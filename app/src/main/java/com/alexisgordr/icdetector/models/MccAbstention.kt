package com.alexisgordr.icdetector.models

/**
 * 3.0.0-beta5 — Por qué H3 (consistencia MCC) se abstuvo en una observación.
 *
 * [code] se guarda en `not_evaluated_heuristics` junto al id (`H3(roaming)`), así que el CSV dice
 * qué caso fue sin tener que reconstruirlo. Es ASCII y estable: cambiarlo cambia lo que leen las
 * exportaciones. [text] es la línea del terminal, en castellano como el resto de motivos; la UI
 * la traduce al mostrarla.
 */
enum class MccAbstention(val code: String, val text: String) {
    /** No hay ServiceState: sin él no se sabe si Android declara roaming. */
    NO_SERVICE_STATE("sin_servicestate", "sin ServiceState, no se sabe si hay roaming"),
    /** Sin SIM, o con un operador de SIM vacío o ilegible. */
    NO_SIM("sin_sim", "sin MCC de la SIM"),
    /** Ni la celda ni el ServiceState informan del MCC de la servidora. */
    NO_SERVING_MCC("sin_mcc_servidora", "sin MCC de la servidora"),
    /** MCC distinto del de la SIM con roaming declarado por Android: es lo esperado en el extranjero. */
    ROAMING("roaming", "MCC distinto del de la SIM con roaming declarado"),
    /** MCC distinto del de la SIM sin roaming, pero hay vecinas con ese mismo MCC. */
    BORDER("frontera", "PLMN extranjero con vecinas coherentes (frontera)");
}
