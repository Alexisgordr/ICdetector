package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.0 — Avisos silenciosos de Stable-Site: cuándo se avisa y cuándo se abstiene. Las decisiones
 * salen del evaluador real (StableSiteEvaluator, sin recalibrar). [Cycle] reproduce el orden del
 * servicio: evaluar el sitio, observar el episodio (anotar el pendiente si empieza maduro), guardar
 * la decisión y, solo si el ciclo se publica, decidir el aviso.
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

    private fun decide(e: StableSiteEvidence) = StableSiteEvaluator.evaluate(e)

    /** Estado persistente: el pendiente (preferencia) y si hay un episodio abierto (base). */
    private class Phone {
        var pending: String? = null
        var openEpisode: String? = null
        var enabled = true
        val posted = mutableListOf<String>()
        val log = mutableListOf<StableSiteNotices.Action>()
        private var next = 0

        /** Un ciclo del servicio. [changed]: este ciclo ve un cambio de servidora. */
        fun cycle(d: StableSiteDecision, changed: Boolean = false, published: Boolean = true): StableSiteDecision {
            val observation = if (changed) StableSiteEpisodeObservation("e${++next}", created = true).also { openEpisode = it.episodeId }
                else StableSiteEpisodeObservation(openEpisode, created = false)
            pending = StableSiteNotices.pendingAfterObservation(pending, observation, d)
            val saved = d.copy(episodeId = openEpisode)
            if (!published) return saved                       // análisis descartado: no llega a decidir
            val action = StableSiteNotices.decide(saved, enabled, pending)
            if (action != StableSiteNotices.Action.NONE) {
                pending = null                                  // se consume antes de notificar
                log += action
                if (action == StableSiteNotices.Action.NOTIFY) posted += saved.episodeId!!
            }
            return saved
        }
    }

    @Test fun `an episode that started while learning never notifies after the site matures (reproduction)`() {
        val p = Phone()
        p.cycle(decide(evidence(state = StableSiteFeatureState.LEARNING, motion = moving)), changed = true)
        p.cycle(decide(evidence(state = StableSiteFeatureState.SHADOW_READY, motion = moving)))
        assertNull(p.pending)
        // El sitio madura con el mismo episodio abierto y el teléfono se queda quieto: sin aviso.
        val mature = decide(evidence())
        assertTrue("el evaluador real sí lo marcaría", mature.wouldTrigger)
        repeat(5) { p.cycle(mature) }
        assertEquals(emptyList<String>(), p.posted)
        // Un cambio NUEVO, ya maduro, sí avisa.
        p.cycle(mature, changed = true)
        assertEquals(listOf("e2"), p.posted)
    }

    @Test fun `an immature site never notifies and reaching maturity does not replay`() {
        val p = Phone()
        listOf(StableSiteFeatureState.BOOTSTRAP, StableSiteFeatureState.LEARNING, StableSiteFeatureState.SHADOW_READY).forEach {
            p.cycle(decide(evidence(state = it)), changed = true)
            p.cycle(decide(evidence(state = it)))
        }
        p.cycle(decide(evidence(currentServingDays = 7)))   // madura sin cambio de celda
        p.cycle(decide(evidence()))                          // y se evalúa el episodio inmaduro
        assertEquals(emptyList<String>(), p.posted)
    }

    @Test fun `a change seen without a reliable location at its start never notifies`() {
        val p = Phone()
        p.cycle(StableSiteDecision(reason = "NO_SERVING_CELL"), changed = true)   // sin sitio al empezar
        repeat(3) { p.cycle(decide(evidence())) }
        assertEquals(emptyList<String>(), p.posted)
    }

    @Test fun `a habitual change at a mature site does not notify`() {
        val p = Phone()
        p.cycle(decide(evidence(currentServingDays = 3)), changed = true)
        p.cycle(decide(evidence(currentNeighbourDays = 2)), changed = true)
        assertEquals(emptyList<String>(), p.posted)
    }

    @Test fun `an evaluable deviation after maturity gives exactly one notice`() {
        val p = Phone()
        p.cycle(decide(evidence()), changed = true)
        repeat(20) { p.cycle(decide(evidence())) }
        assertEquals(listOf("e1"), p.posted)
        p.cycle(decide(evidence()), changed = true)
        assertEquals(listOf("e1", "e2"), p.posted)
    }

    @Test fun `an episode that starts moving at a mature site notifies once when later still`() {
        val p = Phone()
        p.cycle(decide(evidence(motion = moving)), changed = true)
        repeat(3) { p.cycle(decide(evidence(motion = moving))) }
        assertEquals("e1", p.pending)
        repeat(5) { p.cycle(decide(evidence())) }
        assertEquals(listOf("e1"), p.posted)
    }

    @Test fun `a discarded analysis never notifies, and the next valid cycle does once`() {
        val p = Phone()
        p.cycle(decide(evidence()), changed = true, published = false)
        p.cycle(decide(evidence()), published = false)
        assertEquals(emptyList<String>(), p.posted)
        assertEquals("el pendiente se conserva", "e1", p.pending)
        p.cycle(decide(evidence()))
        p.cycle(decide(evidence()))
        assertEquals(listOf("e1"), p.posted)
    }

    @Test fun `with the preference off nothing is posted, and turning it on does not replay`() {
        val p = Phone().apply { enabled = false }
        p.cycle(decide(evidence()), changed = true)
        assertEquals(emptyList<String>(), p.posted)
        assertEquals(listOf(StableSiteNotices.Action.CONSUME_SILENTLY), p.log)
        p.enabled = true
        repeat(3) { p.cycle(decide(evidence())) }
        assertEquals(emptyList<String>(), p.posted)
    }

    @Test fun `movement, stale GPS and missing data abstain and keep the episode pending`() {
        val p = Phone()
        p.cycle(decide(evidence(motion = moving)), changed = true)
        val c = MotionClassifier(); val boot = 1_000_000L
        listOf(0L, 30_000L, 60_000L, 90_000L, 120_000L).forEach { c.observe(MotionFix(42.8, -1.6, boot + it, 8f, 0f), boot + it) }
        assertEquals(MotionState.STATIC_CONFIRMED, c.current(boot + 125_000).state)
        val stale = c.current(boot + 120_000 + 61_000)
        assertNotEquals(MotionState.STATIC_CONFIRMED, stale.state)
        p.cycle(decide(evidence(motion = stale)))
        p.cycle(decide(evidence(motion = MotionEvidence(MotionState.UNKNOWN))))
        p.cycle(decide(evidence(siteKey = null)).copy(siteKey = null))
        p.cycle(decide(evidence(previousIdentity = null)))
        p.cycle(StableSiteDecision(reason = "NO_SERVING_CELL"))
        assertEquals(emptyList<String>(), p.posted)
        assertEquals("sigue pendiente", "e1", p.pending)
    }

    @Test fun `a restart or app reopen never repeats, and a restore never notifies old episodes`() {
        val p = Phone()
        p.cycle(decide(evidence()), changed = true)
        assertEquals(listOf("e1"), p.posted)
        // Proceso nuevo: la preferencia (pendiente vacío) y la base siguen; la memoria no.
        val pendingOnDisk = p.pending
        val restarted = Phone().apply { pending = pendingOnDisk; openEpisode = "e1" }
        repeat(5) { restarted.cycle(decide(evidence())) }
        assertEquals(emptyList<String>(), restarted.posted)
        // Restauración: RestoreStartup borra el pendiente; el episodio restaurado sigue abierto.
        val restored = Phone().apply { pending = null; openEpisode = "old" }
        repeat(3) { restored.cycle(decide(evidence())) }
        assertEquals(emptyList<String>(), restored.posted)
    }

    @Test fun `a new episode replaces the pending one, and no open episode clears it`() {
        val start = decide(evidence())
        assertEquals("b", StableSiteNotices.pendingAfterObservation("a", StableSiteEpisodeObservation("b", true), start))
        assertNull(StableSiteNotices.pendingAfterObservation("a", StableSiteEpisodeObservation("b", true),
            decide(evidence(state = StableSiteFeatureState.SHADOW_READY))))
        assertNull(StableSiteNotices.pendingAfterObservation("a", StableSiteEpisodeObservation(null, false), start))
        assertEquals("a", StableSiteNotices.pendingAfterObservation("a", StableSiteEpisodeObservation("a", false), start))
        assertNull(StableSiteNotices.pendingAfterObservation(null, StableSiteEpisodeObservation("b", true), start.copy(siteKey = null)))
    }

    @Test fun `enforcement stays off and the episode id does not change the verdict`() {
        assertFalse(STABLE_SITE_ENFORCEMENT_ENABLED)
        val d = decide(evidence())
        assertTrue(d.wouldTrigger); assertFalse(d.enforced)
        assertEquals("NEW_SERVING_WHILE_STATIC_AT_MATURE_SITE", d.reason)
        assertEquals(d, d.copy(episodeId = "e1").copy(episodeId = null))
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
