package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicReport
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.LocalCellTrust
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.SITE_UNVERIFIED_PREFIX
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 — El contexto de Stable-Site solo aprende de ciclos limpios, sin confirmación ni episodio. */
class StableSiteContextPolicyTest {
    private val clean = CellData(
        isRegistered = true, networkType = "4G LTE", cellId = "100", mnc = "07",
        tac = "31601", dbm = -85, mcc = "214", radioTech = RadioTech.LTE, securityScore = 100
    )

    private fun may(cell: CellData = clean, confirming: Boolean = false, episode: Boolean = false) =
        StableSiteContextPolicy.mayLearn(cell, confirming, episode)

    @Test fun `a clean cycle teaches the site`() = assertTrue(may())

    @Test fun `a suspicious, failing or contradicted cycle does not`() {
        assertFalse(may(clean.copy(isSuspicious = true, securityScore = 40)))
        assertFalse("sub-umbral", may(clean.copy(
            heuristicReport = HeuristicReport(powerJump = HeuristicStatus.FAILED), securityScore = 80)))
        assertFalse(may(clean.copy(localCellTrust = LocalCellTrust(contradictions = setOf("PCI")))))
    }

    /** 3.0 (corte de campaña) — Un H8 fallado (calidad de dato) no impide aprender el sitio. */
    @Test fun `a failed H8 alone still teaches the site`() {
        assertTrue(may(clean.copy(heuristicReport = HeuristicReport(arfcnSanity = HeuristicStatus.FAILED))))
    }

    @Test fun `an ongoing confirmation or episode blocks learning, even on a clean cycle`() {
        assertFalse(may(confirming = true))
        assertFalse(may(episode = true))
    }

    @Test fun `the unverified-site marker itself does not block learning`() {
        assertTrue(may(clean.copy(suspiciousReason = "$SITE_UNVERIFIED_PREFIX NEW_CELL_AT_KNOWN_SITE")))
    }

    @Test fun `confirmation and episode state are visible to the analysis`() {
        var now = 0L
        val temporal = TemporalConfidence(elapsedRealtimeMs = { now })
        assertFalse(temporal.inConfirmation)
        temporal.apply(clean.copy(isSuspicious = true, suspiciousReason = "x"))
        assertTrue(temporal.inConfirmation)
        temporal.apply(clean)
        assertFalse(temporal.inConfirmation)

        val tracker = ThreatEpisodeTracker { now }
        tracker.apply(clean.copy(heuristicReport = HeuristicReport(powerJump = HeuristicStatus.FAILED)), 1L)
        assertFalse(tracker.hasOpenEpisode())
        now += 5_000
        tracker.apply(clean.copy(heuristicReport = HeuristicReport(tacDeviation = HeuristicStatus.FAILED)), 2L)
        assertTrue(tracker.hasOpenEpisode())
        now += 31_000
        assertFalse("caduca con la retención", tracker.hasOpenEpisode())
    }

    @Test fun `the service records Stable-Site context only through the policy`() {
        val service = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        val before = service.substringBefore("dbHelper.recordStableSiteContext(")
        val gate = before.substringAfterLast("analyzedList.firstOrNull()")
        assertTrue(gate.contains("StableSiteContextPolicy.mayLearn("))
        assertTrue(gate.contains("inConfirmation = temporalConfidence.inConfirmation"))
        assertTrue(gate.contains("episodeOpen = threatEpisodeTracker.hasOpenEpisode()"))
        assertTrue("una sola llamada", service.split("dbHelper.recordStableSiteContext(").size == 2)
    }

    /**
     * 3.0 (corte de campaña) — Un análisis descartado en mayPublish no enseña al sitio: el aprendizaje
     * va después del bloque de publicación y solo si publicó. Antes se escribía antes del gate.
     */
    @Test fun `only a published cycle teaches the site`() {
        val service = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        val gate = service.indexOf("if (!collectionGeneration.mayPublish(cycleTicket)) return@withContext false")
        val learn = service.indexOf("if (published) analyzedList.firstOrNull()")
        val record = service.indexOf("dbHelper.recordStableSiteContext(")
        assertTrue(service.contains("val published = withContext(Dispatchers.Main) {"))
        assertTrue("el gate existe", gate > 0)
        assertTrue("aprende después del gate y solo si publicó", gate < learn && learn < record)
        assertFalse("nada lo escribe antes del gate", service.substring(0, gate).contains("recordStableSiteContext("))
    }

    @Test fun `the deliberate exceptions to the clean-row rule are written down`() {
        val db = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/storage/CellDbHelper.kt").readText()
        assertTrue(db.substringBefore("val globallyKnown = scalar(").endsWith(
            "// aprendizaje, solo distingue una celda nunca vista de una ya conocida en otro sitio.\n        "))
        assertTrue(db.contains("// 3.0 — Ventana de 90 días, frente a los 30 del baseline de potencia"))
    }
}
