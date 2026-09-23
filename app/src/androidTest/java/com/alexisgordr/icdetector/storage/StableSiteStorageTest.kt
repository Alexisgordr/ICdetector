package com.alexisgordr.icdetector.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.core.*
import com.alexisgordr.icdetector.models.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StableSiteStorageTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db:CellDbHelper
    @Before fun before(){context.deleteDatabase(NAME);db=CellDbHelper(context);db.writableDatabase}
    @After fun after(){db.close();context.deleteDatabase(NAME)}

    @Test fun duplicateEventAndSameDayAreIdempotent(){
        val serving=cell("100"); val neighbour=cell("200",false)
        repeat(2){db.recordStableSiteContext("event-1","site",serving,listOf(neighbour),MotionEvidence(MotionState.STATIC_CONFIRMED,120,8f,2f),1_750_000_000_000)}
        val sql=db.readableDatabase
        assertEquals(1,count(sql,"SELECT observations FROM site_cell_evidence WHERE role='SERVING'"))
        assertEquals(1,count(sql,"SELECT COUNT(*) FROM site_cell_days WHERE role='SERVING'"))
        assertEquals(1,count(sql,"SELECT observations FROM site_cell_evidence WHERE role='NEIGHBOUR'"))
        assertEquals(1,count(sql,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='site_shadow_triggers'"))
    }

    @Test fun multiDayEvidenceActivatesSiteAndNovelServingIsHeld(){
        val known=cell("100"); val neighbour=cell("200",false); val base=1_750_000_000_000L
        repeat(7){day-> repeat(5){n->db.recordStableSiteContext("$day-$n","site",known,listOf(neighbour),MotionEvidence(MotionState.STATIC_CONFIRMED,130,8f,2f),base+day*86_400_000L+n*1_000)}}
        val novel=cell("999")
        val decision=db.evaluateStableSiteCandidate("site",novel,known.identityKey,MotionEvidence(MotionState.STATIC_CONFIRMED,130,8f,2f))
        assertEquals(StableSiteFeatureState.ACTIVE,decision.featureState);assertTrue(decision.wouldTrigger);assertTrue(!decision.enforced)
        assertEquals(0,count(db.readableDatabase,"SELECT COUNT(*) FROM site_shadow_triggers"))
        db.applyStableSiteDecision(decision,novel)
        assertEquals(1,count(db.readableDatabase,"SELECT COUNT(*) FROM site_shadow_triggers"))
        val afterRestart=db.evaluateStableSiteCandidate("site",novel,null,MotionEvidence(MotionState.STATIC_CONFIRMED,130,8f,2f))
        assertTrue(afterRestart.wouldTrigger)
        val afterBadFirstFix=db.evaluateStableSiteCandidate("site",novel,novel.identityKey,MotionEvidence(MotionState.STATIC_CONFIRMED,130,8f,2f))
        assertTrue(afterBadFirstFix.wouldTrigger)
        db.resetStableSiteLearning()
        assertEquals(0,count(db.readableDatabase,"SELECT COUNT(*) FROM site_cell_evidence"))
    }

    @Test fun migrationFrom15PreservesLegacyRowsWithoutInventingSiteEvidence(){
        db.close(); context.deleteDatabase(NAME)
        val legacy=context.openOrCreateDatabase(NAME,Context.MODE_PRIVATE,null)
        legacy.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY,cid TEXT)")
        legacy.execSQL("INSERT INTO history(id,cid) VALUES(1,'legacy')")
        legacy.execSQL("CREATE TABLE forensic_cases(id INTEGER PRIMARY KEY)")
        legacy.execSQL("CREATE TABLE forensic_samples(id INTEGER PRIMARY KEY,case_id INTEGER)")
        legacy.execSQL("INSERT INTO forensic_cases(id) VALUES(9)")
        legacy.version=15; legacy.close()
        db=CellDbHelper(context); val upgraded=db.writableDatabase
        assertEquals(1,count(upgraded,"SELECT COUNT(*) FROM history"))
        assertEquals(1,count(upgraded,"SELECT COUNT(*) FROM forensic_cases"))
        assertEquals(0,count(upgraded,"SELECT COUNT(*) FROM site_cell_evidence"))
        assertEquals(0,count(upgraded,"SELECT COUNT(*) FROM site_motion_days"))
        assertEquals(17,upgraded.version)
    }

    @Test fun twentyPollsRemainOneEpisodeAndSurviveHelperRestart(){
        val a=cell("100").identityKey; val x=cell("999").identityKey
        db.observeStableSiteEpisode(x,a,1_000)
        repeat(20){db.observeStableSiteEpisode(x,x,2_000L+it)}
        assertEquals(1,count(db.readableDatabase,"SELECT COUNT(*) FROM site_shadow_triggers WHERE closed_ms IS NULL"))
        db.close();db=CellDbHelper(context);db.writableDatabase
        db.observeStableSiteEpisode(x,null,5_000)
        assertEquals(1,count(db.readableDatabase,"SELECT COUNT(*) FROM site_shadow_triggers WHERE closed_ms IS NULL"))
    }

    @Test fun holdFollowsEpisodeAcrossCanonicalGridAndReleasesByServingCorroboration(){
        val current=cell("999"); val previous=cell("100").identityKey
        db.observeStableSiteEpisode(current.identityKey,previous,1_000)
        val triggerEvidence=StableSiteEvidence("B",StableSiteFeatureState.ACTIVE,7,3,3,0,0,false,7,MotionEvidence(MotionState.STATIC_CONFIRMED,130,8f,2f),35,previous)
        val trigger=StableSiteEvaluator.evaluate(triggerEvidence,enforcementEnabled=true)
        val held=db.applyStableSiteDecision(trigger,current,listOf("A","B"),enforcementEnabled=true)
        assertTrue(held.enforced);assertEquals(1,count(db.readableDatabase,"SELECT COUNT(*) FROM site_holds WHERE active=1"))
        val corroborated=StableSiteDecision(StableSiteFeatureState.ACTIVE,StableSiteNovelty.KNOWN_AT_SITE,false,false,"A",evidence=triggerEvidence.copy(siteKey="A",currentServingDays=3))
        db.applyStableSiteDecision(corroborated,current,listOf("A","B"),enforcementEnabled=true)
        assertEquals(0,count(db.readableDatabase,"SELECT COUNT(*) FROM site_holds WHERE active=1"))
    }

    @Test fun shadowReadyNeverEnforcesEvenWhenFutureFlagIsEnabled(){
        val previous=cell("100").identityKey;val current=cell("999")
        db.observeStableSiteEpisode(current.identityKey,previous,1_000)
        val e=StableSiteEvidence("B",StableSiteFeatureState.SHADOW_READY,5,2,0,0,0,false,7,MotionEvidence(MotionState.STATIC_CONFIRMED,130,8f,2f),20,previous)
        val d=StableSiteEvaluator.evaluate(e,enforcementEnabled=true)
        assertTrue(d.wouldTrigger)
        assertTrue(!db.applyStableSiteDecision(d,current,listOf("A","B"),enforcementEnabled=true).enforced)
        assertEquals(0,count(db.readableDatabase,"SELECT COUNT(*) FROM site_holds WHERE active=1"))
    }

    @Test fun stableSiteExportContainsNeighbourAndEpisodeEvidence(){
        val serving=cell("100");val neighbour=cell("200",false)
        db.recordStableSiteContext("e","site",serving,listOf(neighbour),MotionEvidence(MotionState.STATIC_CONFIRMED,130,8f,2f),1_750_000_000_000)
        db.observeStableSiteEpisode(cell("999").identityKey,serving.identityKey,1_750_000_001_000)
        val files=db.getStableSiteExportFiles()
        assertTrue(files.keys.containsAll(listOf("sites.csv","site_cells.csv","motion.csv","shadow_episodes.csv")))
        assertTrue(files.getValue("site_cells.csv").contains(neighbour.identityKey))
        assertTrue(files.getValue("shadow_episodes.csv").contains(serving.identityKey))
    }

    @Test fun schema16To17MigrationPreservesExistingSubsystems(){
        db.close();context.deleteDatabase(NAME)
        val legacy=context.openOrCreateDatabase(NAME,Context.MODE_PRIVATE,null)
        legacy.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY,cid TEXT)")
        legacy.execSQL("INSERT INTO history VALUES(1,'history-kept')")
        legacy.execSQL("CREATE TABLE forensic_cases(id INTEGER PRIMARY KEY)")
        legacy.execSQL("INSERT INTO forensic_cases VALUES(2)")
        legacy.execSQL("CREATE TABLE forensic_samples(id INTEGER PRIMARY KEY,case_id INTEGER)")
        legacy.execSQL("INSERT INTO forensic_samples VALUES(3,2)")
        legacy.execSQL("CREATE TABLE site_cell_evidence(site_key TEXT,cell_identity TEXT,role TEXT,first_seen_ms INTEGER,last_seen_ms INTEGER,observations INTEGER)")
        legacy.execSQL("INSERT INTO site_cell_evidence VALUES('site','cell','SERVING',1,2,3)")
        legacy.execSQL("CREATE TABLE cell_transitions(from_identity TEXT NOT NULL,to_identity TEXT NOT NULL,observations INTEGER NOT NULL,trusted_observations INTEGER NOT NULL,last_status TEXT NOT NULL,last_seen_ms INTEGER NOT NULL,PRIMARY KEY(from_identity,to_identity))")
        legacy.execSQL("INSERT INTO cell_transitions VALUES('A','B',9,4,'PASSED',123)")
        legacy.version=16;legacy.close()
        db=CellDbHelper(context);val upgraded=db.writableDatabase
        assertEquals(17,upgraded.version)
        assertEquals(1,count(upgraded,"SELECT COUNT(*) FROM history WHERE cid='history-kept'"))
        assertEquals(1,count(upgraded,"SELECT COUNT(*) FROM forensic_cases WHERE id=2"))
        assertEquals(1,count(upgraded,"SELECT COUNT(*) FROM forensic_samples WHERE id=3 AND case_id=2"))
        assertEquals(1,count(upgraded,"SELECT COUNT(*) FROM site_cell_evidence WHERE site_key='site'"))
        assertEquals(9,count(upgraded,"SELECT observations FROM cell_transitions WHERE from_identity='A' AND to_identity='B'"))
        assertEquals(4,count(upgraded,"SELECT trusted_observations FROM cell_transitions WHERE from_identity='A' AND to_identity='B'"))
        assertEquals(0,count(upgraded,"SELECT trip_count FROM cell_transitions WHERE from_identity='A' AND to_identity='B'"))
        assertEquals(1,count(upgraded,"SELECT COUNT(*) FROM cell_transitions WHERE last_trip_id IS NULL"))
        assertEquals(1,count(upgraded,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='mobility_trips'"))
    }

    @Test fun mobilityCommitWritesEachPendingEdgeOnce(){
        val engine=MobilityFamiliarityEngine(db,MobilityFamiliarityConfig(3,2,3,20,30,1_000,100,10_000),true){"trip"}
        engine.observe("A",MotionEvidence(MotionState.MOVING),0)
        repeat(20){engine.observe(if(it%2==0)"B" else "A",MotionEvidence(MotionState.MOVING),(it+1).toLong())}
        engine.observe("C",MotionEvidence(MotionState.MOVING),30)
        engine.observe("C",MotionEvidence(MotionState.STATIC_CONFIRMED),31)
        engine.observe("C",MotionEvidence(MotionState.STATIC_CONFIRMED),52)
        assertEquals(1,count(db.readableDatabase,"SELECT trip_count FROM cell_transitions WHERE from_identity='A' AND to_identity='B'"))
        assertEquals(1,count(db.readableDatabase,"SELECT trip_count FROM cell_transitions WHERE from_identity='B' AND to_identity='A'"))
        assertEquals(0,count(db.readableDatabase,"SELECT COUNT(*) FROM mobility_trips WHERE state='OPEN'"))
    }

    @Test fun tripResumesAfterHelperRestartAndGeometryReadsDoNotOwnLifecycle(){
        val config=MobilityFamiliarityConfig(3,2,3,20,30,1_000,100,10_000)
        val first=MobilityFamiliarityEngine(db,config,true){"persistent-trip"}
        first.observe("A",MotionEvidence(MotionState.MOVING),0)
        first.observe("B",MotionEvidence(MotionState.MOVING),1)
        // Geometry reads the same route table, but opening/closing that UI has no write path to
        // mobility trips. This reproduces its complete storage interaction.
        db.getCellTransitions()
        assertEquals(1,count(db.readableDatabase,"SELECT COUNT(*) FROM mobility_trips WHERE state='OPEN' AND trip_id='persistent-trip'"))
        db.close();db=CellDbHelper(context);db.writableDatabase
        val resumed=MobilityFamiliarityEngine(db,config,true){"must-not-be-created"}
        assertEquals("persistent-trip",resumed.recover(2).tripId)
        resumed.observe("C",MotionEvidence(MotionState.MOVING),3)
        resumed.observe("C",MotionEvidence(MotionState.STATIC_CONFIRMED),4)
        resumed.observe("C",MotionEvidence(MotionState.STATIC_CONFIRMED),25)
        assertEquals(1,count(db.readableDatabase,"SELECT trip_count FROM cell_transitions WHERE from_identity='A' AND to_identity='B'"))
        assertEquals(1,count(db.readableDatabase,"SELECT trip_count FROM cell_transitions WHERE from_identity='B' AND to_identity='C'"))
    }

    @Test fun onOpenDoesNotSilentlyRepairBrokenSchema17(){
        db.close();context.deleteDatabase(NAME)
        val broken=context.openOrCreateDatabase(NAME,Context.MODE_PRIVATE,null)
        broken.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY)");broken.version=17;broken.close()
        db=CellDbHelper(context);val opened=db.writableDatabase
        assertEquals(0,count(opened,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='site_cell_evidence'"))
    }

    private fun cell(cid:String,registered:Boolean=true)=CellData(registered,"4G LTE",cid,"07","31601",-90,"214",radioTech=RadioTech.LTE,pci=10,arfcn=2850)
    private fun count(sqlite:SQLiteDatabase,sql:String)=sqlite.rawQuery(sql,null).use{it.moveToFirst();it.getInt(0)}
    private companion object{const val NAME="icdetector_history.db"}
}
