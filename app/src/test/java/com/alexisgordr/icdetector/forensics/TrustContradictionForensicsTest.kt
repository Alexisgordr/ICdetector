package com.alexisgordr.icdetector.forensics

import com.alexisgordr.icdetector.core.ThreatEpisodeTracker
import com.alexisgordr.icdetector.models.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrustContradictionForensicsTest {
    private fun cell(
        state: LocalCellTrustState,
        contradictions: Set<String> = emptySet(),
        phase: Int = 0,
        cid: String = "100",
        score: Int = 83,
        confidence: Float = 12.5f
    ) = CellData(
        isRegistered = true, networkType = "4G LTE", cellId = cid, mnc = "07",
        tac = "31601", dbm = -90, mcc = "214", radioTech = RadioTech.LTE,
        securityScore = score, anomalyConfidence = confidence,
        temporalProgress = TemporalProgress(phase),
        localCellTrust = LocalCellTrust(state = state, contradictions = contradictions)
    )

    @Test fun `established to changed with contradiction triggers capture`() {
        val tracker = TrustContradictionTransitionTracker()
        assertEquals(TrustContradictionSignal.NONE, tracker.observe(cell(LocalCellTrustState.ESTABLISHED)))
        assertEquals(
            TrustContradictionSignal.TRANSITION,
            tracker.observe(cell(LocalCellTrustState.CHANGED, setOf("PCI")))
        )
    }

    @Test fun `changed without contradictions does not trigger capture`() {
        val tracker = TrustContradictionTransitionTracker()
        tracker.observe(cell(LocalCellTrustState.ESTABLISHED))
        assertEquals(TrustContradictionSignal.NONE, tracker.observe(cell(LocalCellTrustState.CHANGED)))
    }

    // v2.8.0 — Cambio de comportamiento deliberado. Antes esto devolvía false y era el punto
    // ciego: el servicio que arranca con la celda ya contradicha no veía ningún flanco y no
    // abría caso jamás. Ahora se distingue de una transición observada en vivo y se deduplica
    // contra la base de datos, no en memoria.
    @Test fun `cell never observed established reports an on-start contradiction`() {
        val tracker = TrustContradictionTransitionTracker()
        assertEquals(
            TrustContradictionSignal.ON_START,
            tracker.observe(cell(LocalCellTrustState.CHANGED, setOf("PCI")))
        )
    }

    @Test fun `on-start contradiction requires real contradictions`() {
        val tracker = TrustContradictionTransitionTracker()
        assertEquals(TrustContradictionSignal.NONE, tracker.observe(cell(LocalCellTrustState.CHANGED)))
    }

    @Test fun `on-start contradiction is reported only on the first observation`() {
        val tracker = TrustContradictionTransitionTracker()
        assertEquals(
            TrustContradictionSignal.ON_START,
            tracker.observe(cell(LocalCellTrustState.CHANGED, setOf("PCI")))
        )
        repeat(10) {
            assertEquals(
                TrustContradictionSignal.NONE,
                tracker.observe(cell(LocalCellTrustState.CHANGED, setOf("PCI")))
            )
        }
    }

    @Test fun `learning state before changed is not a contradiction transition`() {
        val tracker = TrustContradictionTransitionTracker()
        tracker.observe(cell(LocalCellTrustState.LEARNING))
        assertEquals(
            TrustContradictionSignal.NONE,
            tracker.observe(cell(LocalCellTrustState.CHANGED, setOf("PCI")))
        )
    }

    @Test fun `persistent changed state triggers only once`() {
        val tracker = TrustContradictionTransitionTracker()
        tracker.observe(cell(LocalCellTrustState.ESTABLISHED))
        assertEquals(
            TrustContradictionSignal.TRANSITION,
            tracker.observe(cell(LocalCellTrustState.CHANGED, setOf("PCI")))
        )
        repeat(10) {
            assertEquals(
                TrustContradictionSignal.NONE,
                tracker.observe(cell(LocalCellTrustState.CHANGED, setOf("PCI")))
            )
        }
    }

    @Test fun `recovery to established permits a later independent transition`() {
        val tracker = TrustContradictionTransitionTracker()
        tracker.observe(cell(LocalCellTrustState.ESTABLISHED))
        assertEquals(
            TrustContradictionSignal.TRANSITION,
            tracker.observe(cell(LocalCellTrustState.CHANGED, setOf("PCI")))
        )
        assertEquals(TrustContradictionSignal.NONE, tracker.observe(cell(LocalCellTrustState.ESTABLISHED)))
        assertEquals(
            TrustContradictionSignal.TRANSITION,
            tracker.observe(cell(LocalCellTrustState.CHANGED, setOf("ARFCN")))
        )
    }

    @Test fun `transition tracking is scoped to complete cell identity`() {
        val tracker = TrustContradictionTransitionTracker()
        tracker.observe(cell(LocalCellTrustState.ESTABLISHED, cid = "100"))
        // Otra identidad: no hay flanco observado, es una primera observación.
        assertEquals(
            TrustContradictionSignal.ON_START,
            tracker.observe(cell(LocalCellTrustState.CHANGED, setOf("PCI"), cid = "200"))
        )
    }

    @Test fun `observational trigger does not modify anomaly score`() {
        val tracker = TrustContradictionTransitionTracker()
        tracker.observe(cell(LocalCellTrustState.ESTABLISHED))
        val changed = cell(LocalCellTrustState.CHANGED, setOf("PCI"), score = 77, confidence = 31.25f)
        tracker.observe(changed)
        assertEquals(77, changed.securityScore)
    }

    @Test fun `observational trigger does not modify Bayesian confidence`() {
        val tracker = TrustContradictionTransitionTracker()
        tracker.observe(cell(LocalCellTrustState.ESTABLISHED))
        val changed = cell(LocalCellTrustState.CHANGED, setOf("PCI"), score = 77, confidence = 31.25f)
        tracker.observe(changed)
        assertEquals(31.25f, changed.anomalyConfidence, 0.0f)
    }

    @Test fun `observational trigger does not create a sound alarm`() {
        assertNeutralTrigger()
    }

    @Test fun `observational trigger does not create alarm vibration`() {
        assertNeutralTrigger()
    }

    @Test fun `observational trigger does not create a threat notification`() {
        assertNeutralTrigger()
    }

    @Test fun `tracking does not alter threat episode behavior`() {
        val established = cell(LocalCellTrustState.ESTABLISHED)
        val changed = cell(LocalCellTrustState.CHANGED, setOf("PCI"))
        val baselineTracker = ThreatEpisodeTracker()
        val observedTracker = ThreatEpisodeTracker()
        val transitionTracker = TrustContradictionTransitionTracker()
        transitionTracker.observe(established)
        transitionTracker.observe(changed)
        val baseline = baselineTracker.apply(changed, 1L, true)
        val observed = observedTracker.apply(changed, 1L, true)
        assertEquals(baseline, observed)
    }

    @Test fun `tracking does not introduce baseline learning`() {
        val tracker = TrustContradictionTransitionTracker()
        val established = cell(LocalCellTrustState.ESTABLISHED)
        val changed = cell(LocalCellTrustState.CHANGED, setOf("PCI"))
        tracker.observe(established)
        tracker.observe(changed)
        assertEquals(LocalCellTrustState.ESTABLISHED, established.localCellTrust.state)
        assertEquals(LocalCellTrustState.CHANGED, changed.localCellTrust.state)
    }

    @Test fun `silent case includes prebuffer trigger and post capture then closes`() = runBlocking {
        val store = FakeStore()
        var wall = 1_000L
        var elapsed = 1_000L
        val recorder = ForensicRecorder(store, { wall }, { elapsed })

        recorder.observe(cell(LocalCellTrustState.ESTABLISHED), emptyList(), null, "N/A", emptyList())
        wall += 1_000; elapsed += 1_000
        recorder.observe(
            cell(LocalCellTrustState.CHANGED, setOf("PCI")), emptyList(), null, "N/A", emptyList(),
            TrustContradictionSignal.TRANSITION
        )
        assertEquals(ForensicCaseOrigin.TRUST_CONTRADICTION, store.created.single().second)
        assertEquals(listOf("OBSERVATION", "TRUST_CONTRADICTION"), store.samples.map { it.event })
        assertEquals("CHANGED", JSONObject(store.samples.last().json).getJSONObject("localTrust").getString("state"))
        assertEquals(ForensicCaseState.POST_CAPTURE, store.states.last())

        wall += 30_000; elapsed += 30_000
        recorder.observe(cell(LocalCellTrustState.CHANGED, setOf("PCI")), emptyList(), null, "N/A", emptyList())
        assertNull(store.finished)
        wall += 30_000; elapsed += 30_000
        recorder.observe(cell(LocalCellTrustState.CHANGED, setOf("PCI")), emptyList(), null, "N/A", emptyList())
        assertEquals(ForensicCaseState.READY, store.finished)
        assertEquals(4, store.samples.size)
    }

    @Test fun `silent case preserves samples immediately before transition`() = runBlocking {
        val store = FakeStore()
        var time = 1_000L
        val recorder = ForensicRecorder(store, { time }, { time })
        recorder.observe(cell(LocalCellTrustState.ESTABLISHED), emptyList(), null, "N/A", emptyList())
        time += 1_000
        recorder.observe(cell(LocalCellTrustState.ESTABLISHED), emptyList(), null, "N/A", emptyList())
        time += 1_000
        recorder.observe(
            cell(LocalCellTrustState.CHANGED, setOf("PCI")), emptyList(), null, "N/A", emptyList(),
            TrustContradictionSignal.TRANSITION
        )
        assertEquals(listOf("OBSERVATION", "OBSERVATION", "TRUST_CONTRADICTION"), store.samples.map { it.event })
    }

    @Test fun `trigger observation preserves real trust contradictions score and confidence`() = runBlocking {
        val store = FakeStore()
        val recorder = ForensicRecorder(store, { 1_000L }, { 1_000L })
        recorder.observe(
            cell(LocalCellTrustState.CHANGED, setOf("PCI", "HANDOVER"), score = 74, confidence = 28.5f),
            emptyList(), null, "N/A", emptyList(), TrustContradictionSignal.TRANSITION
        )
        val payload = JSONObject(store.samples.single().json)
        assertEquals(74, payload.getInt("score"))
        assertEquals(28.5, payload.getDouble("anomalyConfidence"), 0.0)
        val contradictions = payload.getJSONObject("localTrust").getJSONArray("contradictions")
        assertEquals(setOf("PCI", "HANDOVER"), (0 until contradictions.length()).map(contradictions::getString).toSet())
    }

    private fun assertNeutralTrigger() {
        val tracker = TrustContradictionTransitionTracker()
        tracker.observe(cell(LocalCellTrustState.ESTABLISHED))
        val changed = cell(LocalCellTrustState.CHANGED, setOf("PCI"))
        assertEquals(TrustContradictionSignal.TRANSITION, tracker.observe(changed))
        assertFalse(changed.isSuspicious)
        assertFalse(changed.temporalProgress.active)
        assertFalse(changed.localCellTrust.alarmCandidate)
        assertEquals(setOf("observe"), TrustContradictionTransitionTracker::class.java.declaredMethods.map { it.name }.toSet())
    }

    @Test fun `normal anomaly cases keep their existing alarm origin`() = runBlocking {
        val store = FakeStore()
        val recorder = ForensicRecorder(store, { 1_000L }, { 1_000L })
        recorder.observe(cell(LocalCellTrustState.LEARNING, phase = 1), emptyList(), null, "N/A", emptyList())
        assertEquals(ForensicCaseOrigin.ALARM, store.created.single().second)
        assertEquals("ANOMALY_STARTED", store.samples.single().event)
    }

    @Test fun `later normal anomaly promotes silent case instead of duplicating it`() = runBlocking {
        val store = FakeStore()
        var time = 1_000L
        val recorder = ForensicRecorder(store, { time }, { time })
        recorder.observe(cell(LocalCellTrustState.ESTABLISHED), emptyList(), null, "N/A", emptyList())
        time += 1_000
        recorder.observe(
            cell(LocalCellTrustState.CHANGED, setOf("PCI")), emptyList(), null, "N/A", emptyList(),
            TrustContradictionSignal.TRANSITION
        )
        time += 1_000
        recorder.observe(cell(LocalCellTrustState.CHANGED, setOf("PCI"), phase = 1), emptyList(), null, "N/A", emptyList())
        assertEquals(1, store.created.size)
        assertEquals(1, store.promotions)
        assertEquals(ForensicCaseState.CAPTURING, store.states.last())
    }

    // ───────────────────────── v2.8.0 · bloque A · captura al arrancar ─────────────────────────

    @Test fun `on-start contradiction opens an observation case`() = runBlocking {
        val store = FakeStore()
        val recorder = ForensicRecorder(store, { 1_000L }, { 1_000L })
        recorder.observe(
            cell(LocalCellTrustState.CHANGED, setOf("PCI")), emptyList(), null, "N/A", emptyList(),
            TrustContradictionSignal.ON_START
        )
        assertEquals(ForensicCaseOrigin.TRUST_CONTRADICTION, store.created.single().second)
        assertEquals(listOf("TRUST_CONTRADICTION_ON_START"), store.samples.map { it.event })
        assertEquals(ForensicCaseState.POST_CAPTURE, store.states.last())
    }

    @Test fun `on-start case is suppressed when the identity already has a recent case`() = runBlocking {
        val store = FakeStore().apply { recentCaseIdentities += "214-07-31601-100-LTE" }
        val recorder = ForensicRecorder(store, { 1_000L }, { 1_000L })
        recorder.observe(
            cell(LocalCellTrustState.CHANGED, setOf("PCI")), emptyList(), null, "N/A", emptyList(),
            TrustContradictionSignal.ON_START
        )
        assertTrue(store.created.isEmpty())
        assertTrue(store.samples.isEmpty())
    }

    @Test fun `recent case with zero samples does not suppress on-start`() = runBlocking {
        val identity = "214-07-31601-100-LTE"
        val store = FakeStore().apply { emptyRecentCaseIdentities += identity }
        val recorder = ForensicRecorder(store, { 1_000L }, { 1_000L })
        recorder.observe(
            cell(LocalCellTrustState.CHANGED, setOf("PCI")), emptyList(), null, "N/A", emptyList(),
            TrustContradictionSignal.ON_START
        )
        assertEquals(1, store.created.size)
        assertEquals(listOf("TRUST_CONTRADICTION_ON_START"), store.samples.map { it.event })
    }

    @Test fun `dedup window asked of the store is twenty four hours`() = runBlocking {
        val store = FakeStore()
        val now = 5L * 24 * 60 * 60 * 1000
        val recorder = ForensicRecorder(store, { now }, { now })
        recorder.observe(
            cell(LocalCellTrustState.CHANGED, setOf("PCI")), emptyList(), null, "N/A", emptyList(),
            TrustContradictionSignal.ON_START
        )
        assertEquals(now - 24L * 60 * 60 * 1000, store.dedupQueries.single().second)
        assertEquals("214-07-31601-100-LTE", store.dedupQueries.single().first)
    }

    @Test fun `a live transition never consults the dedup window`() = runBlocking {
        val store = FakeStore().apply { recentCaseIdentities += "214-07-31601-100-LTE" }
        val recorder = ForensicRecorder(store, { 1_000L }, { 1_000L })
        recorder.observe(cell(LocalCellTrustState.ESTABLISHED), emptyList(), null, "N/A", emptyList())
        recorder.observe(
            cell(LocalCellTrustState.CHANGED, setOf("PCI")), emptyList(), null, "N/A", emptyList(),
            TrustContradictionSignal.TRANSITION
        )
        assertTrue(store.dedupQueries.isEmpty())
        assertEquals(1, store.created.size)
    }

    @Test fun `on-start contradiction during an anomaly keeps the alarm origin`() = runBlocking {
        val store = FakeStore()
        val recorder = ForensicRecorder(store, { 1_000L }, { 1_000L })
        recorder.observe(
            cell(LocalCellTrustState.CHANGED, setOf("PCI"), phase = 1), emptyList(), null, "N/A",
            emptyList(), TrustContradictionSignal.ON_START
        )
        assertEquals(ForensicCaseOrigin.ALARM, store.created.single().second)
        assertEquals(listOf("TRUST_CONTRADICTION_ON_START_AND_ANOMALY_STARTED"), store.samples.map { it.event })
        assertTrue(store.dedupQueries.isEmpty())
    }

    @Test fun `suppressed on-start still leaves the sample in the prebuffer for a later case`() = runBlocking {
        val store = FakeStore().apply { recentCaseIdentities += "214-07-31601-100-LTE" }
        var time = 1_000L
        val recorder = ForensicRecorder(store, { time }, { time })
        recorder.observe(
            cell(LocalCellTrustState.CHANGED, setOf("PCI")), emptyList(), null, "N/A", emptyList(),
            TrustContradictionSignal.ON_START
        )
        assertTrue(store.created.isEmpty())
        time += 1_000
        recorder.observe(cell(LocalCellTrustState.CHANGED, setOf("PCI"), phase = 1), emptyList(), null, "N/A", emptyList())
        assertEquals(ForensicCaseOrigin.ALARM, store.created.single().second)
        assertEquals(
            listOf("TRUST_CONTRADICTION_ON_START", "ANOMALY_STARTED"),
            store.samples.map { it.event }
        )
    }

    // ───────────────────────── v2.8.0 · bloque B · salud de la escritura ─────────────────────────

    @Test fun `three consecutive failed writes report degraded forensic storage`() = runBlocking {
        val store = FakeStore().apply { failWrites = true }
        val changes = mutableListOf<Boolean>()
        var time = 1_000L
        val recorder = ForensicRecorder(store, { time }, { time }, { changes += it })
        recorder.observe(cell(LocalCellTrustState.LEARNING, phase = 1), emptyList(), null, "N/A", emptyList())
        assertFalse(recorder.isStorageFailing)
        repeat(2) {
            time += 1_000
            recorder.observe(cell(LocalCellTrustState.LEARNING, phase = 1), emptyList(), null, "N/A", emptyList())
        }
        assertTrue(recorder.isStorageFailing)
        assertEquals(listOf(true), changes)
    }

    @Test fun `a successful write clears the failure streak`() = runBlocking {
        val store = FakeStore().apply { failWrites = true }
        val changes = mutableListOf<Boolean>()
        var time = 1_000L
        val recorder = ForensicRecorder(store, { time }, { time }, { changes += it })
        repeat(3) {
            recorder.observe(cell(LocalCellTrustState.LEARNING, phase = 1), emptyList(), null, "N/A", emptyList())
            time += 1_000
        }
        assertTrue(recorder.isStorageFailing)
        store.failWrites = false
        recorder.observe(cell(LocalCellTrustState.LEARNING, phase = 1), emptyList(), null, "N/A", emptyList())
        assertFalse(recorder.isStorageFailing)
        assertEquals(listOf(true, false), changes)
    }

    @Test fun `an isolated failed write does not report degradation`() = runBlocking {
        val store = FakeStore()
        val changes = mutableListOf<Boolean>()
        var time = 1_000L
        val recorder = ForensicRecorder(store, { time }, { time }, { changes += it })
        recorder.observe(cell(LocalCellTrustState.LEARNING, phase = 1), emptyList(), null, "N/A", emptyList())
        store.failWrites = true
        time += 1_000
        recorder.observe(cell(LocalCellTrustState.LEARNING, phase = 1), emptyList(), null, "N/A", emptyList())
        store.failWrites = false
        time += 1_000
        recorder.observe(cell(LocalCellTrustState.LEARNING, phase = 1), emptyList(), null, "N/A", emptyList())
        assertFalse(recorder.isStorageFailing)
        assertTrue(changes.isEmpty())
    }

    @Test fun `failed writes never alter the detector verdict`() = runBlocking {
        val store = FakeStore().apply { failWrites = true }
        val recorder = ForensicRecorder(store, { 1_000L }, { 1_000L })
        val observed = cell(LocalCellTrustState.CHANGED, setOf("PCI"), phase = 1, score = 66)
        repeat(5) { recorder.observe(observed, emptyList(), null, "N/A", emptyList()) }
        assertEquals(66, observed.securityScore)
        assertFalse(observed.localCellTrust.alarmCandidate)
    }

    private class FakeStore : ForensicStore {
        data class Sample(val event: String, val json: String)
        val created = mutableListOf<Pair<Long, ForensicCaseOrigin>>()
        val samples = mutableListOf<Sample>()
        val states = mutableListOf<ForensicCaseState>()
        var finished: ForensicCaseState? = null
        var promotions = 0
        /** Identidades que el disco declara ya cubiertas por un caso reciente. */
        val recentCaseIdentities = mutableSetOf<String>()
        /** Casos recientes creados sin ninguna muestra: no cuentan como evidencia. */
        val emptyRecentCaseIdentities = mutableSetOf<String>()
        /** Cada consulta de deduplicación: identidad y marca temporal mínima pedida. */
        val dedupQueries = mutableListOf<Pair<String, Long>>()
        /** Simula disco lleno o base bloqueada: `insert()` devuelve -1 sin lanzar. */
        var failWrites = false

        override fun createForensicCase(cell: CellData, origin: ForensicCaseOrigin): Long = 1L.also {
            created += it to origin
        }
        override fun insertForensicSample(caseId: Long, wall: Long, elapsed: Long, event: String, json: String): Long {
            if (failWrites) return -1L
            samples += Sample(event, json)
            return samples.size.toLong()
        }
        override fun updateForensicCaseProgress(caseId: Long, cell: CellData) = Unit
        override fun setForensicCaseState(caseId: Long, state: ForensicCaseState) { states += state }
        override fun finishForensicCase(caseId: Long, state: ForensicCaseState) { finished = state }
        override fun promoteForensicCase(caseId: Long) { promotions++ }
        override fun hasRecentForensicCaseFor(identity: String, sinceWallMs: Long): Boolean {
            dedupQueries += identity to sinceWallMs
            // Reproduce el contrato SQL: EXISTS(forensic_samples), no solo la fila del caso.
            if (identity in emptyRecentCaseIdentities) return false
            return identity in recentCaseIdentities
        }
    }
}
