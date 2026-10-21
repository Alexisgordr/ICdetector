package com.alexisgordr.icdetector.models

/**
 * Resultado de la verificación externa de una celda contra una base pública.
 *
 * 3.1.0 — La verificación externa se retiró y la app ya no tiene conexión a Internet. Las filas
 * nuevas se guardan siempre como [PENDING]. El enum se conserva porque la columna `verified` y la
 * columna `Verified` del CSV siguen existiendo, y las filas anteriores a 3.1.0 pueden traer
 * cualquiera de estos valores.
 */
enum class VerificationStatus {

    /** Sin consultar. Desde 3.1.0, el valor de todas las filas nuevas. */
    PENDING,

    /** Antes de 3.1.0: la base respondió con la celda preguntada y una coordenada creíble. */
    VERIFIED,

    /** Antes de 3.1.0: la base dijo explícitamente que no conocía la celda. */
    NOT_FOUND,

    /** Antes de 3.1.0: la base respondió, pero la respuesta no superó las comprobaciones. */
    REJECTED,

    /** Antes de 3.1.0: no se obtuvo una respuesta interpretable. */
    ERROR;
}
