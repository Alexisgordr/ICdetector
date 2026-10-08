package com.alexisgordr.icdetector.service

import com.alexisgordr.icdetector.core.HistoryEpoch

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
    /** Publica en pantalla; recibe la época en la que empezó la consulta (v2.10.10). */
    private val publish: (CellData, VerificationStatus, Long) -> Unit,
    private val cacheApiLocation: (String, Double, Double) -> Unit,
    private val onVerified: (CellData) -> Unit,
    private val requestFreshCellInfo: () -> Unit,
    /**
     * v2.10.10 — Cambia con cada "Borrar historial". Una consulta que empezó antes del borrado no
     * escribe ni publica nada al terminar: antes podía repoblar las cachés y actualizar filas nuevas
     * con el resultado de una verificación de antes del borrado.
     */
    private val historyEpoch: HistoryEpoch = HistoryEpoch()
) {
    /** Ejecuta [block] solo si el historial no se ha borrado desde que empezó la consulta. */
    private fun ifCurrent(generation: Long, block: () -> Unit): Boolean =
        historyEpoch.ifCurrent(generation, block)

    private var loggedMissingCredentials = false

    fun verify(cell: CellData) {
        if (!isQueryable(cell)) return
        val key = cell.identityKey
        if (!mayAttempt(key, cache[key])) return
        val generation = historyEpoch.current()

        val token = apiKey().trim()
        if (token.isBlank() || token.startsWith("pk.YOUR")) {
            // v2.10.10 — La re-comprobación de un VERIFIED en memoria es local (base de datos y su
            // TTL) y no necesita OpenCellID: antes, sin credenciales, se salía aquí y la etiqueta
            // podía seguir caducada indefinidamente.
            if (cache[key] == VerificationStatus.VERIFIED) recheckVerifiedLocally(cell, key, generation)
            if (!loggedMissingCredentials) {
                log("⚠ Sin credenciales configuradas. Ve a Ajustes para añadir OpenCellID.")
                loggedMissingCredentials = true
            }
            return
        }
        loggedMissingCredentials = false

        synchronized(cache) {
            if (!mayAttempt(key, cache[key])) return
            if (cache[key] == VerificationStatus.VERIFIED) {
                // Re-comprobación periódica (v2.10.10): la etiqueta sigue siendo VERIFIED mientras
                // se consulta, para no guardar filas como PENDING durante ese instante.
                verifiedCheckedAt[key] = now()
            } else {
                cache[key] = VerificationStatus.PENDING
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                verifyNow(cell, key, token, generation)
            } catch (e: Exception) {
                // Sin esto la antena se quedaba en PENDING en la caché y mayAttempt() no la
                // volvía a intentar hasta reiniciar el servicio. Como ERROR se reintenta sola.
                ifCurrent(generation) {
                    if (cache[key] == VerificationStatus.PENDING) {
                        cache[key] = VerificationStatus.ERROR
                        errorTimes[key] = now()
                    }
                }
                log("Error al verificar (${e.javaClass.simpleName}). Se reintentará más tarde.")
            }
        }
    }

    /** Re-comprobación local de un VERIFIED en memoria: solo base de datos, sin red. */
    private fun recheckVerifiedLocally(cell: CellData, key: String, generation: Long) {
        verifiedCheckedAt[key] = now()
        scope.launch(Dispatchers.IO) {
            val loc = currentLocation()
            val stored = db.getKnownStatus(
                cell.mnc, cell.tac, cell.cellId, cell.mcc,
                loc?.latitude, loc?.longitude, cell.radioTech
            )
            if (stored == VerificationStatus.VERIFIED) return@launch
            // Caducada (TTL) o ya no compatible: deja de mostrarse como VERIFIED. Sin credenciales
            // no se puede volver a consultar, así que queda pendiente.
            ifCurrent(generation) {
                cache.remove(key)
                verifiedCheckedAt.remove(key)
                publish(cell, VerificationStatus.PENDING, generation)
            }
        }
    }

    private fun verifyNow(cell: CellData, key: String, token: String, generation: Long) {
        val loc = currentLocation()
        val stored = db.getKnownStatus(
            cell.mnc, cell.tac, cell.cellId, cell.mcc,
            loc?.latitude, loc?.longitude, cell.radioTech
        )
        if (stored != VerificationStatus.PENDING) {
            ifCurrent(generation) {
                cache[key] = stored
                if (stored == VerificationStatus.VERIFIED) verifiedCheckedAt[key] = now()
                if (stored == VerificationStatus.NOT_FOUND) notFoundTimes[key] = now()
                publish(cell, stored, generation)
                persist(cell, stored)
            }
            return
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
                completeVerified(cell, key, lat, lon, generation)
                return
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
            ifCurrent(generation) {
                cache[key] = VerificationStatus.VERIFIED
                verifiedCheckedAt[key] = now()
                log("Esta antena ya constaba verificada; una consulta vacía o fallida no borra esa evidencia.")
                persist(cell, VerificationStatus.VERIFIED)
                publish(cell, VerificationStatus.VERIFIED, generation)
            }
            return
        }
        complete(cell, key, status, generation)
    }

    private fun complete(cell: CellData, key: String, requested: VerificationStatus, generation: Long) {
        ifCurrent(generation) { completeCurrent(cell, key, requested, generation) }
    }

    private fun completeCurrent(cell: CellData, key: String, requested: VerificationStatus, generation: Long) {
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
        publish(cell, status, generation)
        if (status == VerificationStatus.NOT_FOUND) requestFreshCellInfo()
    }

    private fun completeVerified(cell: CellData, key: String, lat: Double, lon: Double, generation: Long) {
        ifCurrent(generation) { completeVerifiedCurrent(cell, key, lat, lon, generation) }
    }

    private fun completeVerifiedCurrent(cell: CellData, key: String, lat: Double, lon: Double, generation: Long) {
        cache[key] = VerificationStatus.VERIFIED
        verifiedCheckedAt[key] = now()
        cacheApiLocation(key, lat, lon)
        db.updateVerificationStatus(
            cell.mnc, cell.tac, cell.cellId, VerificationStatus.VERIFIED,
            lat, lon, cell.mcc, cell.radioTech
        )
        log("Validación OK (OpenCellID). Firmas geográficas obtenidas.")
        publish(cell, VerificationStatus.VERIFIED, generation)
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
        // v2.10.10 — Antes VERIFIED no caducaba nunca dentro de una sesión: con el servicio en
        // marcha se conservaba más allá del TTL de 30 días que aplica la base de datos. Ahora se
        // vuelve a comprobar cada pocas horas; verifyNow() mira primero la base (que aplica el TTL
        // y la distancia) y solo pregunta a OpenCellID si la verificación guardada ya no vale.
        // Es una etiqueta: no cambia la puntuación.
        VerificationStatus.VERIFIED ->
            now() - verifiedCheckedAt.getOrPut(key) { now() } >= VERIFIED_RECHECK_MS
        else -> false
    }

    private fun isQueryable(cell: CellData): Boolean =
        listOf(cell.mcc, cell.mnc, cell.tac, cell.cellId).all {
            it.isNotBlank() && it != "N/A" && it.toLongOrNull() != null
        }

    private fun now() = System.currentTimeMillis()

    /** Última vez que se comprobó que una celda VERIFIED sigue siéndolo. */
    private val verifiedCheckedAt = ConcurrentHashMap<String, Long>()

    private companion object {
        const val ERROR_RETRY_MS = 60_000L
        const val RECHECK_MS = 60L * 60L * 1000L
        const val VERIFIED_RECHECK_MS = 6L * 60L * 60L * 1000L
    }
}
