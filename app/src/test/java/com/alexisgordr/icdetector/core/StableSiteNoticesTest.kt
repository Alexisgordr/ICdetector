package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.0 — Avisos silenciosos de Stable-Site: cuándo se avisa y cuándo se abstiene. Las decisiones
 * salen del evaluador real (StableSiteEvaluator, sin recalibrar) y el episodio se guarda como en
 * CellDbHelper: `shadow_recorded` solo pasa de 0 a 1, nunca vuelve.
 */
class StableSiteNoticesTest {
    private val static = MotionEvidence(MotionState.STATIC_CONFIRMED, 130, 8f, 2f)
    private val moving = MotionEvidence(MotionState.MOVING, 60, 8f, 300f)
    private val previous = "214-07-31601-100-LTE"

    private fun evidence(
        state: StableSiteFeatureState = StableSiteFeatureState.ACTIVE,
        motion: MotionEvidence = static,
        currentServingDays: Int = 0,
        currentNeighbourDays: Int = 0,
        previousIdentity: String? = previous,
        siteKey: String? = "site"
    ) = StableSiteEvidence(siteKey, state, siteServingDays = 7, siteStaticDays = 3, siteNeighbourDays = 3,
        currentServingDays = currentServingDays, currentNeighbourDays = currentNeighbourDays,
        previousServingDays = 7, motion = motion, servingObservations = 40, previousIdentity = previousIdentity)

    /** Lo que hace CellDbHelper.applyStableSiteDecision con un episodio (persistente entre procesos). */
    private class Episodes {
        val recorded = mutableMapOf<String, Boolean>()
        val firstSeen = mutableMapOf<String, Long>()
        fun apply(id: String, firstSeenMs: Long, d: StableSiteDecision): StableSiteDecision {
            firstSeen.putIfAbsent(id, firstSeenMs)
            val newly = d.wouldTrigger && recorded[id] != true
            if (d.wouldTrigger) recorded[id] = true
            return d.copy(episodeRecord = StableSiteEpisodeRecord(id, firstSeen.getValue(id), newly))
        }
    }

    private class Notifier(var enabled: Boolean = true, var since: Long? = 0L) {
        val posted = mutableListOf<String>()
        fun onDecision(d: StableSiteDecision) {
            if (StableSiteNotices.shouldNotify(d, enabled, since)) posted += d.episodeRecord!!.episodeId
        }
    }

    private fun decide(e: StableSiteEvidence) = StableSiteEvaluator.evaluate(e)

    @Test fun `an immature site never notifies, and reaching maturity does not replay its episodes`() {
        val store = Episodes(); val n = Notifier()
        listOf(StableSiteFeatureState.BOOTSTRAP, StableSiteFeatureState.LEARNING, StableSiteFeatureState.SHADOW_READY).forEach {
            n.onDecision(store.apply("e-$it", 1_000, decide(evidence(state = it))))
        }
        assertEquals(emptyList<String>(), n.posted)
        assertEquals("SHADOW_READY sí queda registrado en sombra", true, store.recorded["e-SHADOW_READY"])
        // El sitio madura y el MISMO episodio se vuelve a evaluar: ya estaba registrado, no avisa.
        n.onDecision(store.apply("e-SHADOW_READY", 1_000, decide(evidence(state = StableSiteFeatureState.ACTIVE))))
        assertEquals(emptyList<String>(), n.posted)
        // Llegar a la madurez sin ningún cambio de celda nuevo: no hay episodio y no hay aviso.
        n.onDecision(decide(evidence(currentServingDays = 7)))
        assertEquals(emptyList<String>(), n.posted)
    }

    @Test fun `a habitual change at a mature site does not notify`() {
        val store = Episodes(); val n = Notifier()
        n.onDecision(store.apply("known", 1_000, decide(evidence(currentServingDays = 3))))      // servidora habitual
        n.onDecision(store.apply("neigh", 1_000, decide(evidence(currentNeighbourDays = 2))))   // vecina conocida
        assertEquals(emptyList<String>(), n.posted)
        assertFalse(store.recorded["known"] == true)
    }

    @Test fun `an evaluable deviation after maturity gives exactly one notice`() {
        val store = Episodes(); val n = Notifier()
        repeat(20) { n.onDecision(store.apply("e1", 1_000, decide(evidence()))) }   // una lectura por ciclo
        assertEquals(listOf("e1"), n.posted)
        // Otro episodio distinto, más tarde: su propio aviso.
        n.onDecision(store.apply("e2", 9_000, decide(evidence())))
        assertEquals(listOf("e1", "e2"), n.posted)
    }

    @Test fun `with the preference off nothing is posted but the episode is kept, and turning it on does not replay it`() {
        val store = Episodes(); val n = Notifier(enabled = false)
        n.onDecision(store.apply("e1", 1_000, decide(evidence())))
        assertEquals(emptyList<String>(), n.posted)
        assertEquals(true, store.recorded["e1"])
        n.enabled = true
        n.onDecision(store.apply("e1", 1_000, decide(evidence())))
        assertEquals(emptyList<String>(), n.posted)
    }

    @Test fun `movement, stale GPS and missing data abstain and keep the context`() {
        val store = Episodes(); val n = Notifier()
        n.onDecision(store.apply("moving", 1_000, decide(evidence(motion = moving))))
        // GPS antiguo: el clasificador real, 61 s después del último fix, ya no confirma reposo.
        val c = MotionClassifier(); val boot = 1_000_000L
        listOf(0L, 30_000L, 60_000L, 90_000L, 120_000L).forEach { c.observe(MotionFix(42.8, -1.6, boot + it, 8f, 0f), boot + it) }
        assertEquals(MotionState.STATIC_CONFIRMED, c.current(boot + 125_000).state)
        val stale = c.current(boot + 120_000 + 61_000)
        assertNotEquals(MotionState.STATIC_CONFIRMED, stale.state)
        n.onDecision(store.apply("stale", 1_000, decide(evidence(motion = stale))))
        n.onDecision(store.apply("unknown", 1_000, decide(evidence(motion = MotionEvidence(MotionState.UNKNOWN)))))
        // Sin lugar o sin celda anterior: no hay con qué comparar.
        n.onDecision(store.apply("nosite", 1_000, decide(evidence(siteKey = null)).copy(siteKey = null)))
        n.onDecision(store.apply("noprev", 1_000, decide(evidence(previousIdentity = null))))
        // Sin evidencia ni episodio: nada.
        n.onDecision(StableSiteDecision(reason = "NO_SERVING_CELL"))
        n.onDecision(decide(evidence()))
        assertEquals(emptyList<String>(), n.posted)
        assertEquals("el contexto se guarda igual", 5, store.firstSeen.size)
    }

    @Test fun `an episode that starts while moving notifies once when it is later evaluated static`() {
        val store = Episodes(); val n = Notifier()
        repeat(3) { n.onDecision(store.apply("e1", 1_000, decide(evidence(motion = moving)))) }
        assertEquals(emptyList<String>(), n.posted)
        repeat(5) { n.onDecision(store.apply("e1", 1_000, decide(evidence()))) }
        assertEquals(listOf("e1"), n.posted)
    }

    @Test fun `a process restart, an app reopen or a restore never duplicate or replay a notice`() {
        val store = Episodes()
        Notifier().also { it.onDecision(store.apply("e1", 1_000, decide(evidence()))); assertEquals(listOf("e1"), it.posted) }
        // Proceso nuevo: memoria vacía, la base conserva shadow_recorded.
        val afterRestart = Notifier()
        repeat(5) { afterRestart.onDecision(store.apply("e1", 1_000, decide(evidence()))) }
        assertEquals(emptyList<String>(), afterRestart.posted)
        // Restauración: base de otro momento con episodios aún sin registrar; el corte pasa a «ahora».
        val restored = Episodes()
        val afterRestore = Notifier(since = 50_000L)
        afterRestore.onDecision(restored.apply("old", 10_000, decide(evidence())))
        assertEquals(emptyList<String>(), afterRestore.posted)
        assertEquals("el episodio restaurado sí se guarda", true, restored.recorded["old"])
        afterRestore.onDecision(restored.apply("new", 60_000, decide(evidence())))
        assertEquals(listOf("new"), afterRestore.posted)
    }

    @Test fun `no notice before the first service start with this version or without a cutoff`() {
        val store = Episodes()
        val n = Notifier(since = 5_000L)
        n.onDecision(store.apply("before", 4_999, decide(evidence())))
        n.onDecision(store.apply("exact", 5_000, decide(evidence())))
        assertEquals(listOf("exact"), n.posted)
        val noCutoff = Notifier(since = null)
        noCutoff.onDecision(Episodes().apply("x", 9_000, decide(evidence())))
        assertEquals(emptyList<String>(), noCutoff.posted)
    }

    @Test fun `enforcement stays off and the decision is the same with or without the notice`() {
        assertFalse(STABLE_SITE_ENFORCEMENT_ENABLED)
        val d = decide(evidence())
        assertTrue(d.wouldTrigger); assertFalse(d.enforced)
        assertEquals("NEW_SERVING_WHILE_STATIC_AT_MATURE_SITE", d.reason)
        val withRecord = Episodes().apply("e1", 1_000, d)
        assertEquals("el registro del episodio no cambia el veredicto", d, withRecord.copy(episodeRecord = null))
    }

    @Test fun `status shows learning, ready or turned off, and maturity never turns the preference on`() {
        StableSiteFeatureState.entries.forEach { s ->
            assertEquals(StableSiteNotices.Status.DISABLED_BY_USER, StableSiteNotices.status(s, enabled = false))
            val expected = if (s == StableSiteFeatureState.ACTIVE) StableSiteNotices.Status.READY else StableSiteNotices.Status.LEARNING
            assertEquals(expected, StableSiteNotices.status(s, enabled = true))
        }
        assertTrue(StableSiteNotices.DEFAULT_ENABLED)
        assertEquals("stable_site_silent_notices", StableSiteNotices.PREF_ENABLED)
    }

    @Test fun `each episode has its own notification id, away from the fixed ones`() {
        val ids = (1..200).map { StableSiteNotices.notificationId(java.util.UUID.randomUUID().toString()) }
        ids.forEach { assertTrue(it >= 1_000_000) }
        assertEquals(StableSiteNotices.notificationId("e1"), StableSiteNotices.notificationId("e1"))
        assertTrue(ids.toSet().size > 190)
    }
}
