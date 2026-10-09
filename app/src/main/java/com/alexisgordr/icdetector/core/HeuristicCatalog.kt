package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.ThreatEpisodeTracker.Family
import com.alexisgordr.icdetector.models.HeuristicReport
import com.alexisgordr.icdetector.models.HeuristicStatus

/**
 * 3.0 (#7, O2) — Lista canónica de las 16 reglas.
 *
 * Hasta 2.10.x cada regla vivía con tres nombres en tres sitios: su id en [HeuristicReport]
 * (`H5`), su clave en [BayesianScorer] (`tacDev`) y su familia en [ThreatEpisodeTracker]
 * (IDENTITY). Nada obligaba a que coincidieran: una regla nueva sin peso caía en silencio a un
 * LR de 1,0, o sin familia no contaba para los episodios. Aquí cada regla declara las tres cosas
 * una sola vez; [ThreatEpisodeTracker] la usa directamente y HeuristicCatalogTest comprueba que
 * el informe, el scorer y el analizador coinciden con ella.
 */
enum class HeuristicCatalog(
    val id: String,
    /** Clave con la que ThreatAnalyzer la entrega a BayesianScorer cuando falla. */
    val scorerKey: String,
    val episodeFamily: Family,
    private val statusOf: (HeuristicReport) -> HeuristicStatus
) {
    ISOLATED_CELL("H1", "isolated", Family.RF_DOMINANCE, { it.isolatedCell }),
    POWER_JUMP("H2", "powerJump", Family.RF_DOMINANCE, { it.powerJump }),
    MCC_CONSISTENCY("H3", "mccMismatch", Family.IDENTITY, { it.mccConsistency }),
    MNC_COUNT("H4", "mncCount", Family.IDENTITY, { it.mncCount }),
    TAC_DEVIATION("H5", "tacDev", Family.IDENTITY, { it.tacDeviation }),
    TA_DISTANCE("H6", "taDistance", Family.MOBILITY, { it.taDistance }),
    GHOST_NEIGHBORS("H7", "ghostCells", Family.RF_DOMINANCE, { it.ghostNeighbors }),
    ARFCN_SANITY("H8", "arfcn", Family.IDENTITY, { it.arfcnSanity }),
    HARDWARE_CIPHERING("H9", "ciphering", Family.CROSS_LAYER, { it.hardwareCiphering }),
    PING_PONG("H10", "pingPong", Family.MOBILITY, { it.pingPong }),
    MOBILE_CELL_ID("H11", "h11", Family.MOBILITY, { it.mobileCellId }),
    LATENCY_CORRELATION("H12", "latency", Family.CROSS_LAYER, { it.latencyCorrelation }),
    SIGNAL_BASELINE("H13", "signalBaseline", Family.RF_DOMINANCE, { it.signalBaseline }),
    BAND_DOWNGRADE("H14", "bandDowngrade", Family.MOBILITY, { it.bandDowngrade }),
    RF_STABILITY("H15", "rfStability", Family.IDENTITY, { it.rfStability }),
    TRANSITION_COHERENCE("H16", "transitionCoherence", Family.MOBILITY, { it.transitionCoherence });

    fun status(report: HeuristicReport): HeuristicStatus = statusOf(report)
}
