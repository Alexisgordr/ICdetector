package com.alexisgordr.icdetector.backup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.service.MiniICService
import com.alexisgordr.icdetector.storage.CellDbHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 3.0 — Comportamiento real del bloqueo: se arranca el servicio de verdad con la pregunta pendiente
 * (o con una restauración a medias) y se comprueba que se bloquea y que no crea ni toca la base.
 * También que «Empezar de cero» borra de verdad los datos que hubiera y solo entonces decide.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunGateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = RestoreStartup.prefs(context)
    private val database = context.getDatabasePath(CellDbHelper.DATABASE_NAME)
    private var savedDecision: String? = null

    @Before fun setUp() {
        savedDecision = prefs.getString(FirstRunPolicy.PREF_DECISION, null)
        CellDbHelper.getInstance(context).close()
        context.deleteDatabase(CellDbHelper.DATABASE_NAME)
    }

    @After fun tearDown() {
        RestoreStartup.engine(context).workDir.deleteRecursively()
        prefs.edit().putString(FirstRunPolicy.PREF_DECISION, savedDecision).commit()
        CellDbHelper.getInstance(context).close()
        context.deleteDatabase(CellDbHelper.DATABASE_NAME)
    }

    private fun bindAndGetService(): Pair<MiniICService, ServiceConnection> {
        val latch = CountDownLatch(1)
        var service: MiniICService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as MiniICService.LocalBinder).getService(); latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        assertTrue(context.bindService(Intent(context, MiniICService::class.java), connection, Context.BIND_AUTO_CREATE))
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return service!! to connection
    }

    @Test fun pendingDecisionBlocksTheServiceAndNothingIsWritten() {
        prefs.edit().putString(FirstRunPolicy.PREF_DECISION, FirstRunPolicy.Decision.PENDING.name).commit()
        val (service, connection) = bindAndGetService()
        assertTrue(service.collectionBlocked)
        Thread.sleep(2_000)
        assertFalse("la base no se ha creado", database.exists())
        context.unbindService(connection)
    }

    @Test fun anUnfinishedRestoreAlsoBlocksTheService() {
        prefs.edit().putString(FirstRunPolicy.PREF_DECISION, FirstRunPolicy.Decision.EXISTING.name).commit()
        val engine = RestoreStartup.engine(context)
        File(engine.workDir, RestoreEngine.JOURNAL_NAME).apply { parentFile!!.mkdirs(); writeText("{}") }
        val (service, connection) = bindAndGetService()
        assertTrue(service.collectionBlocked)
        assertFalse(database.exists())
        context.unbindService(connection)
    }

    @Test fun startFreshDeletesExistingDataAndOnlyThenDecides() {
        prefs.edit().putString(FirstRunPolicy.PREF_DECISION, FirstRunPolicy.Decision.PENDING.name)
            .putString("location_mode", "ADAPTIVE").commit()
        CellDbHelper.getInstance(context).logConnection(
            observedAtMs = 1L, netType = "LTE", cid = "1", mnc = "07", tac = "1", mcc = "214", dbm = -90,
            radio = RadioTech.LTE
        )
        File(context.filesDir, "leftover.txt").writeText("x")
        assertTrue(FirstRun.hasForeignData(context))

        assertTrue(FirstRun.startFresh(context))

        assertFalse(database.exists())
        assertFalse(File(context.filesDir, "leftover.txt").exists())
        assertEquals(setOf(FirstRunPolicy.PREF_DECISION), prefs.all.keys)
        assertEquals(FirstRunPolicy.Decision.FRESH, FirstRun.decision(context))
        assertEquals(0, CellDbHelper.getInstance(context).getRecords().size)
    }
}
