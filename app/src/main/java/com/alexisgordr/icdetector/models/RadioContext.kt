package com.alexisgordr.icdetector.models

/**
 * v2.10.4 — Contexto de radio que Android expone sin root y que hasta ahora no se recogía.
 *
 * Todo lo de este fichero es RECOLECCIÓN: ninguna heurística lo lee y no cambia ningún score.
 * Existe para que, cuando algo salga raro, el historial y el análisis forense puedan responder
 * "¿qué más estaba pasando en la radio en ese momento?" sin tener que suponerlo.
 *
 * El fichero es Kotlin puro (sin clases de Android) para poder probar los mapeos en la JVM.
 * CellParser y TelephonyCollectionController traducen los enteros de la API a estos tipos.
 */

/**
 * Papel de una celda en la conexión, según `CellInfo.getCellConnectionStatus()` (API 28).
 *
 * Con AGREGACIÓN DE PORTADORAS el módem puede publicar varias entradas registradas: la portadora
 * primaria (la que lleva el control de la conexión) y una o más secundarias (solo datos). En
 * 5G NSA la pata NR suele aparecer como secundaria. Saber cuál es cuál evita tener que deducirlo.
 */
enum class CellConnectionState {
    PRIMARY_SERVING,
    SECONDARY_SERVING,
    NONE,
    UNKNOWN;

    companion object {
        // Valores públicos de android.telephony.CellInfo. Se repiten aquí para que el mapeo sea
        // probable en la JVM; hay un test que fija cada uno.
        const val ANDROID_CONNECTION_NONE = 0
        const val ANDROID_CONNECTION_PRIMARY_SERVING = 1
        const val ANDROID_CONNECTION_SECONDARY_SERVING = 2

        fun fromAndroid(value: Int): CellConnectionState = when (value) {
            ANDROID_CONNECTION_PRIMARY_SERVING -> PRIMARY_SERVING
            ANDROID_CONNECTION_SECONDARY_SERVING -> SECONDARY_SERVING
            ANDROID_CONNECTION_NONE -> NONE
            else -> UNKNOWN // CONNECTION_UNKNOWN (Int.MAX_VALUE) o cualquier valor no documentado
        }
    }
}

/**
 * Información de grupo cerrado de abonados (CSG), `getClosedSubscriberGroupInfo()` (API 30).
 *
 * Una celda con [indicator] = true solo admite a los abonados de su grupo: es lo típico de una
 * femtocelda doméstica o de empresa. No es una anomalía por sí sola; se registra como contexto,
 * porque una femtocelda es justo el tipo de equipo pequeño que alguien podría manipular.
 */
data class CsgInfo(
    val indicator: Boolean,
    val identity: Int?,
    val homeNodebName: String?
)

/** Una portadora secundaria o entrada registrada extra vista junto a la celda analizada. */
data class SecondaryCarrier(
    val radio: RadioTech,
    val arfcn: Int?,
    val pci: Int?,
    val bandwidthKhz: Int?,
    val connectionState: CellConnectionState
) {
    /** Forma compacta para CSV y terminal: `LTE:6400:200` (valores ausentes como `?`). */
    val compact: String
        get() = "${radio.name}:${arfcn ?: "?"}:${pci ?: "?"}"
}

/** Serialización estable de la lista de portadoras secundarias para una columna de texto. */
fun List<SecondaryCarrier>.toCompactString(): String = joinToString(";") { it.compact }

/**
 * Estado general del servicio según `ServiceState.getState()`.
 *
 * [EMERGENCY_ONLY] y [OUT_OF_SERVICE] son los estados a vigilar: quedarse "solo emergencias" o
 * perder el servicio justo después de un cambio de celda es un síntoma clásico, en la
 * literatura, de haber pasado por una estación falsa. Aquí solo se registra; no puntúa.
 */
enum class ServiceRegistrationState {
    IN_SERVICE,
    OUT_OF_SERVICE,
    EMERGENCY_ONLY,
    POWER_OFF,
    UNKNOWN;

    companion object {
        // Valores públicos de android.telephony.ServiceState.
        const val ANDROID_STATE_IN_SERVICE = 0
        const val ANDROID_STATE_OUT_OF_SERVICE = 1
        const val ANDROID_STATE_EMERGENCY_ONLY = 2
        const val ANDROID_STATE_POWER_OFF = 3

        fun fromAndroid(value: Int): ServiceRegistrationState = when (value) {
            ANDROID_STATE_IN_SERVICE -> IN_SERVICE
            ANDROID_STATE_OUT_OF_SERVICE -> OUT_OF_SERVICE
            ANDROID_STATE_EMERGENCY_ONLY -> EMERGENCY_ONLY
            ANDROID_STATE_POWER_OFF -> POWER_OFF
            else -> UNKNOWN
        }
    }
}

/** Origen de una lectura de ServiceState. */
enum class ServiceStateSource { CALLBACK, POLL }

/**
 * Instantánea del estado de servicio. Los campos que Android puede no entregar son nulos: un
 * nulo significa "no disponible", nunca "falso".
 */
data class ServiceStateSnapshot(
    val timestampMs: Long,
    val state: ServiceRegistrationState,
    /** Dominio de paquetes (datos) registrado, según NetworkRegistrationInfo (API 30). */
    val dataRegistered: Boolean? = null,
    /** Dominio de circuitos (voz) registrado, según NetworkRegistrationInfo (API 30). */
    val voiceRegistered: Boolean? = null,
    /** Buscando red en algún dominio, según NetworkRegistrationInfo (API 30). */
    val searching: Boolean? = null,
    val roaming: Boolean = false,
    /** PLMN que anuncia la red (MCC+MNC). */
    val operatorNumeric: String? = null,
    /** Nombre largo que anuncia la red. */
    val operatorAlphaLong: String? = null,
    /** PLMN de la SIM, para comparar con [operatorNumeric] al analizar. */
    val simOperator: String? = null,
    /** Selección manual de operador activada por el usuario. */
    val manualSelection: Boolean = false,
    val channelNumber: Int? = null,
    /** Anchos de banda de las portadoras activas, en kHz (`ServiceState.getCellBandwidths()`). */
    val cellBandwidthsKhz: List<Int> = emptyList(),
    /** Tecnología de acceso del dominio de datos, como nombre (LTE, NR, UMTS...). */
    val dataNetworkType: String? = null,
    /** Tecnología de acceso del dominio de voz, como nombre. */
    val voiceNetworkType: String? = null,
    val source: ServiceStateSource = ServiceStateSource.POLL
) {
    /** ¿La red anunciada es distinta de la de tu SIM sin que Android diga que estás en roaming? */
    val operatorDiffersFromSim: Boolean
        get() = !roaming && !operatorNumeric.isNullOrBlank() && !simOperator.isNullOrBlank() &&
            operatorNumeric != simOperator

    /**
     * Firma para decidir si ha habido un CAMBIO que merezca un evento. Deja fuera a propósito
     * el canal y los anchos de banda: con agregación de portadoras cambian a menudo y llenarían
     * el registro de ruido. Esos dos se guardan igualmente en cada evento que sí se registra.
     */
    val changeSignature: String
        get() = listOf(
            state.name, dataRegistered, voiceRegistered, searching, roaming,
            operatorNumeric, simOperator, manualSelection, dataNetworkType, voiceNetworkType
        ).joinToString("|")
}

/**
 * Traducción de `TelephonyManager.NETWORK_TYPE_*` a un nombre legible. Los valores son los
 * públicos y estables de la API; se repiten aquí para poder probarlo sin Android.
 */
object NetworkTypeNames {
    fun name(networkType: Int): String? = when (networkType) {
        0 -> null // NETWORK_TYPE_UNKNOWN
        1 -> "GPRS"
        2 -> "EDGE"
        3 -> "UMTS"
        4 -> "CDMA"
        5 -> "EVDO_0"
        6 -> "EVDO_A"
        7 -> "1xRTT"
        8 -> "HSDPA"
        9 -> "HSUPA"
        10 -> "HSPA"
        11 -> "IDEN"
        12 -> "EVDO_B"
        13 -> "LTE"
        14 -> "EHRPD"
        15 -> "HSPAP"
        16 -> "GSM"
        17 -> "TD_SCDMA"
        18 -> "IWLAN"
        20 -> "NR"
        else -> "TYPE_$networkType"
    }
}
