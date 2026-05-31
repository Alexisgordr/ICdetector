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
 */
object BayesianScorer {

    // Probabilidad base de encontrar un IMSI catcher
    // en entorno urbano europeo normal
    private const val PRIOR = 0.02f

    // Likelihood ratios por heurística
    // LR > 1 → aumenta probabilidad de IMSI catcher
    // LR < 1 → reduce probabilidad
    private val LIKELIHOOD_RATIOS = mapOf(
        "isolated"    to 3.5f,  // Shaik et al. — 34% IMSI catchers sin vecinas
        "powerJump"   to 5.5f,  // LTEInspector — power dominance es fiable
        "mccMismatch" to 9.0f,  // Alta especificidad, muy raro legítimamente
        "mncCount"    to 2.0f,  // Baja especificidad en mercados con MVNOs
        "tacDev"      to 6.5f,  // Raza et al. — TAC mismatch alta especificidad
        "taDistance"  to 7.0f,  // Físicamente irrefutable cuando TA es real
        "ghostCells"  to 4.5f,  // Ambiente RF artificialmente controlado
        "arfcn"       to 8.0f,  // ARFCN fuera de 3GPP spec — imposible legítimo
        "ciphering"   to 12.0f, // A5/0 en LTE es indicador crítico (ETSI)
        "pingPong"    to 3.0f,  // Baja especificidad standalone
        "h11"         to 6.0f,  // Compromiso: potente vs móviles, ciego vs fijos
        "latency"     to 1.8f   // Experimental — demasiadas variables confundentes
    )

    // Factores de corrección por estado de verificación DB
    private val DB_RATIOS = mapOf(
        "VERIFIED"  to 0.4f,  // Reduce probabilidad — celda conocida y legítima
        "NOT_FOUND" to 1.4f,  // Aumenta ligeramente — celda desconocida
        "PENDING"   to 1.0f,  // Neutral — sin información
        "ERROR"     to 1.1f   // Ligeramente sospechoso — fallo de verificación
    )

    /**
     * Calcula la probabilidad estimada de IMSI catcher (0-100%)
     * usando actualización Bayesiana secuencial (Naive Bayes).
     *
     * P(IMSI|evidencia) = LR * P(IMSI) / (LR * P(IMSI) + P(¬IMSI))
     */
    fun calculate(
        failedHeuristics: List<String>,
        verificationStatus: String,
        isLatencyAnomalous: Boolean
    ): Float {
        var posterior = PRIOR

        // Actualizar con cada heurística fallida
        failedHeuristics.forEach { heuristic ->
            val lr = LIKELIHOOD_RATIOS[heuristic] ?: 1.0f
            posterior = bayesUpdate(posterior, lr)
        }

        // Actualizar con estado de verificación DB
        val dbLr = DB_RATIOS[verificationStatus] ?: 1.0f
        posterior = bayesUpdate(posterior, dbLr)

        // Actualizar con latencia si está activa y anómala
        if (isLatencyAnomalous) {
            posterior = bayesUpdate(posterior, LIKELIHOOD_RATIOS["latency"]!!)
        }

        return (posterior * 100f).coerceIn(0f, 100f)
    }

    private fun bayesUpdate(prior: Float, likelihoodRatio: Float): Float {
        val numerator = prior * likelihoodRatio
        val denominator = numerator + (1f - prior)
        return numerator / denominator
    }
}
