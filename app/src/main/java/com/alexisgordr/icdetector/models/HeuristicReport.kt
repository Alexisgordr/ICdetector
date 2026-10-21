package com.alexisgordr.icdetector.models

/**
 * Resultado observable de una heurística en el ciclo actual.
 *
 * NOT_EVALUATED no equivale a PASSED: significa que Android, el módem o el historial todavía no
 * han aportado los datos necesarios. Separar ambos estados evita mostrar un check verde para una
 * regla que realmente se abstuvo.
 */
enum class HeuristicStatus {
    PASSED,
    FAILED,
    NOT_EVALUATED;

    val passed: Boolean
        get() = this != FAILED
}

data class HeuristicReport(
    val isolatedCell: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val powerJump: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val mccConsistency: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val mncCount: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val tacDeviation: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val taDistance: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val ghostNeighbors: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val arfcnSanity: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val hardwareCiphering: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val pingPong: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val mobileCellId: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val latencyCorrelation: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val signalBaseline: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val bandDowngrade: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val rfStability: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val transitionCoherence: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    /** 3.1.0 — H17 (churn de celdas) está en sombra: siempre NOT_EVALUATED (ver core/CellChurn). */
    val cellChurn: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    /** 3.0 (corte de campaña) — Por qué H3 se abstuvo, si se abstuvo (ver [MccAbstention]). */
    val mccAbstention: MccAbstention? = null,
    /** 3.0 (corte de campaña) — La servidora es CSG (femtocelda): H1, H2, H5, H6 y H7 no se evalúan. */
    val csgCell: Boolean = false,
    /** 3.0 (corte de campaña) — H11 se abstuvo porque el fix GPS era demasiado viejo o de edad desconocida. */
    val mobileCellIdFixTooOld: Boolean = false,
    /** 3.1.0 (#46) — H11 se abstuvo porque el fix no tenía precisión conocida o pasaba de 100 m. */
    val mobileCellIdFixImprecise: Boolean = false
) {
    // Compatibilidad: una regla que se abstiene no debe activar una alarma.
    val isolatedCellPassed get() = isolatedCell.passed
    val powerJumpPassed get() = powerJump.passed
    val mccConsistencyPassed get() = mccConsistency.passed
    val mncCountPassed get() = mncCount.passed
    val tacDeviationPassed get() = tacDeviation.passed
    val taDistancePassed get() = taDistance.passed
    val ghostNeighborsPassed get() = ghostNeighbors.passed
    val arfcnSanityPassed get() = arfcnSanity.passed
    val hardwareCipheringPassed get() = hardwareCiphering.passed
    val hardwareCipheringAvailable get() = hardwareCiphering != HeuristicStatus.NOT_EVALUATED
    val pingPongPassed get() = pingPong.passed
    val mobileCellIdPassed get() = mobileCellId.passed
    val latencyCorrelationPassed get() = latencyCorrelation.passed
    val signalBaselinePassed get() = signalBaseline.passed
    val bandDowngradePassed get() = bandDowngrade.passed
    val rfStabilityPassed get() = rfStability.passed
    val transitionCoherencePassed get() = transitionCoherence.passed

    val evaluatedCount: Int
        get() = statuses.count { it != HeuristicStatus.NOT_EVALUATED }

    /**
     * Reglas de EVIDENCIA fallidas: lo que cuenta como "la celda tiene algo en contra" (confianza
     * local, aprendizaje de Stable-Site, resumen del panel).
     *
     * 3.0 (corte de campaña) — No cuenta H8 (aviso de calidad de dato) ni H9 (reservada), ni H12
     * (retirada en 3.1.0): no son evidencia ([NON_EVIDENCE_IDS], igual que HeuristicCatalog.isEvidence). Antes un H8 fallado
     * ponía la celda en cuarentena y frenaba Stable-Site aunque ya no restara puntos.
     */
    val failedCount: Int
        get() = byId.count { (id, status) -> id !in NON_EVIDENCE_IDS && status == HeuristicStatus.FAILED }

    /** 3.0 (corte de campaña) — Avisos de calidad de dato fallidos (H8), aparte de [failedCount]. */
    val dataQualityFailedCount: Int
        get() = if (arfcnSanity == HeuristicStatus.FAILED) 1 else 0

    val totalCount: Int
        get() = statuses.size

    private val byId: List<Pair<String, HeuristicStatus>>
        get() = listOf(
            "H1" to isolatedCell, "H2" to powerJump, "H3" to mccConsistency,
            "H4" to mncCount, "H5" to tacDeviation, "H6" to taDistance,
            "H7" to ghostNeighbors, "H8" to arfcnSanity, "H9" to hardwareCiphering,
            "H10" to pingPong, "H11" to mobileCellId, "H12" to latencyCorrelation,
            "H13" to signalBaseline, "H14" to bandDowngrade, "H15" to rfStability,
            "H16" to transitionCoherence, "H17" to cellChurn
        )

    /**
     * Estado de cada regla, `H1=PASSED;H2=FAILED;...`. 3.0 (corte de campaña): una regla que se
     * abstuvo con motivo lo lleva entre paréntesis, igual que en el CSV (`H3=NOT_EVALUATED(roaming)`).
     */
    fun snapshot(): String = byId.joinToString(";") { (id, status) ->
        val code = if (status == HeuristicStatus.NOT_EVALUATED) abstentionCode(id) else null
        "$id=${status.name}" + (code?.let { "($it)" } ?: "")
    }

    /**
     * 3.0 (#29) — Reglas que se abstuvieron en este ciclo, separadas por `;` (`H1;H6;H9`), o
     * [NONE_NOT_EVALUATED] si todas se evaluaron. Se guarda en cada fila del historial para poder
     * medir cuánto tiempo fue evaluable cada regla. Nunca coincide con una regla que falló.
     *
     * 3.0 (corte de campaña) — Una regla puede llevar entre paréntesis el motivo de su abstención, p. ej.
     * `H1(csg);H3(roaming);H6`, para que el CSV distinga casos sin reconstruirlos: H3 lo toma de
     * [MccAbstention], y H1/H2/H5/H6/H7 llevan `(csg)` en una celda CSG ([CSG_RULES]).
     */
    fun notEvaluatedIds(): String =
        byId.filter { it.second == HeuristicStatus.NOT_EVALUATED }
            .joinToString(";") { (id, _) -> abstentionCode(id)?.let { "$id($it)" } ?: id }
            .ifEmpty { NONE_NOT_EVALUATED }

    /** Código del motivo por el que [id] se abstuvo, o null si no se guarda ninguno. */
    fun abstentionCode(id: String): String? = when {
        id == "H3" -> mccAbstention?.code
        csgCell && id in CSG_RULES -> CSG_ABSTENTION_CODE
        id == "H11" && mobileCellIdFixTooOld -> H11_FIX_ABSTENTION_CODE
        id == "H11" && mobileCellIdFixImprecise -> H11_FIX_IMPRECISE_CODE
        id == "H17" -> H17_SHADOW_CODE
        else -> null
    }

    /** Texto del motivo para el terminal (castellano, como el resto), o null. */
    fun abstentionText(id: String): String? = when {
        id == "H3" -> mccAbstention?.text
        csgCell && id in CSG_RULES -> CSG_ABSTENTION_TEXT
        id == "H11" && mobileCellIdFixTooOld -> H11_FIX_ABSTENTION_TEXT
        id == "H11" && mobileCellIdFixImprecise -> H11_FIX_IMPRECISE_TEXT
        id == "H17" -> H17_SHADOW_TEXT
        else -> null
    }

    companion object {
        /** Valor explícito de "todas evaluadas", distinto del vacío de las filas anteriores a 3.0. */
        const val NONE_NOT_EVALUATED = "NONE"

        /**
         * 3.0 (corte de campaña) — Reglas que no son evidencia: H8 (calidad de dato) y H9 (reservada).
         * 3.1.0 — Y H12 (retirada) y H17 (en sombra). Debe coincidir con HeuristicCatalog.isEvidence;
         * HeuristicCatalogTest lo comprueba.
         */
        val NON_EVIDENCE_IDS = setOf("H8", "H9", "H12", "H17")

        /** 3.0 (corte de campaña) — Reglas que no se evalúan en una celda CSG. */
        val CSG_RULES = setOf("H1", "H2", "H5", "H6", "H7")
        const val CSG_ABSTENTION_CODE = "csg"
        const val CSG_ABSTENTION_TEXT = "celda CSG (femtocelda)"

        /** 3.0 (corte de campaña) — H11 sin fix reciente (ver core/MobileCellIdFixAge). */
        const val H11_FIX_ABSTENTION_CODE = "fix_viejo"
        const val H11_FIX_ABSTENTION_TEXT = "fix GPS de más de 60 s o sin edad conocida"

        /** 3.1.0 (#46) — H11 con un fix sin precisión conocida o de más de 100 m (ver core/MobileCellIdFixAge). */
        const val H11_FIX_IMPRECISE_CODE = "fix_impreciso"
        const val H11_FIX_IMPRECISE_TEXT = "fix GPS sin precisión conocida o de más de 100 m"

        /** 3.1.0 — H17 en sombra: se calcula y se guarda, pero no se evalúa (ver core/CellChurn). */
        const val H17_SHADOW_CODE = "sombra"
        const val H17_SHADOW_TEXT = "en sombra hasta calibrarla con datos de campaña"
    }

    private val statuses: List<HeuristicStatus>
        get() = listOf(
            isolatedCell, powerJump, mccConsistency, mncCount, tacDeviation,
            taDistance, ghostNeighbors, arfcnSanity, hardwareCiphering, pingPong,
            mobileCellId, latencyCorrelation, signalBaseline, bandDowngrade, rfStability,
            transitionCoherence, cellChurn
        )
}
