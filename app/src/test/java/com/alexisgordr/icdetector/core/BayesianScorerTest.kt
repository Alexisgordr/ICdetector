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

    // --- Bayesiano adaptativo al contexto (densidad de entorno) ---

    @Test
    fun `powerJump pesa menos en entorno disperso que en denso`() {
        // Mismo indicio (salto de potencia) en rural (0 vecinas) vs urbano (muchas).
        val denso = BayesianScorer.calculate(listOf("powerJump"), "PENDING", false, neighborCount = 12)
        val disperso = BayesianScorer.calculate(listOf("powerJump"), "PENDING", false, neighborCount = 0)
        assertTrue("en rural el salto de potencia debe pesar menos", disperso < denso)
    }

    @Test
    fun `sin densidad (compat) se comporta como el modo denso`() {
        // neighborCount = -1 (por defecto) no debe cambiar nada respecto al cálculo clásico.
        val clasico = BayesianScorer.calculate(listOf("powerJump"), "PENDING", false)
        val denso = BayesianScorer.calculate(listOf("powerJump"), "PENDING", false, neighborCount = 12)
        assertEquals("desconocido debe igualar a denso", denso, clasico, 0.001f)
    }

    @Test
    fun `las heuristicas fisicas NO se suavizan por densidad`() {
        // MCC mismatch (físicamente sólida) debe valer igual en rural que en urbano.
        val denso = BayesianScorer.calculate(listOf("mccMismatch"), "PENDING", false, neighborCount = 12)
        val disperso = BayesianScorer.calculate(listOf("mccMismatch"), "PENDING", false, neighborCount = 0)
        assertEquals("MCC no debe depender de la densidad", denso, disperso, 0.001f)
    }

    @Test
    fun `el cifrado A5_0 NO se suaviza por densidad`() {
        val denso = BayesianScorer.calculate(listOf("ciphering"), "PENDING", false, neighborCount = 12)
        val disperso = BayesianScorer.calculate(listOf("ciphering"), "PENDING", false, neighborCount = 0)
        assertEquals("el cifrado anulado vale igual en cualquier entorno", denso, disperso, 0.001f)
    }

    // --- Reputación (trustScore) ---

    @Test
    fun `una celda muy probada amortigua el ruido de una heuristica debil`() {
        val nueva = BayesianScorer.calculate(listOf("powerJump"), "PENDING", false, neighborCount = 12, trustScore = -1)
        val probada = BayesianScorer.calculate(listOf("powerJump"), "PENDING", false, neighborCount = 12, trustScore = 100)
        assertTrue("en celda probada el salto de potencia debe pesar menos", probada < nueva)
    }

    @Test
    fun `trustScore desconocido (-1) no cambia nada`() {
        val clasico = BayesianScorer.calculate(listOf("powerJump"), "PENDING", false, neighborCount = 12)
        val conTrust = BayesianScorer.calculate(listOf("powerJump"), "PENDING", false, neighborCount = 12, trustScore = -1)
        assertEquals("desconocido no debe alterar el cálculo", clasico, conTrust, 0.001f)
    }

    @Test
    fun `la reputacion NO amortigua las heuristicas fisicas`() {
        // MCC mismatch debe valer igual aunque la celda sea de máxima confianza.
        val nueva = BayesianScorer.calculate(listOf("mccMismatch"), "PENDING", false, trustScore = -1)
        val probada = BayesianScorer.calculate(listOf("mccMismatch"), "PENDING", false, trustScore = 100)
        assertEquals("MCC no depende de la reputación", nueva, probada, 0.001f)
    }

    @Test
    fun `el cifrado A5_0 NO se amortigua por reputacion`() {
        val nueva = BayesianScorer.calculate(listOf("ciphering"), "PENDING", false, trustScore = -1)
        val probada = BayesianScorer.calculate(listOf("ciphering"), "PENDING", false, trustScore = 100)
        assertEquals("el cifrado anulado vale igual aunque la celda sea de confianza", nueva, probada, 0.001f)
    }
}
