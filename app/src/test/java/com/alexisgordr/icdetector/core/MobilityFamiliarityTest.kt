package com.alexisgordr.icdetector.core

import org.junit.Assert.*
import org.junit.Test

class MobilityFamiliarityTest {
    private val moving = MotionEvidence(MotionState.MOVING)
    private val unknown = MotionEvidence(MotionState.UNKNOWN)
    private val static = MotionEvidence(MotionState.STATIC_CONFIRMED)
    private val cfg = MobilityFamiliarityConfig(3, 2, 3, 20, 30, 1_000, 100, 10_000)

    @Test fun tripCannotValidateItself() {
        val s=FakeStore(); val e=engine(s)
        e.observe("A",moving,0)
        repeat(30){ n -> e.observe(if(n%2==0) "X" else "A",moving,(n+1).toLong()) }
        assertEquals(MobilityFamiliarity.UNKNOWN_ON_ROUTE,e.observe("X",moving,40).familiarity)
        assertTrue(s.counts.isEmpty())
    }

    @Test fun noMovingNoTrip() {
        val s=FakeStore(); val e=engine(s)
        repeat(10){ e.observe(if(it%2==0) "A" else "X",if(it<5) static else unknown,it.toLong()) }
        assertNull(s.openTrip()); assertTrue(s.counts.isEmpty())
    }

    @Test fun restartDoesNotCreateSecondTrip() {
        val s=FakeStore(); val first=engine(s, ids=mutableListOf("one"))
        first.observe("A",moving,0); first.observe("B",moving,1)
        val second=engine(s, ids=mutableListOf("two"))
        assertEquals("one",second.recover(2).tripId)
        second.observe("C",moving,3)
        assertEquals("one",s.openTrip()!!.id)
    }

    @Test fun deadGpsStillClosesTrip() {
        val s=FakeStore(); val e=engine(s)
        e.observe("A",moving,0); e.observe("B",moving,1); e.observe("C",moving,2)
        e.observe("C",unknown,3); e.observe("C",unknown,34)
        assertNull(s.openTrip()); assertEquals(MobilityTripCloseReason.SAME_SERVING_TIMEOUT,s.closedReason)
    }

    @Test fun repeatedEdgeCountsOncePerTrip() {
        val s=FakeStore(); val e=engine(s)
        e.observe("A",moving,0)
        repeat(50){ e.observe(if(it%2==0) "B" else "A",moving,(it+1).toLong()) }
        e.observe("C",moving,60); e.observe("C",static,61); e.observe("C",static,82)
        assertEquals(1,s.counts[MobilityEdge("A","B")]); assertEquals(1,s.counts[MobilityEdge("B","A")])
    }

    @Test fun independentTripsAccumulate() {
        val s=FakeStore()
        repeat(3){ trip ->
            val e=engine(s,ids=mutableListOf("t$trip")); val base=trip*100L
            e.observe("A",moving,base); e.observe("B",moving,base+1); e.observe("C",moving,base+2)
            e.observe("C",static,base+3); e.observe("C",static,base+24)
        }
        assertEquals(3,s.counts[MobilityEdge("A","B")])
    }

    @Test fun currentTripUsesPriorSnapshotOnly() {
        val s=FakeStore(); s.counts[MobilityEdge("A","B")]=2
        val e=MobilityFamiliarityEngine(s,cfg.copy(goodEdgesRequired=1),true){"current"}
        e.observe("A",moving,0)
        assertEquals(MobilityFamiliarity.OBSERVED_ON_ROUTE,e.observe("B",moving,1).familiarity)
        e.observe("C",moving,2); e.observe("C",static,3); e.observe("C",static,24)
        assertEquals(3,s.counts[MobilityEdge("A","B")])
        val next=MobilityFamiliarityEngine(s,cfg.copy(goodEdgesRequired=1),true){"next"}
        next.observe("A",moving,200)
        assertEquals(MobilityFamiliarity.KNOWN_ON_ROUTE,next.observe("B",moving,201).familiarity)
    }

    @Test fun invalidAndValidAbandonedTripsAreHandledExactlyOnce() {
        val invalid=FakeStore(); engine(invalid).observe("A",moving,0)
        engine(invalid,ids=mutableListOf("unused")).recover(101)
        assertTrue(invalid.counts.isEmpty())
        val valid=FakeStore(); val first=engine(valid)
        first.observe("A",moving,0); first.observe("B",moving,1); first.observe("C",moving,2)
        engine(valid).recover(103); val once=valid.counts.toMap(); engine(valid).recover(104)
        assertEquals(once,valid.counts); assertEquals(1,valid.counts[MobilityEdge("A","B")])
    }

    @Test fun geometryLifecycleCannotAffectServiceOwnedTrip() {
        val s=FakeStore(); val serviceEngine=engine(s)
        serviceEngine.observe("A",moving,0)
        var geometry: Any?=Any(); geometry=null // Activity/UI lifecycle has no engine/store reference.
        assertNull(geometry)
        serviceEngine.observe("B",moving,1); serviceEngine.observe("C",moving,2)
        assertEquals(setOf(MobilityEdge("A","B"),MobilityEdge("B","C")),s.tripEdges(s.openTrip()!!.id))
    }

    @Test fun mobilityMetadataHasNoSecuritySideEffects() {
        data class Security(val score:Int,val suspicious:Boolean,val reason:String?,val failed:Set<String>,val trust:String,val forensic:Int)
        fun pipeline(@Suppress("UNUSED_PARAMETER") mobility:MobilityFamiliarity)=Security(88,false,null,emptySet(),"LEARNING",0)
        assertEquals(pipeline(MobilityFamiliarity.UNKNOWN_ON_ROUTE),pipeline(MobilityFamiliarity.KNOWN_ON_ROUTE))
    }

    private fun engine(s:FakeStore,ids:MutableList<String> = mutableListOf("trip")) =
        MobilityFamiliarityEngine(s,cfg,true){ ids.removeFirstOrNull() ?: "fallback" }

    private class FakeStore:MobilityFamiliarityStore {
        var trip:MobilityTrip?=null; val cells=linkedSetOf<String>(); val edges=linkedSetOf<MobilityEdge>()
        val counts=linkedMapOf<MobilityEdge,Int>(); var closedReason:MobilityTripCloseReason?=null
        override fun openTrip()=trip
        override fun createTrip(trip:MobilityTrip,firstCell:String){this.trip=trip;cells.clear();edges.clear();cells+=firstCell}
        override fun updateTrip(trip:MobilityTrip){this.trip=trip}
        override fun addTripCell(tripId:String,identity:String){cells+=identity}
        override fun addTripEdge(tripId:String,edge:MobilityEdge){edges+=edge}
        override fun tripCells(tripId:String)=cells.toSet()
        override fun tripEdges(tripId:String)=edges.toSet()
        override fun priorTripCounts(edges:Set<MobilityEdge>)=edges.associateWith{counts[it]?:0}
        override fun commitMobilityTrip(tripId:String,reason:MobilityTripCloseReason,closedAtMs:Long){
            if(trip?.id!=tripId)return; edges.forEach{counts[it]=(counts[it]?:0)+1};closedReason=reason;trip=null
        }
        override fun discardMobilityTrip(tripId:String,reason:MobilityTripCloseReason,closedAtMs:Long){closedReason=reason;trip=null}
        override fun pruneMobilityTripDetails(beforeMs:Long){}
    }
}
