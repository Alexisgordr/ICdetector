package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.ui.localizeTerminalLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.1.0 — «Borrar historial» distingue una restauración en curso (puerta cerrada, no se intenta) de
 * un error al borrar (se intentó y falló). Antes, un fallo de la base se informaba como restauración.
 */
class HistoryClearTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test fun `open gate - the history is cleared`() = runBlocking {
        var calls = 0
        val outcome = HistoryClear.run(WriteGate(), scope, Dispatchers.IO) { calls++ }
        assertEquals(HistoryClear.Outcome.Cleared, outcome)
        assertEquals(1, calls)
        assertEquals("Historial borrado: captura forense, episodios y cachés reiniciados.", HistoryClear.logLine(outcome))
    }

    @Test fun `the clear throws - reported as an error with its class, not as a restore`() = runBlocking {
        val outcome = HistoryClear.run(WriteGate(), scope, Dispatchers.IO) { throw IllegalStateException("disk I/O error") }
        assertEquals(HistoryClear.Outcome.Failed("IllegalStateException"), outcome)
        val line = HistoryClear.logLine(outcome)
        assertEquals("Historial NO borrado: error IllegalStateException.", line)
        assertFalse(line.contains("restauración"))
        assertEquals("History NOT deleted: error IllegalStateException.", localizeTerminalLine(line))
    }

    @Test fun `closed gate (restore in progress) - not attempted`() = runBlocking {
        val gate = WriteGate()
        assertTrue(gate.closeAndDrain(1_000))
        var calls = 0
        val outcome = HistoryClear.run(gate, scope, Dispatchers.IO) { calls++ }
        assertEquals(HistoryClear.Outcome.RestoreInProgress, outcome)
        assertEquals("no se toca la base", 0, calls)
        assertEquals("Historial NO borrado: hay una restauración en curso.", HistoryClear.logLine(outcome))
        assertEquals("History NOT deleted: a restore is in progress.", localizeTerminalLine(HistoryClear.logLine(outcome)))
    }

    @Test fun `the success line is translated too`() {
        assertEquals("History deleted: forensic capture, episodes and caches reset.",
            localizeTerminalLine(HistoryClear.logLine(HistoryClear.Outcome.Cleared)))
    }

    @Test fun `the service clears through it and logs its outcome`() {
        val service = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        val clear = service.substringAfter("suspend fun clearHistory() {").substringBefore("private fun forgetHistoryDerivedState()")
        assertTrue(clear.contains("HistoryClear.run(writeGate, scope, historyWriteDispatcher) { dbHelper.clear() }"))
        assertTrue(clear.contains("appendLog(\"[SYS]\", com.alexisgordr.icdetector.core.HistoryClear.logLine(outcome))"))
        assertFalse("sin textos sueltos", clear.contains("Historial"))
    }
}
