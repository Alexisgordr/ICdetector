package com.alexisgordr.icdetector.forensics

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.LocalCellTrustState
import com.alexisgordr.icdetector.models.identityKey

/**
 * Qué clase de contradicción de confianza acaba de observarse para una identidad completa.
 *
 * v2.8.0 — Antes esto era un simple `Boolean` y solo podía valer `true` cuando el proceso había
 * visto la identidad en ESTABLISHED **en esta ejecución**. Esa condición deja un punto ciego real:
 * el mapa vive en memoria, así que un reinicio del servicio (reinicio del móvil, OTA, muerte del
 * proceso por memoria) lo vacía. Si el servicio arranca con la celda ya en CHANGED y contradicha,
 * no hay transición que observar y el caso forense no se abre nunca — justo en el escenario en el
 * que más interesa: el atacante ya estaba ahí antes de que la app volviera.
 *
 * [ON_START] nombra exactamente ese caso: primera observación de esta identidad en este proceso y
 * ya viene contradicha. Es una señal más débil que [TRANSITION] (no hemos visto el flanco con
 * nuestros propios ojos, nos fiamos del perfil guardado en disco), y por eso el grabador la
 * deduplica por identidad con una ventana de 24 h contra la base de datos — ver [ForensicRecorder].
 */
enum class TrustContradictionSignal {
    /** Nada que registrar. */
    NONE,

    /** Flanco ESTABLISHED -> CHANGED observado en vivo, con contradicciones. */
    TRANSITION,

    /** Primera observación de la identidad en este proceso, ya en CHANGED y con contradicciones. */
    ON_START
}

/**
 * Detects an actual ESTABLISHED -> CHANGED transition for one complete cell identity.
 * It is observational only: it never mutates [CellData] or feeds a result back into detection.
 */
class TrustContradictionTransitionTracker(private val maxIdentities: Int = 500) {
    private val previousStates = LinkedHashMap<String, LocalCellTrustState>()

    fun observe(cell: CellData): TrustContradictionSignal {
        val identity = cell.identityKey
        val previous = previousStates.put(identity, cell.localCellTrust.state)
        while (previousStates.size > maxIdentities) {
            previousStates.remove(previousStates.keys.first())
        }
        val contradicted = cell.localCellTrust.state == LocalCellTrustState.CHANGED &&
            cell.localCellTrust.contradictions.isNotEmpty()
        if (!contradicted) return TrustContradictionSignal.NONE
        return when (previous) {
            LocalCellTrustState.ESTABLISHED -> TrustContradictionSignal.TRANSITION
            // Sin estado previo: o el proceso acaba de arrancar, o es la primera vez que vemos
            // esta identidad, o fue desalojada del mapa por el tope de [maxIdentities].
            null -> TrustContradictionSignal.ON_START
            else -> TrustContradictionSignal.NONE
        }
    }
}
