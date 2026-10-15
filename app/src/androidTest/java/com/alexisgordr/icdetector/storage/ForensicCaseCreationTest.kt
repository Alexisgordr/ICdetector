package com.alexisgordr.icdetector.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.ForensicCaseOrigin
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 3.0 — createForensicCase: insert y case_code en una transacción. Si el update falla o no toca
 * una fila, no queda ningún caso (ni "PENDING") y el error llega al llamador.
 */
@RunWith(AndroidJUnit4::class)
class ForensicCaseCreationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var helper: CellDbHelper

    @Before fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        helper = CellDbHelper(context)
        helper.writableDatabase
    }

    @After fun tearDown() {
        helper.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test fun successCreatesOneCaseWithItsFinalCode() {
        val id = helper.createForensicCase(cell(), ForensicCaseOrigin.ALARM)
        assertTrue(id > 0)
        assertEquals(1, count())
        assertTrue(code(id).matches(Regex("""ICD-\d{4}-\d{2}-\d{2}-\d{4}""")))
        val obs = helper.createForensicCase(cell(), ForensicCaseOrigin.TRUST_CONTRADICTION)
        assertTrue(code(obs).startsWith("ICD-OBS-"))
        assertEquals(0, countPending())
    }

    @Test fun aFailingCodeUpdateRollsBackTheInsertAndPropagates() {
        helper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_code BEFORE UPDATE OF case_code ON forensic_cases " +
                "BEGIN SELECT RAISE(ABORT, 'forced'); END"
        )
        try {
            helper.createForensicCase(cell(), ForensicCaseOrigin.ALARM)
            fail("el error debe llegar al llamador")
        } catch (_: Exception) { }
        assertEquals("ningún caso a medias", 0, count())
    }

    @Test fun anUpdateThatTouchesNoRowRollsBackTheInsertAndPropagates() {
        helper.writableDatabase.execSQL(
            "CREATE TRIGGER skip_code BEFORE UPDATE OF case_code ON forensic_cases " +
                "BEGIN SELECT RAISE(IGNORE); END"
        )
        try {
            helper.createForensicCase(cell(), ForensicCaseOrigin.ALARM)
            fail("0 filas actualizadas debe ser un error")
        } catch (_: IllegalStateException) { }
        assertEquals(0, count())
        // Sin el trigger, la creación vuelve a funcionar y no arrastra nada.
        helper.writableDatabase.execSQL("DROP TRIGGER skip_code")
        helper.createForensicCase(cell(), ForensicCaseOrigin.ALARM)
        assertEquals(1, count())
        assertEquals(0, countPending())
    }

    private fun count() = helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM forensic_cases", null)
        .use { it.moveToFirst(); it.getInt(0) }
    private fun countPending() = helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM forensic_cases WHERE case_code='PENDING'", null)
        .use { it.moveToFirst(); it.getInt(0) }
    private fun code(id: Long) = helper.readableDatabase.rawQuery("SELECT case_code FROM forensic_cases WHERE id=?", arrayOf(id.toString()))
        .use { it.moveToFirst(); it.getString(0) }

    private fun cell() = CellData(isRegistered = true, networkType = "4G LTE", cellId = "1000", mnc = "07", tac = "1",
        dbm = -80, mcc = "214", radioTech = RadioTech.LTE)

    private companion object { const val DATABASE_NAME = "icdetector_history.db" }
}
