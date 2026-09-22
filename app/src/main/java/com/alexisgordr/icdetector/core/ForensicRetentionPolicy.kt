package com.alexisgordr.icdetector.core

/**
 * Qué casos forenses hay que borrar para caber bajo el tope de muestras — v2.8.0.
 *
 * El recorte anterior borraba muestras sueltas ordenadas por `id`, sin mirar de qué caso eran.
 * Eso mantenía el tope y destruía lo único que hace útil a un paquete forense: que esté completo.
 * Un caso al que le faltan el prebúfer y los primeros minutos se sigue listando con su código
 * `ICD-…` y su recuento, se exporta y se analiza como si estuviera íntegro. Un paquete que miente
 * sobre su propia integridad es peor que no tener el paquete.
 *
 * Aquí vive solo la decisión, sin SQLite ni Android, para poder probarla. La garantía de no tocar
 * una captura en curso es de quien construye la lista: [casesToDelete] solo puede borrar lo que se
 * le pasa, y [com.alexisgordr.icdetector.storage.CellDbHelper.enforceForensicSampleCap] le pasa
 * exclusivamente casos en READY o INTERRUPTED.
 */
object ForensicRetentionPolicy {

    /** Un caso cerrado candidato a la poda, con cuántas muestras se llevaría consigo. */
    data class Candidate(val caseId: Long, val sampleCount: Int)

    /**
     * Casos a borrar, en el orden en que se recibieron (del más antiguo al más reciente).
     *
     * Se para en cuanto el total cabe bajo [maxSamples]: la poda es lo mínimo necesario, no una
     * limpieza general. Si los casos cerrados no bastan, devuelve todos y el total sigue por
     * encima — ese caso lo comunica el llamante, porque la alternativa sería mutilar la captura
     * que está grabando ahora mismo.
     */
    fun casesToDelete(
        totalSamples: Int,
        maxSamples: Int,
        closedCasesOldestFirst: List<Candidate>
    ): List<Candidate> {
        if (totalSamples <= maxSamples) return emptyList()
        var remaining = totalSamples
        val doomed = mutableListOf<Candidate>()
        for (candidate in closedCasesOldestFirst) {
            if (remaining <= maxSamples) break
            doomed += candidate
            remaining -= candidate.sampleCount
        }
        return doomed
    }

    /** Muestras que quedarían tras aplicar [casesToDelete]. */
    fun remainingAfter(totalSamples: Int, deleted: List<Candidate>): Int =
        totalSamples - deleted.sumOf { it.sampleCount }
}
