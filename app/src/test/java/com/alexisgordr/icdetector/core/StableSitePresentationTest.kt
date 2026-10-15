package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.StableSitePresentation.EpisodeKind
import com.alexisgordr.icdetector.core.StableSitePresentation.EpisodeReason
import com.alexisgordr.icdetector.core.StableSitePresentation.Missing
import com.alexisgordr.icdetector.core.StableSitePresentation.Motion
import com.alexisgordr.icdetector.core.StableSitePresentation.SimpleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 3.0 — Stable-Site en lenguaje sencillo: estados, requisitos, motivos y episodios tal como se guardaron. */
class StableSitePresentationTest {

    private fun site(sd: Int, st: Int, fn: Int, rf: Int, ob: Int) =
        StableSiteSummary("k", servingObservations = ob, servingDays = sd, staticDays = st, fullNeighbourDays = fn,
            rfNeighbourDays = rf, firstSeenMs = 1, lastSeenMs = 2)

    private fun episode(shadowRecorded: Boolean, reason: String?, evaluatedMs: Long?, motion: String?, state: String?) =
        StableSiteEpisode("e1", "k", "A", "B", firstSeenMs = 10, lastSeenMs = 20, evaluatedMs = evaluatedMs, closedMs = null,
            shadowRecorded = shadowRecorded, reason = reason, motionState = motion, featureStateAtEpisode = state,
            globallyKnown = null, knownAtSite = null, seenAsNeighbour = null, servingDays = null, neighbourDays = null, corroborated = false)

    @Test fun `each internal state has its plain name, and ACTIVE is only a prepared reference`() {
        assertEquals(SimpleState.LEARNING_PLACE, StableSitePresentation.simpleState(StableSiteFeatureState.BOOTSTRAP))
        assertEquals(SimpleState.LEARNING_PLACE, StableSitePresentation.simpleState(StableSiteFeatureState.LEARNING))
        assertEquals(SimpleState.REFERENCE_PREPARING, StableSitePresentation.simpleState(StableSiteFeatureState.SHADOW_READY))
        assertEquals(SimpleState.REFERENCE_READY, StableSitePresentation.simpleState(StableSiteFeatureState.ACTIVE))
        assertEquals(SimpleState.NOT_ENOUGH_DATA, StableSitePresentation.simpleState(StableSiteFeatureState.UNAVAILABLE))
        assertEquals(SimpleState.REFERENCE_PREPARING, StableSitePresentation.simpleStateOf("SHADOW_READY"))
        assertNull("un estado guardado ausente no se rellena", StableSitePresentation.simpleStateOf(null))
        assertNull(StableSitePresentation.simpleStateOf("FOO"))
    }

    @Test fun `every maturity reason is translated, and nothing is missing only when the policy says ACTIVE`() {
        for (sd in 0..9) for (st in 0..5) for (fn in 0..5) for (rf in 0..5) for (ob in listOf(0, 29, 30, 500)) {
            val s = site(sd, st, fn, rf, ob)
            val m = s.maturity
            val missing = StableSitePresentation.missing(m.reason)
            assertNotEquals(m.reason, Missing.UNKNOWN, missing)
            assertEquals(m.reason, m.state == StableSiteFeatureState.ACTIVE, missing == Missing.NOTHING)
            assertEquals("$sd/$st/$fn/$rf/$ob", m.state == StableSiteFeatureState.ACTIVE,
                StableSitePresentation.requirements(s).all { it.met })
        }
        assertEquals(Missing.SERVING_DAYS, StableSitePresentation.missing("NEEDS_SERVING_DAYS"))
        assertEquals(Missing.STATIC_DAYS, StableSitePresentation.missing("NEEDS_STATIC_DAYS"))
        assertEquals(Missing.SERVING_OBSERVATIONS, StableSitePresentation.missing("NEEDS_SERVING_OBSERVATIONS"))
        assertEquals(Missing.NEIGHBOUR_DAYS, StableSitePresentation.missing("NEEDS_FULL_NEIGHBOUR_DAYS"))
        assertEquals(Missing.NEIGHBOUR_DAYS, StableSitePresentation.missing("NEEDS_RF_NEIGHBOUR_DAYS"))
        assertEquals(Missing.NO_NEIGHBOUR_DATA, StableSitePresentation.missing("NO_USABLE_NEIGHBOUR_DATA"))
        assertEquals(Missing.UNKNOWN, StableSitePresentation.missing(""))
    }

    @Test fun `requirements use the policy constants and show value and target, not a countdown`() {
        val r = StableSitePresentation.requirements(site(sd = 4, st = 3, fn = 1, rf = 2, ob = 12)).associateBy { it.kind }
        assertEquals(StableSitePresentation.Requirement(StableSitePresentation.Requirement.Kind.SERVING_DAYS, false, 4,
            StableSiteMaturityPolicy.ACTIVE_SERVING_DAYS), r.getValue(StableSitePresentation.Requirement.Kind.SERVING_DAYS))
        assertEquals(true, r.getValue(StableSitePresentation.Requirement.Kind.STATIC_DAYS).met)
        val n = r.getValue(StableSitePresentation.Requirement.Kind.NEIGHBOUR_DAYS)
        assertEquals("el mayor de vecinas completas y RF", 2, n.value)
        assertEquals(StableSiteMaturityPolicy.ACTIVE_NEIGHBOUR_DAYS, n.target)
        assertEquals(StableSiteMaturityPolicy.ACTIVE_SERVING_OBSERVATIONS, r.getValue(StableSitePresentation.Requirement.Kind.SERVING_OBSERVATIONS).target)
    }

    @Test fun `stored episode reasons and movement are translated, missing ones stay unknown`() {
        assertEquals(EpisodeReason.FEW_PRIOR_SIGHTINGS_WHILE_STILL, StableSitePresentation.episodeReason("NEW_SERVING_WHILE_STATIC_AT_MATURE_SITE"))
        assertEquals(EpisodeReason.NOVELTY_CONDITIONS_NOT_MET, StableSitePresentation.episodeReason("INSUFFICIENT_CONTEXT"))
        assertEquals(EpisodeReason.HOLD_APPLIED, StableSitePresentation.episodeReason("STABLE_SITE_NOVELTY_HOLD"))
        assertEquals(EpisodeReason.NO_RELIABLE_LOCATION, StableSitePresentation.episodeReason("NO_RELIABLE_LOCATION"))
        assertEquals(EpisodeReason.NO_SITE_DATA, StableSitePresentation.episodeReason("NO_SITE_DATA"))
        assertEquals(EpisodeReason.UNKNOWN, StableSitePresentation.episodeReason(null))
        assertEquals(EpisodeReason.UNKNOWN, StableSitePresentation.episodeReason("SOMETHING_NEW"))
        assertEquals(Motion.STILL, StableSitePresentation.motion(MotionState.STATIC_CONFIRMED.name))
        assertEquals(Motion.MOVING, StableSitePresentation.motion(MotionState.MOVING.name))
        assertEquals(Motion.UNKNOWN, StableSitePresentation.motion(MotionState.UNKNOWN.name))
        assertEquals(Motion.UNKNOWN, StableSitePresentation.motion(null))
    }

    @Test fun `the shadow mark is the episode's, independent of the last saved evaluation`() {
        // Marcado en sombra durante el episodio, pero la última evaluación ya no tenía contexto.
        val marked = episode(shadowRecorded = true, reason = "INSUFFICIENT_CONTEXT", evaluatedMs = 30, motion = "MOVING", state = "LEARNING")
        assertEquals(EpisodeKind.NOVELTY_RECORDED_IN_SHADOW, StableSitePresentation.episodeKind(marked))
        assertEquals(EpisodeReason.NOVELTY_CONDITIONS_NOT_MET, StableSitePresentation.episodeReason(marked.reason))
        assertEquals(SimpleState.LEARNING_PLACE, StableSitePresentation.simpleStateOf(marked.featureStateAtEpisode))
        // Sin marca: un cambio observado, aunque la última evaluación tenga un motivo de novedad.
        val observed = episode(shadowRecorded = false, reason = "NEW_SERVING_WHILE_STATIC_AT_MATURE_SITE", evaluatedMs = 30, motion = null, state = "ACTIVE")
        assertEquals(EpisodeKind.OBSERVED_CHANGE, StableSitePresentation.episodeKind(observed))
        // Sin evaluación guardada: nada se deduce ni se completa.
        val bare = episode(shadowRecorded = false, reason = null, evaluatedMs = null, motion = null, state = null)
        assertEquals(EpisodeReason.UNKNOWN, StableSitePresentation.episodeReason(bare.reason))
        assertEquals(Motion.UNKNOWN, StableSitePresentation.motion(bare.motionState))
        assertNull(StableSitePresentation.simpleStateOf(bare.featureStateAtEpisode))
    }
}
