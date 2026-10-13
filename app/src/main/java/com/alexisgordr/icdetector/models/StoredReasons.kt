package com.alexisgordr.icdetector.models

/**
 * 3.0 — Contrato de los motivos guardados en `history.failed_heuristics`, compartido por el motor
 * (que los escribe) y el almacenamiento (que los consulta). Las cadenas son EXACTAMENTE las de
 * siempre y se guardan en castellano: la interfaz las traduce al mostrarlas, nunca en la base.
 * Cambiar una de estas cadenas cambia qué filas históricas selecciona cada consulta.
 *
 * Otros marcadores del contrato que ya eran constantes: [SUBTHRESHOLD_PREFIX] (sub-umbral),
 * [SITE_UNVERIFIED_PREFIX], [LOCAL_TRUST_RECONFIGURATION_REASON] y
 * [LOCAL_TRUST_RECONFIGURATION_HISTORY_LIKE] (cuarentena de reconfiguración).
 */
object StoredReasons {
    /** Fila limpia: se guarda cuando la observación no tiene motivo. Vacío o null también cuentan. */
    const val CLEAN = "OK"

    /** Separador de varios motivos en una misma fila. */
    const val SEPARATOR = " | "

    /** Comienzo del motivo de H15 (estabilidad de identidad RF). */
    const val H15_PREFIX = "Identidad RF inestable:"

    /**
     * 3.0 (L4) — Comienzo del motivo de H13 por potencia. El motor añade el exceso medido, p. ej.
     * `Potencia anómala vs historial (+20dB)`. Texto histórico: no se traduce en la base.
     */
    const val H13_POWER_PREFIX = "Potencia anómala vs historial"

    /** 3.0 (L4) — Comienzo del motivo de H13 por huella RF (RSRQ/SINR). */
    const val H13_RF_PREFIX = "Huella RF incoherente con el historial"

    /** Motivo completo de H13 por huella RF, tal como se guarda desde siempre. */
    const val H13_RF_REASON = "$H13_RF_PREFIX (RSRQ/SINR muy desviados)"

    /** Motivo de H13 por potencia con su exceso en dB (formato histórico). */
    fun h13PowerReason(excessDb: Int): String = "$H13_POWER_PREFIX (+${excessDb}dB)"

    /** Motivo completo que escribe H15. */
    const val H15_REASON = "$H15_PREFIX misma Cell ID alternando PCI en la misma portadora (posible clon)"

    /**
     * 3.0 — Añade [addition] a los motivos [existing] con [SEPARATOR], sin borrar ninguno: un motivo
     * vacío no se añade y uno que ya está (comparado parte a parte) no se repite. Null si no queda
     * ningún motivo.
     */
    fun append(existing: String?, addition: String?): String? {
        val parts = existing?.split(SEPARATOR)?.map(String::trim)?.filter(String::isNotEmpty).orEmpty().toMutableList()
        val extra = addition?.trim().orEmpty()
        if (extra.isNotEmpty() && extra !in parts) parts += extra
        return parts.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    }

    /** Prefijo de una alarma aún en confirmación temporal, p. ej. `[2/3 ciclos confirmando]`. */
    fun confirmingPrefix(streak: Int, required: Int): String = "[$streak/$required ciclos confirmando]"

    /** Patrón LIKE que reconoce [confirmingPrefix] sea cual sea la racha. */
    const val CONFIRMING_LIKE = "[%ciclos confirmando]"

    /** SQL: la fila no tiene motivo (null, vacío tras TRIM, u [CLEAN]). */
    fun cleanSql(column: String): String = "($column IS NULL OR TRIM($column)='' OR $column='$CLEAN')"

    /** Lo mismo en Kotlin para una fila ya leída (misma semántica que [cleanSql]). */
    fun isClean(reason: String?): Boolean = reason == null || reason.isBlank() || reason == CLEAN

    /** Patrones LIKE de una fila cuyo único motivo es H15: directo, sub-umbral o en confirmación. */
    val H15_ONLY_LIKE: Array<String> = arrayOf(
        "$H15_PREFIX%",
        "$SUBTHRESHOLD_PREFIX $H15_PREFIX%",
        "$CONFIRMING_LIKE $H15_PREFIX%"
    )

    /**
     * SQL de las filas válidas para el historial de H15: limpias con score ≥ ?, o con H15 como ÚNICO
     * motivo (ninguna otra regla, por eso se excluye el separador). Parámetros: el score mínimo y
     * después los tres [H15_ONLY_LIKE].
     */
    fun h15EligibleSql(scoreColumn: String, reasonColumn: String): String =
        "(($scoreColumn >= ? AND ${cleanSql(reasonColumn)}) " +
            "OR (($reasonColumn LIKE ? OR $reasonColumn LIKE ? OR $reasonColumn LIKE ?) " +
            "AND $reasonColumn NOT LIKE '%$SEPARATOR%'))"
}
