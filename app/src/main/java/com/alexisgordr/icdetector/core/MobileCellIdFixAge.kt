package com.alexisgordr.icdetector.core

/**
 * 3.0 (corte de campaña) — Edad máxima del fix GPS con el que H11 (consistencia geográfica) compara.
 *
 * En modo continuo la ubicación acepta fixes de hasta 120 s, y H11 la usaba sin mirar su edad. A
 * 30 m/s, 120 s son 3,6 km: más que el umbral urbano de H11 (2 km con 12 o más vecinas), así que un
 * fix viejo bastaba para que la celda pareciera "fuera de sitio".
 *
 * 60 s: el error por un fix viejo es velocidad × edad. En ciudad (≤ 15 m/s, umbral de 2 a 4 km)
 * queda en 0,9 km; en autopista (≈ 30 m/s), en 1,8 km, donde hay pocas vecinas y el umbral es de 4 a
 * 25 km. No es el de H16 (10 s, [GpsFixAge.LIVE_MAX_AGE_MS]): H16 mide el desplazamiento entre dos
 * fixes y necesita los dos frescos; H11 compara una posición con muestras históricas de la celda y
 * además exige dos registros discrepantes y el 30 % de ellos, así que tolera más.
 *
 * La edad es la del reloj monotónico del fix (elapsedRealtimeNanos) en la entrega. Sin edad
 * conocida no se puede asegurar que sea reciente: H11 también se abstiene.
 */
internal object MobileCellIdFixAge {
    const val MAX_AGE_MS = 60_000L

    /** ¿Vale el fix para H11? [fixAgeMs] null = edad desconocida, no vale. */
    fun usable(fixAgeMs: Long?): Boolean = fixAgeMs != null && fixAgeMs in 0..MAX_AGE_MS
}
