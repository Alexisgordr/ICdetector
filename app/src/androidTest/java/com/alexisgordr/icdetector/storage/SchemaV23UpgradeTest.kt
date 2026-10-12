package com.alexisgordr.icdetector.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.core.profile.MetricProfile
import com.alexisgordr.icdetector.core.profile.ProfileMaturity
import com.alexisgordr.icdetector.core.profile.ProfileMetric
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 3.0 — Actualización real 22 -> 23 con el SQLite de Android: referencias anteriores conservadas. */
@RunWith(AndroidJUnit4::class)
class SchemaV23UpgradeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "schema23_upgrade_test.db"
    private var helper: CellDbHelper? = null

    @Before fun createSchema22() {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            SchemaV20UpgradeTest.V19.forEach(db::execSQL)
            SchemaV20.STATEMENTS.forEach(db::execSQL)
            SchemaV21.STATEMENTS.forEach(db::execSQL)
            SchemaV22.STATEMENTS.forEach(db::execSQL)
            db.execSQL("INSERT INTO local_profile (context_key, metric, radio, device_model, algorithm_version, revision, " +
                "mean, maturity, updated_at_ms) VALUES ('214-07-1-100-LTE', 'SIGNAL_DBM', 'LTE', 'google/pixel 8', 1, 80, -80.0, 'MATURE', 1)")
            db.version = 22
        }
    }

    @After fun after() { helper?.close(); context.deleteDatabase(name) }

    @Test fun upgradeKeepsReferencesAndStoresTheNewFields() {
        val h = CellDbHelper(context, context.getDatabasePath(name).absolutePath).also { helper = it }
        val db = h.writableDatabase
        assertEquals(CellDbHelper.DATABASE_VERSION, db.version)
        val legacy = LocalProfileStore.profiles(db, "214-07-1-100-LTE").getValue(ProfileMetric.SIGNAL_DBM)
        assertEquals(80L, legacy.revision)
        assertEquals(MetricProfile.LEGACY_GENERATION, legacy.generation)

        val p = MetricProfile("214-07-1-200-LTE", ProfileMetric.SIGNAL_DBM, RadioTech.LTE, "google/pixel 8", 2, 3,
            -90.0, 4.0, 3, 0, 3, 0, 1, "2026-10-01", 3, -90.0, 1L, 2L, 2L, ProfileMaturity.LEARNING, null, 2L,
            generation = "g1", dayStartVariance = 9.0, lastSeenMs = 2L)
        LocalProfileStore.upsert(db, p)
        assertEquals(p, LocalProfileStore.profiles(db, p.contextKey)[ProfileMetric.SIGNAL_DBM])
    }
}
