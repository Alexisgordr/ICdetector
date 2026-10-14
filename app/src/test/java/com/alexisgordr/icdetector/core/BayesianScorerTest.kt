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
    fun `sin evidencia devuelve el prior del 0,5 por ciento`() {
        // 3.0 (corte de campaña): prior 0.02 -> 0.005 (corte de dataset de anomaly_confidence).
        assertEquals(0.005f, BayesianScorer.PRIOR, 0f)
        val result = BayesianScorer.calculate(emptyList(), "PENDING", false)
        assertEquals(0.5f, result, 0.01f)
    }

    @Test
    fun `una heuristica fuerte sola sube el posterior`() {
        // mccMismatch (H3) es independiente, LR = 9 -> ~4.3% con prior 0.005
        val result = BayesianScorer.calculate(listOf("mccMismatch"), "PENDING", false)
        assertTrue("esperado ~4.3%, fue $result", result in 4.2f..4.5f)
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
            listOf("mccMismatch", "taDistance", "tacDev", "powerJump", "h11", "rfStability"),
            "NOT_FOUND", true
        )
        assertEquals(95.0f, result, 0.01f)
    }

    @Test
    fun `nunca supera el 95 por ciento ni baja de 0`() {
        val alto = BayesianScorer.calculate(
            listOf("mccMismatch", "tacDev", "h11"), "NOT_FOUND", true
        )
        assertTrue(alto <= 95.0f)
        val bajo = BayesianScorer.calculate(emptyList(), "VERIFIED", false)
        assertTrue(bajo >= 0f)
    }

    /**
     * v2.1 — El estado de verificación externa NO mueve el posterior.
     *
     * Antes VERIFIED valía 0,4 y NOT_FOUND 1,4, y había un test por cada uno exigiendo justo eso.
     * Las dos cifras eran estimaciones razonadas sobre una base colaborativa de cobertura
     * irregular: donde el mapeo es pobre, el 1,4 se lo llevan todas las celdas de la zona, y una
     * razón que se aplica a todas no separa ninguna. Durante la fase de recolección el posterior
     * tiene que salir solo de las heurísticas, para poder cruzarlo después contra la etiqueta de
     * verificación y medir si aporta algo. Este test protege esa independencia.
     */
    @Test
    fun `el estado de verificacion no altera el posterior durante la fase de medicion`() {
        val pendiente = BayesianScorer.calculate(listOf("powerJump"), "PENDING", false)
        for (estado in listOf("VERIFIED", "NOT_FOUND", "ERROR")) {
            val conEstado = BayesianScorer.calculate(listOf("powerJump"), estado, false)
            assertTrue(
                "$estado no debe mover el posterior (esperado $pendiente, obtenido $conEstado)",
                conEstado == pendiente
            )
        }
    }

    @Test
    fun `H12 fallida suma evidencia debil pero suma`() {
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

    /** 3.0 (corte de campaña) — H9 está reservada: una app no puede ver el cifrado, así que no tiene LR. */
    @Test
    fun `H9 is reserved and never moves the posterior`() {
        val prior = BayesianScorer.calculate(emptyList(), "PENDING", false)
        assertEquals(prior, BayesianScorer.calculate(listOf("ciphering"), "PENDING", false), 0f)
        assertTrue("ciphering" in BayesianScorer.UNAVAILABLE_KEYS)
        assertTrue("arfcn" in BayesianScorer.DATA_QUALITY_KEYS)
        assertEquals(BayesianScorer.Group.NOT_SCORED, BayesianScorer.groupOf("ciphering"))
    }

    /**
     * v2.10.6 — Cada nombre que ThreatAnalyzer puede añadir a la lista de fallos debe tener su
     * razón de verosimilitud. Un nombre sin LR vale 1.0 y no suma nada, en silencio: si algún
     * día se añade una heurística y se olvida su peso, este test lo dice.
     */
    @Test
    fun `toda heuristica que ThreatAnalyzer puede emitir tiene peso`() {
        val emitted = listOf(
            "isolated", "powerJump", "mccMismatch", "mncCount", "tacDev", "taDistance",
            "ghostCells", "pingPong", "h11", "signalBaseline",
            "bandDowngrade", "rfStability", "transitionCoherence"
        )
        val prior = BayesianScorer.calculate(emptyList(), "PENDING", false)
        emitted.forEach { name ->
            val posterior = BayesianScorer.calculate(listOf(name), "PENDING", false)
            assertTrue("'$name' no tiene LR: no mueve el posterior ($posterior)", posterior > prior)
        }
        // La latencia no va en la lista: entra por su propio parámetro, y también debe pesar.
        assertTrue(BayesianScorer.calculate(emptyList(), "PENDING", true) > prior)
    }

    @Test
    fun `H8 is a data-quality flag and never moves the posterior`() {
        val prior = BayesianScorer.calculate(emptyList(), "PENDING", false)
        assertEquals(prior, BayesianScorer.calculate(listOf("arfcn"), "PENDING", false), 0f)
        assertEquals(
            BayesianScorer.calculate(listOf("mccMismatch"), "PENDING", false),
            BayesianScorer.calculate(listOf("mccMismatch", "arfcn"), "PENDING", false), 0f
        )
    }

    // ── 3.0 (fase 4) — H6 en dominancia RF, amortiguada por reputación ──────────────────────

    @Test
    fun `H6 and H2 are the same nearby transmitter and do not multiply`() {
        val h6 = BayesianScorer.calculate(listOf("taDistance"), "PENDING", false)
        assertEquals(3.4f, h6, DELTA)
        // Antes, en grupos distintos, se multiplicaban. Ahora cuenta solo la mayor (7,0).
        assertEquals(h6, BayesianScorer.calculate(listOf("taDistance", "powerJump"), "PENDING", false), 0.001f)
        assertEquals(h6, BayesianScorer.calculate(listOf("taDistance", "ghostCells"), "PENDING", false), 0.001f)
    }

    @Test
    fun `H6 is damped by reputation but not by neighbour density`() {
        assertEquals(2.3f, BayesianScorer.calculate(listOf("taDistance"), "PENDING", false, trustScore = 85), DELTA)
        val dense = BayesianScorer.calculate(listOf("taDistance"), "PENDING", false, neighborCount = 6)
        assertEquals(dense, BayesianScorer.calculate(listOf("taDistance"), "PENDING", false, neighborCount = 0), 0.001f)
        // Amortiguar nunca invierte la evidencia.
        assertTrue(BayesianScorer.calculate(listOf("taDistance"), "PENDING", false, neighborCount = 0, trustScore = 100) > 0.5f)
    }

    @Test
    fun `H6 no longer stacks on the mobility group`() {
        val h11 = BayesianScorer.calculate(listOf("h11"), "PENDING", false)
        val both = BayesianScorer.calculate(listOf("h11", "taDistance"), "PENDING", false)
        assertTrue("grupos distintos: H11 y H6 suman (fenómenos distintos)", both > h11)
    }
}
