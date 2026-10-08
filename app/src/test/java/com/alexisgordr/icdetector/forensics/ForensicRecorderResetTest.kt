package com.alexisgordr.icdetector.forensics

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.ForensicCaseOrigin
import com.alexisgordr.icdetector.models.ForensicCaseState
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.TemporalProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.10.10 — "Borrar historial" durante una captura. Tras reset() el grabador no escribe en el caso
 * borrado ni vuelca en un caso nuevo observaciones de antes del borrado.
 */
class ForensicRecorderResetTest {
    private class FakeStore : ForensicStore {
        var nextId = 1L
        val samples = mutableListOf<Pair<Long, Long>>()   // caseId -> wall
        override fun createForensicCase(cell: CellData, origin: ForensicCaseOrigin) = nextId++
        override fun insertForensicSample(caseId: Long, wall: Long, elapsed: Long, event: String, json: String): Long {
            samples += caseId to wall
            return samples.size.toLong()
        }
        override fun updateForensicCaseProgress(caseId: Long, cell: CellData) {}
        override fun setForensicCaseState(caseId: Long, state: ForensicCaseState) {}
        override fun finishForensicCase(caseId: Long, state: ForensicCaseState) {}
        override fun promoteForensicCase(caseId: Long) {}
        override fun hasRecentForensicCaseFor(identity: String, sinceWallMs: Long) = false
    }

    private var clock = 1_000_000L
    private val store = FakeStore()
    private val recorder = ForensicRecorder(store, wallClock = { clock }, elapsedClock = { clock })

    private fun cell(phase: Int) = CellData(
        isRegistered = true, networkType = "LTE", cellId = "100", mnc = "07", tac = "1", dbm = -90,
        mcc = "214", radioTech = RadioTech.LTE, temporalProgress = TemporalProgress(phase = phase, required = 3)
    )

    private fun observe(phase: Int) = runBlocking {
        recorder.observe(cell(phase), emptyList(), null, "N/A", emptyList())
        clock += 2_000L
    }

    @Test fun `after reset the deleted case gets no samples and the old prebuffer is gone`() {
        observe(0); observe(0)          // prebúfer de antes del borrado
        observe(1)                      // abre el caso 1 y vuelca el prebúfer
        val beforeReset = clock
        assertTrue(store.samples.all { it.first == 1L })

        runBlocking { recorder.reset() }
        store.samples.clear()

        observe(0)                      // ya no hay caso abierto: nada se escribe
        assertTrue("wrote into the deleted case: ${store.samples}", store.samples.isEmpty())

        observe(1)                      // nuevo episodio: caso 2
        assertTrue(store.samples.isNotEmpty())
        assertTrue(store.samples.all { it.first == 2L })
        assertTrue(
            "prebuffer from before the reset was flushed: ${store.samples}",
            store.samples.all { it.second >= beforeReset }
        )
        assertEquals(2L, store.samples.first().first)
    }
}
