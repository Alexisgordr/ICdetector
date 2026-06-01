package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del motor bayesiano. BayesianScorer es lógica pura (sin dependencias de
 * Android), así que corre directamente en la JVM con JUnit.
 *
 * Ubicación en el proyecto:
 *   app/src/test/java/com/alexisgordr/icdetector/core/BayesianScorerTest.kt
 *
 * Ejecutar:  ./gradlew testDebugUnitTest
 */
class BayesianScorerTest {

    private val DELTA = 0.1f

    @Test
    fun `sin evidencia devuelve el prior del 2 por ciento`() {
        val result = BayesianScorer.calculate(emptyList(), "PENDING", false)
        assertEquals(2.0f, result, DELTA)
    }

    @Test
    fun `una heuristica fuerte sola sube el posterior`() {
        // ciphering (A5/0) es independiente, LR = 12 -> ~19.7%
        val result = BayesianScorer.calculate(listOf("ciphering"), "PENDING", false)
        assertTrue("esperado ~19.7%, fue $result", result in 18f..22f)
    }

    @Test
    fun `el grupo RF Dominance no infla - solo cuenta la LR maxima`() {
        // powerJump (5.5) es la LR mas alta del grupo RF.
        // Anadir signalBaseline (3.5), isolated (3.5) y ghostCells (4.5) NO debe
        // cambiar el resultado: estan correlacionados y solo aplica el maximo.
        val soloPower = BayesianScorer.calculate(listOf("powerJump"), "PENDING", false)
        val todoElGrupo = BayesianScorer.calculate(
            listOf("powerJump", "signalBaseline", "isolated", "ghostCells"),
            "PENDING", false
        )
        assertEquals(soloPower, todoElGrupo, DELTA)
    }

    @Test
    fun `signalBaseline queda absorbido por powerJump (misma dominancia RF)`() {
        val soloPower = BayesianScorer.calculate(listOf("powerJump"), "PENDING", false)
        val powerMasBaseline =
            BayesianScorer.calculate(listOf("powerJump", "signalBaseline"), "PENDING", false)
        assertEquals(soloPower, powerMasBaseline, DELTA)
    }

    @Test
    fun `signalBaseline sola si contribuye (es la unica del grupo)`() {
        val sinNada = BayesianScorer.calculate(emptyList(), "PENDING", false)
        val conBaseline = BayesianScorer.calculate(listOf("signalBaseline"), "PENDING", false)
        assertTrue("signalBaseline sola debe subir el posterior", conBaseline > sinNada)
    }

    @Test
    fun `el posterior se satura al 95 por ciento (honestidad epistemica)`() {
        val result = BayesianScorer.calculate(
            listOf("ciphering", "arfcn", "mccMismatch", "taDistance", "tacDev", "powerJump"),
            "NOT_FOUND", true
        )
        assertEquals(95.0f, result, 0.01f)
    }

    @Test
    fun `nunca supera el 95 por ciento ni baja de 0`() {
        val alto = BayesianScorer.calculate(
            listOf("ciphering", "arfcn", "mccMismatch"), "NOT_FOUND", true
        )
        assertTrue(alto <= 95.0f)
        val bajo = BayesianScorer.calculate(emptyList(), "VERIFIED", false)
        assertTrue(bajo >= 0f)
    }

    @Test
    fun `verificada en la BD reduce el posterior frente a pendiente`() {
        val pendiente = BayesianScorer.calculate(listOf("powerJump"), "PENDING", false)
        val verificada = BayesianScorer.calculate(listOf("powerJump"), "VERIFIED", false)
        assertTrue("VERIFIED (LR 0.4) debe bajar el riesgo", verificada < pendiente)
    }

    @Test
    fun `no encontrada en la BD sube ligeramente el posterior`() {
        val pendiente = BayesianScorer.calculate(listOf("powerJump"), "PENDING", false)
        val noEncontrada = BayesianScorer.calculate(listOf("powerJump"), "NOT_FOUND", false)
        assertTrue("NOT_FOUND (LR 1.4) debe subir un poco el riesgo", noEncontrada > pendiente)
    }

    @Test
    fun `la latencia anomala suma evidencia debil pero suma`() {
        val sinLatencia = BayesianScorer.calculate(listOf("powerJump"), "PENDING", false)
        val conLatencia = BayesianScorer.calculate(listOf("powerJump"), "PENDING", true)
        assertTrue("latencia (LR 1.8) debe subir algo el posterior", conLatencia > sinLatencia)
    }
}
