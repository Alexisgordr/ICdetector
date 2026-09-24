package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.*
import org.junit.Assert.*
import org.junit.Test

class StableSiteTest {
    @Test fun `rf neighbour fingerprint requires radio carrier and pci without pretending cid`() {
        val useful = CellData(false,"4G LTE","N/A","07","31601",-100,"214",radioTech=RadioTech.LTE,pci=10,arfcn=2850)
        val fingerprint = StableSiteNeighbourEvidence.rfFingerprint(useful)
        assertEquals("RFCTX:v1:LTE:2850:10", fingerprint?.value)
        assertFalse(fingerprint!!.value.contains("31601"))
        assertNull(StableSiteNeighbourEvidence.rfFingerprint(useful.copy(pci=null)))
        assertNull(StableSiteNeighbourEvidence.rfFingerprint(useful.copy(arfcn=null)))
        assertNull(StableSiteNeighbourEvidence.rfFingerprint(useful.copy(cellId="123")))
    }

    @Test fun `same pci differs by carrier and radio`() {
        val base = CellData(false,"4G LTE","N/A","07","N/A",-100,"214",radioTech=RadioTech.LTE,pci=10,arfcn=2850)
        val a = StableSiteNeighbourEvidence.rfFingerprint(base)!!.value
        val b = StableSiteNeighbourEvidence.rfFingerprint(base.copy(arfcn=2851))!!.value
        val c = StableSiteNeighbourEvidence.rfFingerprint(base.copy(radioTech=RadioTech.NR))!!.value
        assertNotEquals(a,b); assertNotEquals(a,c)
    }

    @Test fun `rf ranges follow the physical radio and accept channel zero`() {
        fun fp(radio:RadioTech,arfcn:Int,pci:Int)=StableSiteNeighbourEvidence.rfFingerprint(
            CellData(false,radio.name,"N/A","N/A","N/A",-100,"N/A",radioTech=radio,arfcn=arfcn,pci=pci)
        )
        assertNotNull(fp(RadioTech.LTE,0,0));assertNotNull(fp(RadioTech.LTE,262_143,503))
        assertNull(fp(RadioTech.LTE,262_144,503));assertNull(fp(RadioTech.LTE,100,504))
        assertNotNull(fp(RadioTech.NR,0,0));assertNotNull(fp(RadioTech.NR,3_279_165,1007))
        assertNull(fp(RadioTech.NR,3_279_166,1007));assertNull(fp(RadioTech.NR,100,1008))
        assertNotNull(fp(RadioTech.UMTS,0,0));assertNotNull(fp(RadioTech.UMTS,16_383,511))
        assertNull(fp(RadioTech.UMTS,16_384,511));assertNull(fp(RadioTech.UMTS,100,512))
        assertNull(fp(RadioTech.GSM,0,0));assertNull(fp(RadioTech.UNKNOWN,0,0))
        listOf(-1,Int.MAX_VALUE).forEach { unavailable ->
            assertNull(fp(RadioTech.LTE,unavailable,10));assertNull(fp(RadioTech.LTE,100,unavailable))
        }
        assertNull(fp(RadioTech.LTE,100,504)) // legal NR PCI, illegal LTE PCI
        assertNotEquals(fp(RadioTech.LTE,100,10)!!.value,fp(RadioTech.NR,100,10)!!.value)
    }

    @Test fun `maturity accepts three independent full or rf days but never absent neighbours`() {
        assertEquals(StableSiteFeatureState.ACTIVE, StableSiteMaturityPolicy.evaluate(7,3,3,0,30).state)
        val rf = StableSiteMaturityPolicy.evaluate(7,3,0,3,30)
        assertEquals(StableSiteFeatureState.ACTIVE,rf.state)
        assertEquals(NeighbourEvidenceCapability.RF_NEIGHBOUR_ONLY,rf.capability)
        assertEquals(NeighbourEvidenceCapability.RF_NEIGHBOUR_ONLY, StableSiteMaturityPolicy.evaluate(7,3,1,3,30).capability)
        val absent = StableSiteMaturityPolicy.evaluate(30,20,0,0,5000)
        assertEquals(StableSiteFeatureState.SHADOW_READY,absent.state)
        assertEquals("NO_USABLE_NEIGHBOUR_DATA",absent.reason)
        assertEquals(StableSiteFeatureState.SHADOW_READY, StableSiteMaturityPolicy.evaluate(7,3,0,1,50_000).state)
    }

    @Test fun `timing advance stub does not participate in neighbour capability`() {
        val cell = CellData(false,"4G LTE","N/A","07","N/A",-100,"214",timingAdvance=0,
            timingAdvanceUnit=TimingAdvanceUnit.STUB_ZERO,radioTech=RadioTech.LTE,pci=22,arfcn=1800)
        assertEquals(NeighbourEvidenceCapability.RF_NEIGHBOUR_ONLY,StableSiteNeighbourEvidence.diagnostic(listOf(cell)).capability)
    }

    @Test fun `first fix and bad accuracy are unknown`() {
        assertEquals(MotionState.UNKNOWN, MotionClassifier().observe(fix(0)).state)
        assertEquals(MotionState.UNKNOWN, MotionClassifier().observe(fix(0, accuracy = 80f)).state)
    }

    @Test fun `precise stable fixes become confirmed after two minutes`() {
        val c = MotionClassifier()
        assertEquals(MotionState.UNKNOWN, c.observe(fix(0)).state)
        assertEquals(MotionState.UNKNOWN, c.observe(fix(30_000, lat = 42.80001)).state)
        assertEquals(MotionState.UNKNOWN, c.observe(fix(60_000, lat = 42.80002)).state)
        assertEquals(MotionState.UNKNOWN, c.observe(fix(90_000, lat = 42.80002)).state)
        assertEquals(MotionState.STATIC_CONFIRMED, c.observe(fix(120_000, lat = 42.80003)).state)
    }

    @Test fun `isolated poor fix abstains without erasing good static window`() {
        val c = MotionClassifier()
        c.observe(fix(0)); c.observe(fix(30_000))
        val poor = c.observe(fix(60_000, accuracy = 70f))
        assertEquals(MotionState.UNKNOWN, poor.state)
        assertEquals(MotionReason.ACCURACY_POOR, poor.reason)
        c.observe(fix(90_000))
        assertEquals(MotionState.STATIC_CONFIRMED, c.observe(fix(120_000)).state)
    }

    @Test fun `long gap invalidates static window`() {
        val c = MotionClassifier()
        c.observe(fix(0)); c.observe(fix(30_000))
        assertEquals(MotionState.UNKNOWN, c.current(120_001).state)
        assertEquals(MotionReason.NO_RECENT_FIX, c.current(120_001).reason)
        assertEquals(MotionState.UNKNOWN, c.observe(fix(150_000)).state)
        assertEquals(MotionReason.FIRST_FIX, c.current(150_001).reason)
    }

    @Test fun `repeated and out of order fixes do not inflate duration`() {
        val c = MotionClassifier()
        val one = fix(0)
        repeat(20) { assertEquals(MotionState.UNKNOWN, c.observe(one).state) }
        assertEquals(0L, c.current(one.timeMs).durationSeconds)
        c.observe(fix(30_000))
        val before = c.current(30_001)
        c.observe(fix(10_000))
        assertEquals(before, c.current(30_001))
    }

    @Test fun `real movement wins after an isolated poor fix`() {
        val c = MotionClassifier()
        c.observe(fix(0)); c.observe(fix(30_000, accuracy = 70f))
        assertEquals(MotionState.MOVING, c.observe(fix(60_000, lat = 42.802, speed = 8f)).state)
    }

    @Test fun `poor fixes never become static`() {
        val c = MotionClassifier()
        listOf(0L, 30_000L, 60_000L, 90_000L, 120_000L).forEach {
            assertEquals(MotionState.UNKNOWN, c.observe(fix(it, accuracy = 70f)).state)
        }
    }

    @Test fun `real movement is moving and jitter is not`() {
        val moving = MotionClassifier(); moving.observe(fix(0))
        assertEquals(MotionState.MOVING, moving.observe(fix(30_000, lat = 42.802, speed = 8f)).state)
        val jitter = MotionClassifier(); jitter.observe(fix(0, accuracy = 20f))
        assertNotEquals(MotionState.MOVING, jitter.observe(fix(30_000, lat = 42.8001, accuracy = 20f)).state)
    }

    @Test fun `site key hides coordinates and tolerates ordinary jitter`() {
        val a = StableSiteKey.from(42.82670, -1.64550)
        val b = StableSiteKey.from(42.82675, -1.64545)
        assertTrue(StableSiteKey.candidates(42.82670, -1.64550).intersect(StableSiteKey.candidates(42.82675, -1.64545).toSet()).isNotEmpty())
        assertFalse(a.contains("42.82670")); assertEquals(20, a.length)
        assertNotEquals(a, StableSiteKey.from(42.83670, -1.64550))
    }

    @Test fun `overlapping site grids survive jitter across a base grid corner`() {
        val southWest = StableSiteKey.candidates(-0.0001, -0.0001).toSet()
        val northEast = StableSiteKey.candidates(0.0001, 0.0001).toSet()
        assertTrue(southWest.intersect(northEast).isNotEmpty())
    }

    @Test fun `new installation and travel cannot trigger`() {
        val bootstrap = evidence(StableSiteFeatureState.BOOTSTRAP, currentDays = 0, previousDays = 0, motion = MotionState.STATIC_CONFIRMED)
        assertFalse(StableSiteEvaluator.evaluate(bootstrap).wouldTrigger)
        val moving = evidence(StableSiteFeatureState.ACTIVE, currentDays = 0, previousDays = 7, motion = MotionState.MOVING)
        assertFalse(StableSiteEvaluator.evaluate(moving).wouldTrigger)
    }

    @Test fun `evaluator reports shadow verdict and only marks active decision enforceable`() {
        val shadow = StableSiteEvaluator.evaluate(evidence(StableSiteFeatureState.SHADOW_READY, 0, 7, MotionState.STATIC_CONFIRMED))
        assertTrue(shadow.wouldTrigger); assertFalse(shadow.enforced)
        val active = StableSiteEvaluator.evaluate(evidence(StableSiteFeatureState.ACTIVE, 0, 7, MotionState.STATIC_CONFIRMED))
        assertTrue(active.wouldTrigger); assertFalse(active.enforced)
        val seenOnce = StableSiteEvaluator.evaluate(evidence(StableSiteFeatureState.ACTIVE, 1, 7, MotionState.STATIC_CONFIRMED))
        assertTrue(seenOnce.wouldTrigger); assertNotEquals(StableSiteNovelty.KNOWN_AT_SITE, seenOnce.novelty)
        assertTrue(StableSiteEvaluator.evaluate(evidence(StableSiteFeatureState.ACTIVE, 0, 7, MotionState.STATIC_CONFIRMED), enforcementEnabled = true).enforced)
    }

    @Test fun `canonical selector chooses maturity before trigger verdict`() {
        val matureKnown=StableSiteDecision(StableSiteFeatureState.ACTIVE,StableSiteNovelty.KNOWN_AT_SITE,false,false,"A",evidence=evidence(StableSiteFeatureState.ACTIVE,3,7,MotionState.STATIC_CONFIRMED).copy(servingObservations=50))
        val immatureNovel=StableSiteDecision(StableSiteFeatureState.SHADOW_READY,StableSiteNovelty.SITE_NOVEL,true,false,"B",evidence=evidence(StableSiteFeatureState.SHADOW_READY,0,7,MotionState.STATIC_CONFIRMED).copy(servingObservations=100))
        assertEquals("A",StableSiteCandidateSelector.select(listOf(immatureNovel,matureKnown))?.siteKey)
        val matureNovel=immatureNovel.copy(featureState=StableSiteFeatureState.ACTIVE,siteKey="C",evidence=immatureNovel.evidence?.copy(featureState=StableSiteFeatureState.ACTIVE,siteServingDays=8,servingObservations=60))
        assertEquals("C",StableSiteCandidateSelector.select(listOf(matureKnown,matureNovel))?.siteKey)
    }

    @Test fun `canonical selector tie is deterministic`() {
        val e=evidence(StableSiteFeatureState.ACTIVE,0,7,MotionState.STATIC_CONFIRMED)
        val a=StableSiteDecision(StableSiteFeatureState.ACTIVE,siteKey="aaa",evidence=e.copy(siteKey="aaa"))
        val b=StableSiteDecision(StableSiteFeatureState.ACTIVE,siteKey="bbb",evidence=e.copy(siteKey="bbb"))
        assertEquals(StableSiteCandidateSelector.select(listOf(a,b)),StableSiteCandidateSelector.select(listOf(b,a)))
    }

    @Test fun `hold changes trust state without changing detector verdict or score`() {
        val cell = CellData(true,"4G LTE","100","07","31601",-90,"214",radioTech=RadioTech.LTE,pci=10,arfcn=2850)
        val evidence = LocalCellTrustEvidence(80,35,14,336,30,setOf(10),setOf(2850),mapOf(2850 to setOf(10)),rfObservations=60,trustedTransitions=20)
        val decision = StableSiteDecision(StableSiteFeatureState.ACTIVE, StableSiteNovelty.GLOBALLY_NOVEL, true, true, "abc", "hold")
        val out = LocalCellTrustEngine.apply(cell,evidence,decision)
        assertEquals(LocalCellTrustState.SITE_UNVERIFIED,out.localCellTrust.state)
        assertEquals(cell.securityScore,out.securityScore); assertEquals(cell.isSuspicious,out.isSuspicious)
        assertFalse(out.localCellTrust.alarmCandidate)
    }

    @Test fun `later identity contradiction still becomes changed`() {
        val cell = CellData(true,"4G LTE","100","07","31601",-90,"214",radioTech=RadioTech.LTE,pci=311,arfcn=2850)
        val e = LocalCellTrustEvidence(80,35,14,336,30,setOf(10),setOf(2850),mapOf(2850 to setOf(10)),rfObservations=60,trustedTransitions=20)
        val hold = StableSiteDecision(StableSiteFeatureState.ACTIVE, StableSiteNovelty.SITE_NOVEL,true,true,"abc","hold")
        assertEquals(LocalCellTrustState.CHANGED, LocalCellTrustEngine.apply(cell,e,hold).localCellTrust.state)
    }

    private fun fix(t:Long,lat:Double=42.8,accuracy:Float=8f,speed:Float?=0f)=MotionFix(lat,-1.6,t+1,accuracy,speed)
    private fun evidence(state:StableSiteFeatureState,currentDays:Int,previousDays:Int,motion:MotionState)=StableSiteEvidence("site",state,7,3,3,currentDays,0,false,previousDays,MotionEvidence(motion,130,8f,3f))
}
