package com.alexisgordr.icdetector.core

/**
 * Bayesian IMSI-Catcher Threat Scorer
 *
 * Likelihood ratios basados en:
 * - LTEInspector (Purdue University, 2018)
 * - IMSI-Catch Me If You Can (Shaik et al., 2016)
 * - Practical attacks against privacy in LTE (Raza et al., 2018)
 *
 * Prior P(IMSI) = 0.02 (2%) — estimación conservadora
 * para entorno urbano europeo sin contexto especial.
 *
 * Nota: powerJump, ghostCells e isolated están agrupadas
 * como heurísticas correlacionadas (mismo fenómeno RF).
 * Solo se aplica la más potente del grupo para evitar
 * inflación del posterior por dependencia implícita.
 */
object BayesianScorer {

    private const val PRIOR = 0.02f

    private val LIKELIHOOD_RATIOS = mapOf(
        "isolated"    to 3.5f,
        "powerJump"   to 5.5f,
        "mccMismatch" to 9.0f,
        "mncCount"    to 2.0f,
        "tacDev"      to 6.5f,
        "taDistance"  to 7.0f,
        "ghostCells"  to 4.5f,
        "arfcn"       to 8.0f,
        "ciphering"   to 12.0f,
        "pingPong"    to 3.0f,
        "h11"         to 6.0f,
        "latency"     to 1.8f
    )

    private val DB_RATIOS = mapOf(
        "VERIFIED"  to 0.4f,
        "NOT_FOUND" to 1.4f,
        "PENDING"   to 1.0f,
        "ERROR"     to 1.1f
    )

    // Heurísticas correlacionadas — describen el mismo fenómeno RF
    // (IMSI catcher dominando la señal sobre las vecinas)
    private val RF_DOMINANCE_GROUP = listOf("powerJump", "ghostCells", "isolated")

    fun calculate(
        failedHeuristics: List<String>,
        verificationStatus: String,
        isLatencyAnomalous: Boolean
    ): Float {
        var posterior = PRIOR

        // Grupo RF dominance — solo aplicar la más potente
        // para evitar inflación por dependencia implícita
        val rfDominanceMax = failedHeuristics
            .filter { it in RF_DOMINANCE_GROUP }
            .maxOfOrNull { LIKELIHOOD_RATIOS[it] ?: 1.0f } ?: 1.0f

        if (rfDominanceMax > 1.0f) {
            posterior = bayesUpdate(posterior, rfDominanceMax)
        }

        // Heurísticas independientes — actualizar individualmente
        failedHeuristics
            .filter { it !in RF_DOMINANCE_GROUP }
            .forEach { heuristic ->
                val lr = LIKELIHOOD_RATIOS[heuristic] ?: 1.0f
                posterior = bayesUpdate(posterior, lr)
            }

        // Factor verificación DB
        val dbLr = DB_RATIOS[verificationStatus] ?: 1.0f
        posterior = bayesUpdate(posterior, dbLr)

        // Factor latencia experimental
        if (isLatencyAnomalous) {
            posterior = bayesUpdate(posterior, LIKELIHOOD_RATIOS["latency"]!!)
        }

        // Saturación en 95% — honestidad epistémica
        // Android userland nunca puede confirmar con certeza absoluta
        return (posterior * 100f).coerceIn(0f, 95f)
    }

    private fun bayesUpdate(prior: Float, likelihoodRatio: Float): Float {
        val numerator = prior * likelihoodRatio
        val denominator = numerator + (1f - prior)
        return numerator / denominator
    }
}
