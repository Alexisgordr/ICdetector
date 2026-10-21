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
 * Prior P(IMSI) = 0.005 (0,5 %) — estimación conservadora
 * para entorno urbano europeo sin contexto especial.
 * 3.0 (corte de campaña): bajado de 0.02 a 0.005. Con el 2 % una sola señal débil (p. ej. H6 sola,
 * LR 7) ya pintaba un 12,5 %; con 0,5 % se queda en ~3,4 % y hace falta evidencia
 * combinada para subir. Las LR no cambian; solo el punto de partida. Corte de dataset:
 * anomaly_confidence no es comparable entre filas anteriores y posteriores a este cambio.
 *
 * Heurísticas agrupadas por correlación física para evitar
 * inflación del posterior por dependencia implícita en Naive Bayes.
 *
 * CÓMO DEBE LLAMARSE ESTO (v2.1). El resultado NO es una probabilidad en sentido estadístico:
 * es un posterior calculado con likelihood ratios estimados por criterio experto sobre un prior
 * asumido. Nombrarlo "probabilidad de amenaza" invitaba a leer un "88,9 %" como si saliera de una
 * validación empírica que no se ha hecho. Por eso la UI y el CSV lo llaman ahora **confianza de
 * anomalía (no calibrada)**. El número no ha cambiado; la etiqueta ha dejado de prometer de más.
 *
 * Solo podrá llamarse probabilidad calibrada cuando existan suficientes observaciones etiquetadas
 * de escenarios benignos y adversarios como para medir estas LR en lugar de razonarlas. Ese es el
 * punto #7 del roadmap, y el banco de escenarios de ScenarioTest es el primer paso hacia él.
 */
object BayesianScorer {

    internal const val PRIOR = 0.005f

    private val LIKELIHOOD_RATIOS = mapOf(
        // Grupo RF Dominance — señal dominante anómala
        // Expert-derived LR estimate — not directly reported by source paper
        "isolated"    to 3.5f,  // Shaik et al. — 34% IMSI catchers sin vecinas
        "powerJump"   to 5.5f,  // LTEInspector — power dominance fiable en urbano
        "ghostCells"  to 4.5f,  // Ambiente RF artificialmente controlado
        "signalBaseline" to 3.5f, // Potencia anómala vs historial propio (mismo fenómeno de dominancia)
        "taDistance"  to 7.0f,  // H6: proximidad anómala por TA (≤ 100 m con señal ≥ -60 dBm): un emisor muy cerca
        "bandDowngrade" to 2.5f, // H14: celda nueva fuerte en banda baja; absorbida por powerJump si coinciden

        // Grupo Identidad — parámetros de red anómalos
        // Expert-derived LR estimate — not directly reported by source paper
        "mccMismatch" to 9.0f,  // Alta especificidad — raro legítimamente
        "mncCount"    to 2.0f,  // Baja especificidad en mercados con MVNOs
        "tacDev"      to 6.5f,  // Raza et al. — TAC mismatch alta especificidad

        // Grupo Movilidad — comportamiento temporal anómalo
        // Expert-derived LR estimate — not directly reported by source paper
        "pingPong"    to 3.0f,  // Baja especificidad standalone
        "h11"         to 6.0f,  // Compromiso móvil vs fijo
        "rfStability" to 6.0f,  // Misma Cell ID con PCI/ARFCN mutados — clon reconfigurándose
        "transitionCoherence" to 1.8f, // H16 nace conservadora; necesita calibración de campo

        // Independientes
        // Expert-derived LR estimate — not directly reported by source paper
        // 3.0 (corte de campaña) — "arfcn" (H8) ya no está: un ARFCN fuera de la tabla 3GPP no es evidencia de
        // ataque (un IMSI catcher necesita un canal válido para que el móvil se registre), sino un
        // módem que informa mal. H8 es un aviso de calidad de dato ([DATA_QUALITY_KEYS]).
        // 3.0 (corte de campaña) — "ciphering" (H9) tampoco: Android no expone el estado de cifrado a una app sin
        // privilegios, H9 nunca se evalúa y una LR de 12 para una regla que no mide nada no es
        // honesta. Queda reservada ([UNAVAILABLE_KEYS]).
        // 3.1.0 — "latency" (H12) se retira con la sonda de latencia ([UNAVAILABLE_KEYS]).
    )

    /**
     * Razones de verosimilitud del estado de verificación externa. **Todas neutras (1.0) desde
     * v2.1**, por la misma razón por la que la verificación dejó de sumar y restar puntos en
     * [ThreatAnalyzer]: no medían la antena, medían la cobertura de una base colaborativa. Un 1.4
     * para NOT_FOUND en una zona poco mapeada se lo llevaban todas las celdas, y una LR que se
     * aplica a todo el mundo no discrimina a nadie — solo desplaza la escala entera.
     *
     * Se mantienen como mapa, y no se borra el factor, porque la pregunta sigue abierta: la fase de
     * recolección existe precisamente para medir si este estado aporta señal. Cuando haya datos
     * para estimar estas razones en vez de suponerlas, se ponen aquí. Mientras tanto, neutro es la
     * única cifra honesta.
     */
    private val DB_RATIOS = mapOf(
        "VERIFIED"  to 1.0f,
        "NOT_FOUND" to 1.0f,
        "PENDING"   to 1.0f,
        "ERROR"     to 1.0f
    )

    // Grupos de heurísticas correlacionadas
    // Dentro de cada grupo solo se aplica la LR más alta
    // para evitar inflación por dependencia implícita

    // RF Dominance — correlación fuerte, mismo fenómeno físico
    // Un IMSI catcher dominando la señal produce los tres simultáneamente.
    // signalBaseline (potencia anómala vs historial) es el mismo fenómeno de
    // dominancia visto contra el propio histórico, así que se agrupa aquí para
    // que solo cuente la LR más alta y no infle el posterior junto a powerJump.
    //
    // 3.0 — taDistance (H6) va aquí, no en movilidad: solo falla con TA ≤ 100 m Y señal ≥ -60 dBm,
    // que es la misma señal dominante que hace fallar H2 (≥ -75 y salto > 35 dB) y H7 (≥ -65). En
    // grupos distintos, un solo transmisor cercano se multiplicaba: H6 sola 12,5 %, H6 + H2 (vivir
    // bajo una macro de azotea) 44 %. Aquí cuenta solo la mayor, y con 7,0 sigue siendo la que manda.
    // bandDowngrade (H14) exige una celda nueva fuerte: si coincide con powerJump es el mismo hecho.
    private val RF_DOMINANCE_GROUP = listOf("powerJump", "ghostCells", "isolated", "signalBaseline", "bandDowngrade", "taDistance")

    // Movilidad — correlación moderada, comportamiento temporal.
    // rfStability (PCI/ARFCN mutados de una misma Cell ID) se agrupa aquí porque comparte
    // con h11 el mismo fenómeno de identidad RF inconsistente; juntas no deben inflar el
    // posterior, así que solo cuenta la LR más alta del grupo.
    private val MOBILITY_GROUP = listOf("pingPong", "h11", "rfStability", "transitionCoherence")

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
    // NO se tocan las físicamente sólidas en cualquier contexto (MCC, H11,
    // H13/signalBaseline, H14/bandDowngrade, H15): esas valen igual en ciudad o campo.
    private val ENV_SENSITIVE = setOf("isolated", "powerJump", "ghostCells")

    // 3.0 — H6 la amortigua la REPUTACIÓN pero no la densidad: la proximidad no depende de cuántas
    // vecinas haya, pero quien vive al lado de una antena con semanas de historial limpio no debe
    // llevarse cada día la LR entera (con confianza ≥ 80: 7,0 → 4,6, posterior 8,6 %). Si en campo
    // sigue saltando a diario en celdas de confianza, bajar la LR a 5,0.
    private val TRUST_ONLY_SENSITIVE = setOf("taDistance")

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

    // Factor de reputación: una celda con historial limpio probado durante semanas merece
    // menos sensibilidad en las heurísticas DÉBILES (ya ha demostrado ser legítima). Solo
    // amortigua (<=1.0), nunca amplifica. trustScore < 0 (desconocida) o bajo -> sin cambios.
    // Se aplica a ENV_SENSITIVE (junto con la densidad) y a TRUST_ONLY_SENSITIVE (H6, solo esto).
    private fun trustFactor(trustScore: Int): Float = when {
        trustScore < 0    -> 1.0f   // desconocida -> sin cambios
        trustScore >= 80  -> 0.6f   // celda muy probada -> suaviza ruido débil
        trustScore >= 60  -> 0.8f   // celda razonablemente probada
        else              -> 1.0f   // poca confianza -> escrutinio completo
    }

    /**
     * 3.0 (fase 4) — Grupo de correlación del scorer; INDEPENDENT = actualiza por su cuenta.
     * 3.0 (corte de campaña) — NOT_SCORED: la regla no es evidencia y no tiene LR: H8 (se evalúa y se publica
     * como calidad de dato) y H9 (reservada: no se puede evaluar desde una app).
     */
    enum class Group { RF_DOMINANCE, MOBILITY, WEAK_IDENTITY, INDEPENDENT, NOT_SCORED }

    /** 3.0 (corte de campaña) — Claves de reglas que son avisos de calidad de dato, sin LR: H8. */
    internal val DATA_QUALITY_KEYS = setOf("arfcn")

    /**
     * 3.0 (corte de campaña) — Claves de reglas que no se evalúan, sin LR: H9 (cifrado; una app no
     * puede verlo) y, desde 3.1.0, H12 (latencia; retirada con la sonda de red).
     */
    internal val UNAVAILABLE_KEYS = setOf("ciphering", "latency")

    /** Claves sin LR: ni calidad de dato ni reglas reservadas son evidencia. */
    internal val NOT_SCORED_KEYS = DATA_QUALITY_KEYS + UNAVAILABLE_KEYS

    /** Grupo de una clave, para declararlo y comprobarlo en HeuristicCatalog. */
    internal fun groupOf(key: String): Group = when (key) {
        in NOT_SCORED_KEYS -> Group.NOT_SCORED
        in RF_DOMINANCE_GROUP -> Group.RF_DOMINANCE
        in MOBILITY_GROUP -> Group.MOBILITY
        in WEAK_IDENTITY_GROUP -> Group.WEAK_IDENTITY
        else -> Group.INDEPENDENT
    }

    /** 3.0 (#7, O2) — Claves con peso, para comprobar que coinciden con HeuristicCatalog. */
    internal val weightedKeys: Set<String> get() = LIKELIHOOD_RATIOS.keys

    /** 3.0 (#7, O2) — Claves usadas en los grupos de correlación. */
    internal val groupedKeys: Set<String> get() = ALL_GROUPED.toSet()

    fun calculate(
        failedHeuristics: List<String>,
        verificationStatus: String,
        neighborCount: Int = -1,
        trustScore: Int = -1
    ): Float {
        var posterior = PRIOR
        // Las heurísticas DÉBILES (sensibles al entorno) se amortiguan por densidad Y por
        // reputación: ambos factores son <=1.0 y se multiplican (un entorno disperso y/o una
        // celda muy probada reducen el ruido de las señales débiles). Nunca por debajo de neutro.
        val trust = trustFactor(trustScore)
        val df = densityFactor(neighborCount) * trust
        fun damped(h: String, base: Float): Float = when (h) {
            in ENV_SENSITIVE -> 1.0f + (base - 1.0f) * df
            in TRUST_ONLY_SENSITIVE -> 1.0f + (base - 1.0f) * trust
            else -> base
        }

        // Para cada grupo — aplicar solo la LR más potente.
        // Las heurísticas sensibles al entorno ven su LR suavizada hacia 1.0 (neutro) según
        // la densidad: 1 + (LR - 1) * df. En denso/desconocido df=1.0 -> sin cambios.
        ALL_GROUPS.forEach { group ->
            val maxLr = failedHeuristics
                .filter { it in group }
                .maxOfOrNull { h -> damped(h, LIKELIHOOD_RATIOS[h] ?: 1.0f) } ?: 1.0f

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
