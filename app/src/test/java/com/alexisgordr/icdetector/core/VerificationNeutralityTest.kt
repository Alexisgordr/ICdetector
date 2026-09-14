package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.TimingAdvanceUnit
import com.alexisgordr.icdetector.models.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La verificación externa (WiGLE / OpenCellID) **no puntúa** — v2.1.
 *
 * Durante la fase de recolección el score tiene que salir solo de las 14 heurísticas, para poder
 * cruzarlo después contra la etiqueta de verificación y medir si esa etiqueta aporta señal. Si el
 * estado ya estuviera metido en el score, se estaría contrastando el dato consigo mismo.
 *
 * Estos tests existen para que ese acoplamiento no vuelva a entrar por descuido.
 */
class VerificationNeutralityTest {

    private fun cell(estado: VerificationStatus) = CellData(
        isRegistered = true,
        networkType = "4G LTE",
        cellId = "12345",
        mnc = "01",
        tac = "100",
        dbm = -90,
        mcc = "214",
        verified = estado
    )

    private fun analizar(estado: VerificationStatus) = ThreatAnalyzer.analyzeThreats(
        active = cell(estado),
        neighbors = emptyList(),
        isHardwareCipheringActive = false,
        cellChangeHistory = emptyList(),
        currentLocation = null
    )

    @Test
    fun `el estado de verificacion no cambia el score`() {
        val referencia = analizar(VerificationStatus.PENDING).securityScore
        for (estado in VerificationStatus.entries) {
            assertEquals(
                "$estado no debe mover el score",
                referencia,
                analizar(estado).securityScore
            )
        }
    }

    @Test
    fun `no estar en las bases publicas no genera un motivo de sospecha`() {
        val r = analizar(VerificationStatus.NOT_FOUND)
        val motivo = r.suspiciousReason
        assertTrue(
            "NOT_FOUND no debe aparecer como observación sobre la antena: $motivo",
            motivo == null || !motivo.contains("bases públicas")
        )
    }

    @Test
    fun `una celda no encontrada nunca es sospechosa por ese motivo`() {
        assertTrue(!analizar(VerificationStatus.NOT_FOUND).isSuspicious)
    }

    /**
     * El caso donde el acoplamiento estaba escondido.
     *
     * El test general usa una celda que no dispara ninguna heurística, y por eso no detectaba que
     * H6 miraba `verified`: una celda con TA de proximidad y señal muy fuerte se llevaba -15 si
     * estaba PENDING y se libraba si estaba VERIFIED. Mismo TA, misma señal, distinto score según
     * lo que contestara una base pública. Aquí se reproduce exactamente esa celda y se exige que
     * los cinco estados den el MISMO resultado, campo por campo.
     */
    @Test
    fun `una celda que dispara H6 puntua igual en los cinco estados`() {
        fun celdaPegadaALaAntena(estado: VerificationStatus) = CellData(
            isRegistered = true,
            networkType = "4G LTE",
            cellId = "12345",
            mnc = "01",
            tac = "100",
            dbm = -55,                                   // señal muy fuerte
            mcc = "214",
            verified = estado,
            timingAdvance = 0,                           // TA de proximidad...
            timingAdvanceUnit = TimingAdvanceUnit.LTE_INDEX  // ...con unidad convertible
        )

        val referencia = ThreatAnalyzer.analyzeThreats(
            active = celdaPegadaALaAntena(VerificationStatus.PENDING),
            neighbors = emptyList(),
            isHardwareCipheringActive = false,
            cellChangeHistory = emptyList(),
            currentLocation = null
        )
        // Si esta celda dejara de disparar H6, el test perdería su sentido sin que nadie se
        // enterara: se comprueba que efectivamente la dispara.
        assertTrue(
            "La celda de prueba debe disparar H6; si no, este test no vigila nada",
            !referencia.heuristicReport.taDistancePassed
        )

        for (estado in VerificationStatus.entries) {
            val r = ThreatAnalyzer.analyzeThreats(
                active = celdaPegadaALaAntena(estado),
                neighbors = emptyList(),
                isHardwareCipheringActive = false,
                cellChangeHistory = emptyList(),
                currentLocation = null
            )
            assertEquals("$estado cambia el score", referencia.securityScore, r.securityScore)
            assertEquals("$estado cambia los motivos", referencia.suspiciousReason, r.suspiciousReason)
            assertEquals(
                "$estado cambia la confianza de anomalía",
                referencia.anomalyConfidence,
                r.anomalyConfidence,
                0.0001f
            )
            assertEquals(
                "$estado cambia el veredicto de H6",
                referencia.heuristicReport.taDistancePassed,
                r.heuristicReport.taDistancePassed
            )
        }
    }
}
