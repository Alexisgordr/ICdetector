package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.*
import org.junit.Assert.*
import org.junit.Test

class StableSiteTest {
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
