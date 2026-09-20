package com.alexisgordr.icdetector.service

import android.location.Location
import com.alexisgordr.icdetector.core.VerificationDecision
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.VerificationStatus
import com.alexisgordr.icdetector.models.identityKey
import com.alexisgordr.icdetector.network.OpenCellIdClient
import com.alexisgordr.icdetector.storage.CellDbHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/** Owns OpenCellID lookup, retry policy, persistence and result publication. */
internal class ExternalVerificationController(
    private val scope: CoroutineScope,
    private val db: CellDbHelper,
    private val client: OkHttpClient,
    private val coordinateValidator: ApiCoordinateValidator,
    private val apiKey: () -> String,
    private val proxyEnabled: () -> Boolean,
    private val currentLocation: () -> Location?,
    private val cache: ConcurrentHashMap<String, VerificationStatus>,
    private val errorTimes: ConcurrentHashMap<String, Long>,
    private val notFoundTimes: ConcurrentHashMap<String, Long>,
    private val rejectedTimes: ConcurrentHashMap<String, Long>,
    private val log: (String) -> Unit,
    private val publish: (CellData, VerificationStatus) -> Unit,
    private val cacheApiLocation: (String, Double, Double) -> Unit,
    private val onVerified: (CellData) -> Unit,
    private val requestFreshCellInfo: () -> Unit
) {
    private var loggedMissingCredentials = false

    fun verify(cell: CellData) {
        if (!isQueryable(cell)) return
        val key = cell.identityKey
        if (!mayAttempt(key, cache[key])) return

        val token = apiKey().trim()
        if (token.isBlank() || token.startsWith("pk.YOUR")) {
            if (!loggedMissingCredentials) {
                log("⚠ Sin credenciales configuradas. Ve a Ajustes para añadir OpenCellID.")
                loggedMissingCredentials = true
            }
            return
        }
        loggedMissingCredentials = false

        synchronized(cache) {
            if (!mayAttempt(key, cache[key])) return
            cache[key] = VerificationStatus.PENDING
        }

        scope.launch(Dispatchers.IO) {
            val loc = currentLocation()
            val stored = db.getKnownStatus(
                cell.mnc, cell.tac, cell.cellId, cell.mcc,
                loc?.latitude, loc?.longitude, cell.radioTech
            )
            if (stored != VerificationStatus.PENDING) {
                cache[key] = stored
                if (stored == VerificationStatus.NOT_FOUND) notFoundTimes[key] = now()
                publish(cell, stored)
                persist(cell, stored)
                return@launch
            }

            log("Verificando antena MCC ${cell.mcc} · MNC ${cell.mnc} · TAC ${cell.tac} · CID ${cell.cellId}")
            val result = OpenCellIdClient.tryOpenCellIdSyncWithData(cell, token, proxyEnabled(), client)
            log("OpenCellID → ${result.status.name}")
            result.reason?.let(log)

            var status = result.status
            val data = result.record
            if (status == VerificationStatus.VERIFIED && data != null) {
                val lat = data.optDouble("lat", Double.NaN)
                val lon = data.optDouble("lon", Double.NaN)
                val hasCoordinates = !lat.isNaN() && !lon.isNaN()
                if (hasCoordinates && coordinateValidator.isValid(lat, lon, cell)) {
                    completeVerified(cell, key, lat, lon)
                    return@launch
                }
                status = VerificationDecision.combine(VerificationStatus.PENDING, VerificationStatus.REJECTED)
                log(if (hasCoordinates) {
                    "OpenCellID: respuesta descartada por coordenada no creíble. No se concluye nada sobre la antena."
                } else {
                    "OpenCellID: respuesta sin coordenadas; no se puede comprobar."
                })
            }

            if (status != VerificationStatus.VERIFIED &&
                db.hasRecentVerifiedRecord(cell.cellId, cell.mnc, cell.tac, cell.mcc, cell.radioTech)) {
                cache[key] = VerificationStatus.VERIFIED
                log("Esta antena ya constaba verificada; una consulta vacía o fallida no borra esa evidencia.")
                persist(cell, VerificationStatus.VERIFIED)
                publish(cell, VerificationStatus.VERIFIED)
                return@launch
            }
            complete(cell, key, status)
        }
    }

    private fun complete(cell: CellData, key: String, requested: VerificationStatus) {
        val status = if (requested == VerificationStatus.PENDING) VerificationStatus.ERROR else requested
        cache[key] = status
        when (status) {
            VerificationStatus.NOT_FOUND -> {
                notFoundTimes[key] = now()
                persist(cell, status)
                log("Antena no identificada en OpenCellID. Se reintentará en 1 h.")
            }
            VerificationStatus.REJECTED -> {
                rejectedTimes[key] = now()
                persist(cell, status)
                log("Respuesta descartada: no permite afirmar ni desmentir nada sobre esta antena.")
            }
            VerificationStatus.ERROR -> {
                errorTimes[key] = now()
                log("Error al verificar. Se reintentará más tarde.")
            }
            VerificationStatus.VERIFIED -> persist(cell, status)
            VerificationStatus.PENDING -> Unit
        }
        publish(cell, status)
        if (status == VerificationStatus.NOT_FOUND) requestFreshCellInfo()
    }

    private fun completeVerified(cell: CellData, key: String, lat: Double, lon: Double) {
        cache[key] = VerificationStatus.VERIFIED
        cacheApiLocation(key, lat, lon)
        db.updateVerificationStatus(
            cell.mnc, cell.tac, cell.cellId, VerificationStatus.VERIFIED,
            lat, lon, cell.mcc, cell.radioTech
        )
        log("Validación OK (OpenCellID). Firmas geográficas obtenidas.")
        publish(cell, VerificationStatus.VERIFIED)
        onVerified(cell.copy(verified = VerificationStatus.VERIFIED))
        requestFreshCellInfo()
    }

    private fun persist(cell: CellData, status: VerificationStatus) {
        db.updateVerificationStatus(
            cell.mnc, cell.tac, cell.cellId, status,
            mcc = cell.mcc, radio = cell.radioTech
        )
    }

    private fun mayAttempt(key: String, status: VerificationStatus?): Boolean = when (status) {
        null -> true
        VerificationStatus.ERROR -> now() - (errorTimes[key] ?: 0L) >= ERROR_RETRY_MS
        VerificationStatus.NOT_FOUND -> now() - (notFoundTimes[key] ?: 0L) >= RECHECK_MS
        VerificationStatus.REJECTED -> now() - (rejectedTimes[key] ?: 0L) >= RECHECK_MS
        else -> false
    }

    private fun isQueryable(cell: CellData): Boolean =
        listOf(cell.mcc, cell.mnc, cell.tac, cell.cellId).all {
            it.isNotBlank() && it != "N/A" && it.toLongOrNull() != null
        }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val ERROR_RETRY_MS = 60_000L
        const val RECHECK_MS = 60L * 60L * 1000L
    }
}
