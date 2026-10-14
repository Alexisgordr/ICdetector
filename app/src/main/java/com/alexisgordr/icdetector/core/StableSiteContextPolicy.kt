package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData

/**
 * 3.0 — Qué ciclos alimentan el contexto aprendido de Stable-Site (días como servidora, vecinas,
 * huellas RF de vecinas sin CID y días de movimiento del sitio).
 *
 * Antes se anotaba CUALQUIER ciclo, alarma confirmada incluida: un emisor que insistiera 3 días en
 * casa pasaba a ser celda conocida del sitio, y como vecina se corroboraba a sí mismo en 2. Era la
 * única fuente de aprendizaje de la app que no filtraba los ciclos sucios. Ahora solo aprende de un
 * ciclo con el mismo criterio que una fila limpia del historial (ninguna regla fallida, no
 * sospechoso, sin contradicción de confianza local) y sin racha de confirmación ni episodio
 * multiseñal abiertos.
 *
 * La marca de sitio sin verificar NO cuenta como sucia: es la propia novedad que Stable-Site mide, y
 * si la excluyera una celda nueva legítima nunca llegaría a ser conocida del sitio.
 */
object StableSiteContextPolicy {
    fun mayLearn(serving: CellData, inConfirmation: Boolean, episodeOpen: Boolean): Boolean =
        !serving.isSuspicious &&
            serving.heuristicReport.failedCount == 0 &&
            serving.localCellTrust.contradictions.isEmpty() &&
            !inConfirmation &&
            !episodeOpen
}
