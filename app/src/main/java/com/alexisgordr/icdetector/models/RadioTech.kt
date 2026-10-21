package com.alexisgordr.icdetector.models

/**
 * Tecnología de radio de la celda, **tomada de la clase de `CellInfo` de la que se leyó**, no de la
 * cadena que enseña el icono del móvil.
 *
 * Esa distinción ya costó cara una vez: `CellData.networkType` viene de `TelephonyDisplayInfo`, que
 * describe lo que el teléfono decide pintar en la barra de estado, y en el historial de campo hay
 * 54 celdas que alternan entre "4G" y "5G" sin cambiar de identidad. Se usó esa cadena para elegir
 * el multiplicador del Timing Advance y el resultado fue geometría inventada. [TimingAdvanceUnit]
 * nació de ahí, y esto es la misma idea aplicada a la identidad de la celda ([cellIdentityKey]).
 */
enum class RadioTech {
    GSM,
    UMTS,
    LTE,
    NR,
    UNKNOWN
}
