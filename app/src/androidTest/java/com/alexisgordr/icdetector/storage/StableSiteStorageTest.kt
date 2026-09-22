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
        assertEquals(16,upgraded.version)
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

    @Test fun onOpenDoesNotSilentlyRepairBrokenSchema16(){
        db.close();context.deleteDatabase(NAME)
        val broken=context.openOrCreateDatabase(NAME,Context.MODE_PRIVATE,null)
        broken.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY)");broken.version=16;broken.close()
        db=CellDbHelper(context);val opened=db.writableDatabase
        assertEquals(0,count(opened,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='site_cell_evidence'"))
    }

    private fun cell(cid:String,registered:Boolean=true)=CellData(registered,"4G LTE",cid,"07","31601",-90,"214",radioTech=RadioTech.LTE,pci=10,arfcn=2850)
    private fun count(sqlite:SQLiteDatabase,sql:String)=sqlite.rawQuery(sql,null).use{it.moveToFirst();it.getInt(0)}
    private companion object{const val NAME="icdetector_history.db"}
}
