package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.BayesianScorer.Group as G
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
 *
 * 3.0 (fase 4) — Cada regla declara también su GRUPO del scorer ([scorerGroup]): dentro de un grupo
 * solo cuenta la LR más alta. Familia de episodio y grupo responden a preguntas distintas y en tres
 * reglas no coinciden a propósito ([familyDiffersFromScorer] dice por qué):
 *  - H6: el scorer la agrupa con la dominancia RF (TA ≤ 100 m con señal ≥ -60 dBm es la misma señal
 *    fuerte que H2/H7); el episodio la sigue contando como MOVILIDAD (proximidad al emisor).
 *  - H14: el scorer la agrupa con la dominancia RF (exige celda nueva fuerte, absorbida por H2 si
 *    coinciden); el episodio la cuenta como MOVILIDAD (cambio de banda en un cambio de celda).
 *  - H15: el scorer la agrupa con H11 (identidad RF inconsistente de la misma Cell ID); el episodio
 *    la cuenta como IDENTIDAD.
 * ThreatEpisodeTracker ya advierte que dos familias son dos reglas, no dos hechos independientes:
 * con H11 + H15 el episodio ve dos familias aunque el scorer las trate como un solo fenómeno. Cambiar
 * las familias movería las alarmas; eso es otra decisión.
 */
enum class HeuristicCatalog(
    val id: String,
    /** Clave con la que ThreatAnalyzer la entrega a BayesianScorer cuando falla. */
    val scorerKey: String,
    val episodeFamily: Family,
    /** Grupo de correlación en BayesianScorer (HeuristicCatalogTest lo compara con el real). */
    val scorerGroup: BayesianScorer.Group,
    /** Por qué la familia de episodio no corresponde al grupo del scorer; null si corresponden. */
    val familyDiffersFromScorer: String?,
    private val statusOf: (HeuristicReport) -> HeuristicStatus
) {
    ISOLATED_CELL("H1", "isolated", Family.RF_DOMINANCE, G.RF_DOMINANCE, null, { it.isolatedCell }),
    POWER_JUMP("H2", "powerJump", Family.RF_DOMINANCE, G.RF_DOMINANCE, null, { it.powerJump }),
    MCC_CONSISTENCY("H3", "mccMismatch", Family.IDENTITY, G.INDEPENDENT, null, { it.mccConsistency }),
    MNC_COUNT("H4", "mncCount", Family.IDENTITY, G.WEAK_IDENTITY, null, { it.mncCount }),
    TAC_DEVIATION("H5", "tacDev", Family.IDENTITY, G.WEAK_IDENTITY, null, { it.tacDeviation }),
    TA_DISTANCE("H6", "taDistance", Family.MOBILITY, G.RF_DOMINANCE,
        "Proximidad: para el scorer es la misma señal dominante que H2/H7; para el episodio, movilidad.", { it.taDistance }),
    GHOST_NEIGHBORS("H7", "ghostCells", Family.RF_DOMINANCE, G.RF_DOMINANCE, null, { it.ghostNeighbors }),
    // 3.0.0-beta5 — Aviso de calidad de dato: ni peso en el scorer ni familia de episodio.
    ARFCN_SANITY("H8", "arfcn", Family.DATA_QUALITY, G.NOT_SCORED, null, { it.arfcnSanity }),
    HARDWARE_CIPHERING("H9", "ciphering", Family.CROSS_LAYER, G.INDEPENDENT, null, { it.hardwareCiphering }),
    PING_PONG("H10", "pingPong", Family.MOBILITY, G.MOBILITY, null, { it.pingPong }),
    MOBILE_CELL_ID("H11", "h11", Family.MOBILITY, G.MOBILITY, null, { it.mobileCellId }),
    LATENCY_CORRELATION("H12", "latency", Family.CROSS_LAYER, G.INDEPENDENT, null, { it.latencyCorrelation }),
    SIGNAL_BASELINE("H13", "signalBaseline", Family.RF_DOMINANCE, G.RF_DOMINANCE, null, { it.signalBaseline }),
    BAND_DOWNGRADE("H14", "bandDowngrade", Family.MOBILITY, G.RF_DOMINANCE,
        "Celda nueva fuerte en banda baja: para el scorer, dominancia (absorbida por H2); para el episodio, cambio de celda.", { it.bandDowngrade }),
    RF_STABILITY("H15", "rfStability", Family.IDENTITY, G.MOBILITY,
        "Identidad RF inconsistente de la misma Cell ID: para el scorer, un fenómeno con H11; para el episodio, identidad.", { it.rfStability }),
    TRANSITION_COHERENCE("H16", "transitionCoherence", Family.MOBILITY, G.MOBILITY, null, { it.transitionCoherence });

    fun status(report: HeuristicReport): HeuristicStatus = statusOf(report)

    /** 3.0.0-beta5 — ¿Su fallo es evidencia de amenaza? No para los avisos de calidad de dato (H8). */
    val isEvidence: Boolean get() = scorerGroup != G.NOT_SCORED
}
