package com.alexisgordr.icdetector.core

/**
 * Bayesian IMSI-Catcher Threat Scorer
 *
 * Likelihood ratios basados en literatura académica:
 * - LTEInspector (Purdue University, 2018)
 * - IMSI-Catch Me If You Can (Shaik et al., 2016)
 * - Practical attacks against privacy in LTE (Raza et al., 2018)
 *
 * IMPORTANTE: Los valores numéricos son estimaciones razonadas
 * derivadas de la literatura. Los papers describen cualitativamente
 * la especificidad de cada indicador pero NO publican likelihood
 * ratios exactos. Estos valores son interpretaciones expertas,
 * no constantes científicamente medidas.
 *
 * Prior P(IMSI) = 0.02 (2%) — estimación conservadora
 * para entorno urbano europeo sin contexto especial.
 *
 * Heurísticas agrupadas por correlación física para evitar
 * inflación del posterior por dependencia implícita en Naive Bayes.
 */
object BayesianScorer {

    private const val PRIOR = 0.02f

    private val LIKELIHOOD_RATIOS = mapOf(
        // Grupo RF Dominance — señal dominante anómala
        // Expert-derived LR estimate — not directly reported by source paper
        "isolated"    to 3.5f,  // Shaik et al. — 34% IMSI catchers sin vecinas
        "powerJump"   to 5.5f,  // LTEInspector — power dominance fiable en urbano
        "ghostCells"  to 4.5f,  // Ambiente RF artificialmente controlado
        "signalBaseline" to 3.5f, // Potencia anómala vs historial propio (mismo fenómeno de dominancia)

        // Grupo Identidad — parámetros de red anómalos
        // Expert-derived LR estimate — not directly reported by source paper
        "mccMismatch" to 9.0f,  // Alta especificidad — raro legítimamente
        "mncCount"    to 2.0f,  // Baja especificidad en mercados con MVNOs
        "tacDev"      to 6.5f,  // Raza et al. — TAC mismatch alta especificidad

        // Grupo Movilidad — comportamiento temporal anómalo
        // Expert-derived LR estimate — not directly reported by source paper
        "pingPong"    to 3.0f,  // Baja especificidad standalone
        "h11"         to 6.0f,  // Compromiso móvil vs fijo
        "taDistance"  to 7.0f,  // Físicamente irrefutable con TA real
        "rfStability" to 6.0f,  // Misma Cell ID con PCI/ARFCN mutados — clon reconfigurándose

        // Independientes
        // Expert-derived LR estimate — not directly reported by source paper
        "arfcn"       to 8.0f,  // ARFCN fuera 3GPP spec — imposible legítimo
        "ciphering"   to 12.0f, // A5/0 en LTE — indicador crítico (ETSI papers)
        "latency"     to 1.8f,  // Experimental — demasiadas variables confundentes
        "bandDowngrade" to 2.5f  // Downgrade forzado a banda baja
    )

    private val DB_RATIOS = mapOf(
        "VERIFIED"  to 0.4f,
        "NOT_FOUND" to 1.4f,
        "PENDING"   to 1.0f,
        "ERROR"     to 1.1f
    )

    // Grupos de heurísticas correlacionadas
    // Dentro de cada grupo solo se aplica la LR más alta
    // para evitar inflación por dependencia implícita

    // RF Dominance — correlación fuerte, mismo fenómeno físico
    // Un IMSI catcher dominando la señal produce los tres simultáneamente.
    // signalBaseline (potencia anómala vs historial) es el mismo fenómeno de
    // dominancia visto contra el propio histórico, así que se agrupa aquí para
    // que solo cuente la LR más alta y no infle el posterior junto a powerJump.
    private val RF_DOMINANCE_GROUP = listOf("powerJump", "ghostCells", "isolated", "signalBaseline", "bandDowngrade")

    // Movilidad — correlación moderada, comportamiento temporal.
    // rfStability (PCI/ARFCN mutados de una misma Cell ID) se agrupa aquí porque comparte
    // con h11 el mismo fenómeno de identidad RF inconsistente; juntas no deben inflar el
    // posterior, así que solo cuenta la LR más alta del grupo.
    private val MOBILITY_GROUP = listOf("pingPong", "h11", "taDistance", "rfStability")

    // mncCount y tacDev — correlación débil entre sí
    // pueden aparecer juntos en estaciones mal configuradas
    private val WEAK_IDENTITY_GROUP = listOf("mncCount", "tacDev")

    // mccMismatch — independiente, evidencia muy fuerte por sí sola
    // No se agrupa — siempre actualiza individualmente

    private val ALL_GROUPS = listOf(RF_DOMINANCE_GROUP, MOBILITY_GROUP, WEAK_IDENTITY_GROUP)
    private val ALL_GROUPED = ALL_GROUPS.flatten()

    // --- Bayesiano adaptativo al contexto (H6 de la lista de mejoras) ---
    // Heurísticas de DOMINANCIA RF que dan falsos positivos en entornos dispersos (rural,
    // macroceldas): una sola antena dominando sin vecinas es ANÓMALO en ciudad pero NORMAL en
    // el campo. Suavizamos SOLO estas según la densidad del entorno (nº de vecinas visibles).
    // NO se tocan las físicamente sólidas en cualquier contexto (MCC, ARFCN, cifrado, H11,
    // H13/signalBaseline, H14/bandDowngrade, H15): esas valen igual en ciudad o campo.
    private val ENV_SENSITIVE = setOf("isolated", "powerJump", "ghostCells")

    // Factor [0..1] que escala la FUERZA de la evidencia (LR) de las heurísticas sensibles.
    // 1.0 = sin cambios (urbano/denso o densidad desconocida). <1.0 = evidencia suavizada
    // (disperso/rural). Nunca invierte la evidencia: el LR efectivo se acerca a 1.0 (neutro),
    // no por debajo. neighborCount < 0 significa "desconocido" -> sin cambios (compatibilidad).
    private fun densityFactor(neighborCount: Int): Float = when {
        neighborCount < 0  -> 1.0f   // desconocido -> comportamiento clásico
        neighborCount == 0 -> 0.5f   // muy disperso (rural / macrocelda dominante)
        neighborCount <= 2 -> 0.7f   // disperso
        else               -> 1.0f   // denso (urbano) -> sin cambios
    }

    fun calculate(
        failedHeuristics: List<String>,
        verificationStatus: String,
        isLatencyAnomalous: Boolean,
        neighborCount: Int = -1
    ): Float {
        var posterior = PRIOR
        val df = densityFactor(neighborCount)

        // Para cada grupo — aplicar solo la LR más potente.
        // Las heurísticas sensibles al entorno ven su LR suavizada hacia 1.0 (neutro) según
        // la densidad: 1 + (LR - 1) * df. En denso/desconocido df=1.0 -> sin cambios.
        ALL_GROUPS.forEach { group ->
            val maxLr = failedHeuristics
                .filter { it in group }
                .maxOfOrNull { h ->
                    val base = LIKELIHOOD_RATIOS[h] ?: 1.0f
                    if (h in ENV_SENSITIVE) 1.0f + (base - 1.0f) * df else base
                } ?: 1.0f

            if (maxLr > 1.0f) {
                posterior = bayesUpdate(posterior, maxLr)
            }
        }

        // Heurísticas independientes — actualizar individualmente
        failedHeuristics
            .filter { it !in ALL_GROUPED }
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
