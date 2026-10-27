package com.alexisgordr.icdetector.service

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.AccessNetworkConstants
import android.telephony.CellInfo
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.alexisgordr.icdetector.models.NetworkTypeNames
import com.alexisgordr.icdetector.models.ServiceRegistrationState
import com.alexisgordr.icdetector.models.ServiceStateSnapshot
import com.alexisgordr.icdetector.models.ServiceStateSource

/** Owns telephony callbacks, watchdog recovery, explicit refresh and cached fallback delivery. */
internal class TelephonyCollectionController(
    private val context: Context,
    private val log: (String) -> Unit,
    private val deliver: (List<CellInfo>?, Boolean, Boolean) -> Unit,
    /** v2.10.4 — Recibe cada lectura de ServiceState (callback o sondeo). Solo recolección. */
    private val serviceStateSink: (ServiceStateSnapshot) -> Unit = {}
) {
    private var manager = context.getSystemService(TelephonyManager::class.java)
    private var cellCallback: TelephonyCallback? = null
    private var displayCallback: TelephonyCallback? = null
    private var serviceStateCallback: TelephonyCallback? = null
    private var lastDisplayInfo: TelephonyDisplayInfo? = null
    // 3.0 — Ver CallbackWatchdog: cuenta también las respuestas a requestCellInfoUpdate.
    private val watchdog = com.alexisgordr.icdetector.core.CallbackWatchdog { android.os.SystemClock.elapsedRealtime() }
    // 3.1.6 (#49) — Aviso único en el terminal cuando el sistema entrega una lista de celdas nula.
    private val nullCellsNotice = com.alexisgordr.icdetector.core.NullCellDeliveryNotice()

    fun start() {
        if (!hasPhonePermission()) return
        registerCellCallback()
        registerDisplayCallback()
        registerServiceStateCallback()
    }

    fun requestFreshCellInfo() {
        recoverCallbacksIfStale()
        start()
        if (!hasLocationPermission()) return
        try {
            manager.requestCellInfoUpdate(
                context.mainExecutor,
                // 3.1.6 (#49) — Adaptador en Java: la lista puede llegar null (ver
                // NullTolerantTelephonyCallbacks y CellDelivery).
                NullTolerantTelephonyCallbacks.CellInfoRequest(
                    { cellInfo -> handleCellDelivery(cellInfo, fresh = true, registered = false) },
                    { _, _ ->
                        if (hasLocationPermission()) {
                            // 3.1.6 (#49) — allCellInfo puede lanzar algo más que SecurityException
                            // (IllegalStateException en algunos dispositivos) o devolver null.
                            try {
                                handleCellDelivery(manager.allCellInfo, fresh = false, registered = false)
                            } catch (_: Exception) {}
                        }
                    }
                )
            )
        } catch (_: SecurityException) {}
    }

    /**
     * 3.1.6 (#49) — Una entrega de celdas, quizá nula. Null sigue el flujo normal como lista vacía
     * (sin señal), no cuenta como entrega para el watchdog y deja una línea en el terminal (una sola
     * vez hasta la siguiente entrega no nula). Una lista con celdas demuestra que el módem contesta.
     */
    private fun handleCellDelivery(raw: List<CellInfo>?, fresh: Boolean, registered: Boolean) {
        if (com.alexisgordr.icdetector.core.CellDelivery.countsForWatchdog(raw)) watchdog.onDelivery()
        nullCellsNotice.onDelivery(raw == null)?.let(log)
        deliver(com.alexisgordr.icdetector.core.CellDelivery.orEmpty(raw), fresh, registered)
    }

    fun operatorMcc(): String = manager.networkOperator
        ?.takeIf { it.length >= 3 }?.substring(0, 3) ?: "N/A"

    fun operatorMnc(): String = manager.networkOperator
        ?.takeIf { it.length > 3 }?.substring(3) ?: "N/A"

    @SuppressLint("MissingPermission")
    fun displayNetworkType(): String {
        val info = lastDisplayInfo
        if (info != null) {
            val override = info.overrideNetworkType
            if (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
            ) return "5G NR (NSA)"
        }
        return when (manager.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G HSPA+"
            TelephonyManager.NETWORK_TYPE_HSPA -> "3G HSPA"
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G UMTS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
            TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
            else -> "DESCONOCIDA"
        }
    }

    fun destroy() {
        listOf(cellCallback, displayCallback, serviceStateCallback).forEach { callback ->
            callback?.let { try { manager.unregisterTelephonyCallback(it) } catch (_: Exception) {} }
        }
        cellCallback = null
        displayCallback = null
        serviceStateCallback = null
    }

    /**
     * v2.10.4 — Lectura activa del ServiceState, como red de seguridad si un callback se pierde
     * (3.0: la app exige Android 12, así que los callbacks existen siempre). Devuelve null si Android no la permite.
     */
    @SuppressLint("MissingPermission")
    fun pollServiceState(): ServiceStateSnapshot? {
        if (!hasPhonePermission()) return null
        return try {
            manager.serviceState?.let { snapshotOf(it, ServiceStateSource.POLL) }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun registerServiceStateCallback() {
        if (serviceStateCallback != null) return
        try {
            // 3.1.6 (#49) — Adaptador en Java, por la misma razón que las celdas: un null se ignora.
            val callback = NullTolerantTelephonyCallbacks.ServiceStateChanged { serviceState ->
                if (serviceState != null) {
                    try {
                        serviceStateSink(snapshotOf(serviceState, ServiceStateSource.CALLBACK))
                    } catch (_: Exception) {}
                }
            }
            manager.registerTelephonyCallback(context.mainExecutor, callback)
            serviceStateCallback = callback
        } catch (_: SecurityException) {
        } catch (_: Exception) {}
    }

    /**
     * Traduce un ServiceState a la instantánea del modelo. Cada campo va en su propio
     * runCatching: un fabricante que rompa un getter deja ese campo vacío, no la lectura entera.
     */
    // Callers are gated by hasPhonePermission(); individual vendor getters remain best-effort.
    @SuppressLint("MissingPermission")
    private fun snapshotOf(serviceState: ServiceState, source: ServiceStateSource): ServiceStateSnapshot {
        val registration = try { registrationOf(serviceState) } catch (_: Exception) { RegistrationDomains() }
        return ServiceStateSnapshot(
            timestampMs = System.currentTimeMillis(),
            state = ServiceRegistrationState.fromAndroid(runCatching { serviceState.state }.getOrDefault(-1)),
            dataRegistered = registration.dataRegistered,
            voiceRegistered = registration.voiceRegistered,
            searching = registration.searching,
            roaming = runCatching { serviceState.roaming }.getOrDefault(false),
            operatorNumeric = runCatching { serviceState.operatorNumeric }.getOrNull()?.takeIf { it.isNotBlank() },
            operatorAlphaLong = runCatching { serviceState.operatorAlphaLong }.getOrNull()?.takeIf { it.isNotBlank() },
            simOperator = runCatching { manager.simOperator }.getOrNull()?.takeIf { it.isNotBlank() },
            manualSelection = runCatching { serviceState.getIsManualSelection() }.getOrDefault(false),
            channelNumber = runCatching { serviceState.channelNumber }.getOrNull()
                ?.takeIf { it in 0 until Int.MAX_VALUE },
            cellBandwidthsKhz = runCatching { serviceState.cellBandwidths.toList() }.getOrDefault(emptyList())
                .filter { it in 1 until Int.MAX_VALUE },
            dataNetworkType = registration.dataNetwork,
            voiceNetworkType = registration.voiceNetwork,
            source = source
        )
    }

    /** Resumen por dominio (datos/voz) de NetworkRegistrationInfo. Nulo = no disponible. */
    private data class RegistrationDomains(
        val dataRegistered: Boolean? = null,
        val voiceRegistered: Boolean? = null,
        val searching: Boolean? = null,
        val dataNetwork: String? = null,
        val voiceNetwork: String? = null
    )

    @Suppress("DEPRECATION") // El SDK actual aún no expone un sustituto público equivalente.
    private fun registrationOf(serviceState: ServiceState): RegistrationDomains {
        var dataRegistered: Boolean? = null
        var voiceRegistered: Boolean? = null
        var searching = false
        var dataNetwork: String? = null
        var voiceNetwork: String? = null
        for (info in serviceState.networkRegistrationInfoList) {
            if (info.transportType != AccessNetworkConstants.TRANSPORT_TYPE_WWAN) continue
            val registered = info.isRegistered
            val tech = NetworkTypeNames.name(info.accessNetworkTechnology)
            if (info.isSearching) searching = true
            if ((info.domain and NetworkRegistrationInfo.DOMAIN_PS) != 0) {
                dataRegistered = (dataRegistered ?: false) || registered
                if (tech != null) dataNetwork = tech
            }
            if ((info.domain and NetworkRegistrationInfo.DOMAIN_CS) != 0) {
                voiceRegistered = (voiceRegistered ?: false) || registered
                if (tech != null) voiceNetwork = tech
            }
        }
        return RegistrationDomains(dataRegistered, voiceRegistered, searching, dataNetwork, voiceNetwork)
    }

    private fun registerCellCallback() {
        if (cellCallback != null) return
        try {
            // 3.1.6 (#49) — Adaptador en Java: algunos fabricantes entregan null con la ubicación apagada.
            val callback = NullTolerantTelephonyCallbacks.CellInfoChanged { cellInfo ->
                handleCellDelivery(cellInfo, fresh = true, registered = true)
            }
            manager.registerTelephonyCallback(context.mainExecutor, callback)
            cellCallback = callback
        } catch (_: SecurityException) {}
    }

    private fun registerDisplayCallback() {
        if (displayCallback != null) return
        try {
            // 3.1.6 (#49) — Adaptador en Java: un null no cuenta como entrega ni borra el último valor.
            val callback = NullTolerantTelephonyCallbacks.DisplayInfoChanged { displayInfo ->
                if (displayInfo != null) {
                    watchdog.onDelivery()
                    lastDisplayInfo = displayInfo
                }
                requestFreshCellInfo()
            }
            manager.registerTelephonyCallback(context.mainExecutor, callback)
            displayCallback = callback
        } catch (_: SecurityException) {}
    }

    private fun recoverCallbacksIfStale() {
        val silentMs = watchdog.onRefreshRequested() ?: return
        // 3.0 — Cada reinicio queda en el terminal: antes no dejaba rastro.
        log("Watchdog: callbacks de telefonía reiniciados; ${silentMs / 1_000} s sin entregas de celdas.")
        destroy()
        manager = context.getSystemService(TelephonyManager::class.java)
        start()
    }

    private fun hasPhonePermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}
