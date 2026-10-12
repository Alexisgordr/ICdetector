package com.alexisgordr.icdetector.storage

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 — createForensicCase: transacción, una fila exacta, ID solo tras confirmar (ver ForensicCaseCreationTest). */
class ForensicCaseAtomicityTest {
    @Test fun `insert and code update share one transaction and the id is returned only after success`() {
        val db = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/storage/CellDbHelper.kt").readText()
        val fn = db.substringAfter("override fun createForensicCase(").substringBefore("\n    /**")
        val begin = fn.indexOf("db.beginTransaction()")
        val insert = fn.indexOf("db.insertOrThrow(TABLE_FORENSIC_CASES")
        val check = fn.indexOf("check(updated == 1)")
        val success = fn.indexOf("db.setTransactionSuccessful()")
        val ret = fn.indexOf("return id")
        val end = fn.indexOf("db.endTransaction()")
        assertTrue(fn, begin in 0 until insert && insert < check && check < success && success < ret && ret < end)
        assertTrue(fn.contains("} finally {\n            db.endTransaction()"))
    }
}
