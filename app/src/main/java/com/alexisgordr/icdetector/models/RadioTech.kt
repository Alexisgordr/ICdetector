package com.alexisgordr.icdetector.models

/**
 * Tecnología de radio de la celda, **tomada de la clase de `CellInfo` de la que se leyó**, no de la
 * cadena que enseña el icono del móvil.
 *
 * Esa distinción ya costó cara una vez: `CellData.networkType` viene de `TelephonyDisplayInfo`, que
 * describe lo que el teléfono decide pintar en la barra de estado, y en el historial de campo hay
 * 54 celdas que alternan entre "4G" y "5G" sin cambiar de identidad. Se usó esa cadena para elegir
 * el multiplicador del Timing Advance y el resultado fue geometría inventada. [TimingAdvanceUnit]
 * nació de ahí, y esto es la misma idea aplicada a la verificación externa.
 *
 * Aquí hace falta porque OpenCellID acepta un parámetro `radio` en la consulta y lo devuelve en la
 * respuesta: enviarlo hace la pregunta específica —sin él la API puede devolver el primer registro
 * que coincida con el resto de campos— y recibirlo permite comprobar que contestó por la misma
 * tecnología por la que se preguntó.
 */
enum class RadioTech {
    GSM,
    UMTS,
    LTE,
    NR,
    UNKNOWN;

    /**
     * Nombre del parámetro `radio` tal y como lo esperan OpenCellID y WiGLE, o null si no se sabe
     * de qué tecnología se trata — en cuyo caso no se envía el filtro, porque enviar un valor
     * inventado sería peor que no filtrar.
     */
    val apiName: String?
        get() = when (this) {
            GSM -> "GSM"
            UMTS -> "UMTS"
            LTE -> "LTE"
            NR -> "NR"
            UNKNOWN -> null
        }

    companion object {
        /**
         * Interpreta el `radio` que devuelve una API. Desconocido o ausente → [UNKNOWN], que en las
         * comprobaciones de identidad se trata como "no comprobable", nunca como "no coincide".
         */
        fun fromApi(value: String?): RadioTech = when (value?.trim()?.uppercase()) {
            "GSM" -> GSM
            "UMTS", "WCDMA" -> UMTS
            "LTE" -> LTE
            "NR", "5G" -> NR
            else -> UNKNOWN   // CDMA, NBIOT, vacío o cualquier otra cosa: no comprobable
        }
    }
}
