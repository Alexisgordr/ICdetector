package com.alexisgordr.icdetector.service

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.AccessNetworkConstants
import android.telephony.CellInfo
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
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
    private var securityCallback: TelephonyCallback? = null
    private var serviceStateCallback: TelephonyCallback? = null
    private var lastDisplayInfo: TelephonyDisplayInfo? = null
    private var lastRegisteredCallbackAt = 0L
    private var retryCount = 0

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !hasPhonePermission()) return
        registerCellCallback()
        registerDisplayCallback()
        registerServiceStateCallback()
    }

    fun requestFreshCellInfo() {
        recoverCallbacksIfStale()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) start()
        if (!hasLocationPermission()) return
        try {
            manager.requestCellInfoUpdate(
                context.mainExecutor,
                object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        deliver(cellInfo, true, false)
                    }

                    override fun onError(errorCode: Int, detail: Throwable?) {
                        if (!hasLocationPermission()) return
                        try {
                            deliver(manager.allCellInfo, false, false)
                        } catch (_: SecurityException) {}
                    }
                }
            )
        } catch (_: SecurityException) {}
    }

    fun operatorMcc(): String = manager.networkOperator
        ?.takeIf { it.length >= 3 }?.substring(0, 3) ?: "N/A"

    fun operatorMnc(): String = manager.networkOperator
        ?.takeIf { it.length > 3 }?.substring(3) ?: "N/A"

    @SuppressLint("MissingPermission")
    fun displayNetworkType(): String {
        val info = lastDisplayInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && info != null) {
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        listOf(cellCallback, displayCallback, securityCallback, serviceStateCallback).forEach { callback ->
            callback?.let { try { manager.unregisterTelephonyCallback(it) } catch (_: Exception) {} }
        }
        cellCallback = null
        displayCallback = null
        securityCallback = null
        serviceStateCallback = null
    }

    /**
     * v2.10.4 — Lectura activa del ServiceState, para Android 10 (sin callbacks) y como red de
     * seguridad si un callback se pierde. Devuelve null si Android no la permite.
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || serviceStateCallback != null) return
        try {
            val callback = object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
                override fun onServiceStateChanged(serviceState: ServiceState) {
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
        val registration = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { registrationOf(serviceState) } catch (_: Exception) { RegistrationDomains() }
        } else RegistrationDomains()
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

    @RequiresApi(Build.VERSION_CODES.R)
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || cellCallback != null) return
        try {
            val callback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                    if (cellInfo.isNotEmpty()) {
                        lastRegisteredCallbackAt = System.currentTimeMillis()
                        retryCount = 0
                    }
                    deliver(cellInfo, true, true)
                }
            }
            manager.registerTelephonyCallback(context.mainExecutor, callback)
            cellCallback = callback
            if (Build.VERSION.SDK_INT >= 34) registerSecurityPlaceholder()
        } catch (_: SecurityException) {}
    }

    private fun registerDisplayCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || displayCallback != null) return
        try {
            val callback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
                    lastRegisteredCallbackAt = System.currentTimeMillis()
                    lastDisplayInfo = displayInfo
                    requestFreshCellInfo()
                }
            }
            manager.registerTelephonyCallback(context.mainExecutor, callback)
            displayCallback = callback
        } catch (_: SecurityException) {}
    }

    private fun recoverCallbacksIfStale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (System.currentTimeMillis() - lastRegisteredCallbackAt <= CALLBACK_TIMEOUT_MS) return
        retryCount++
        if (retryCount < MAX_RETRIES) return
        retryCount = 0
        destroy()
        manager = context.getSystemService(TelephonyManager::class.java)
        start()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerSecurityPlaceholder() {
        if (securityCallback != null) return
        try {
            securityCallback = SecurityPlaceholder().also {
                manager.registerTelephonyCallback(context.mainExecutor, it)
            }
            log("Callback de telefonía registrado (detección de cifrado nulo/IMSI: pendiente API Android 16).")
        } catch (error: Exception) {
            securityCallback = null
            Log.e("MiniIC", "No se pudo registrar el callback de seguridad: ${error.message}")
        }
    }

    private fun hasPhonePermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    @RequiresApi(Build.VERSION_CODES.S)
    private class SecurityPlaceholder : TelephonyCallback(), TelephonyCallback.CellInfoListener {
        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) = Unit
    }

    private companion object {
        const val CALLBACK_TIMEOUT_MS = 30_000L
        const val MAX_RETRIES = 4
    }
}
