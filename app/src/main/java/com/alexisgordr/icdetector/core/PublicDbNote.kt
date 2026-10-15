package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.VerificationStatus

/**
 * 3.0 — Qué se muestra sobre la base pública (OpenCellID) para una celda: tarjeta de estado,
 * notificación y terminal. Solo presentación: el estado guardado y la lógica de verificación no
 * cambian.
 *
 * Sin token configurado, la verificación sale sin consultar y la celda se queda en su PENDING por
 * defecto. Mostrarlo como "consultando" (o "pendiente") era falso: no se consulta nada. En ese caso
 * se dice que la comprobación está desactivada por falta de token. Un resultado real anterior
 * (registrada, sin registro…) se sigue mostrando tal cual.
 */
enum class PublicDbNote {
    REGISTERED, NOT_FOUND, REJECTED, QUERYING, ERROR, CHECK_OFF_NO_TOKEN;

    companion object {
        fun of(status: VerificationStatus, hasCredentials: Boolean): PublicDbNote = when (status) {
            VerificationStatus.VERIFIED -> REGISTERED
            VerificationStatus.NOT_FOUND -> NOT_FOUND
            VerificationStatus.REJECTED -> REJECTED
            VerificationStatus.ERROR -> ERROR
            VerificationStatus.PENDING -> if (hasCredentials) QUERYING else CHECK_OFF_NO_TOKEN
        }
    }
}
