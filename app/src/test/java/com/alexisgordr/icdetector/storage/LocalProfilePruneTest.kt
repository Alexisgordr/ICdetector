package com.alexisgordr.icdetector.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * 3.0 — La retención del perfil local, en SQLite real y con el corte enlazado como TEXTO, como hace
 * SQLiteDatabase.delete. Antes la condición era `COALESCE(...) < ?` y borraba TODAS las referencias.
 */
class LocalProfilePruneTest {
    private lateinit var db: Connection
    private val day = 86_400_000L
    private val now = 1_790_000_000_000L

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        SchemaV22.STATEMENTS.forEach { sql -> db.createStatement().use { it.execute(sql) } }
    }

    @After fun close() = db.close()

    private fun profile(key: String, lastEvidenceMs: Long?, updatedAtMs: Long) =
        db.prepareStatement("INSERT INTO ${SchemaV22.TABLE_PROFILE} (context_key, metric, radio, device_model, " +
            "algorithm_version, maturity, last_evidence_ms, updated_at_ms) VALUES (?, 'RSRP', 'LTE', 'm', 1, 'LEARNING', ?, ?)").use { st ->
            st.setString(1, key)
            if (lastEvidenceMs == null) st.setNull(2, java.sql.Types.INTEGER) else st.setLong(2, lastEvidenceMs)
            st.setLong(3, updatedAtMs)
            st.executeUpdate()
        }

    /** Lo que hace pruneProfiles: DELETE con la condición de producción y el corte como texto. */
    private fun prune(where: String, cutoffMs: Long): Int =
        db.prepareStatement("DELETE FROM ${SchemaV22.TABLE_PROFILE} WHERE $where").use { st ->
            st.setString(1, cutoffMs.toString())
            st.executeUpdate()
        }

    private fun remaining(): Int = db.createStatement().use { st ->
        st.executeQuery("SELECT COUNT(*) FROM ${SchemaV22.TABLE_PROFILE}").use { it.next(); it.getInt(1) }
    }

    @Test fun `recent references survive a prune with an older cutoff`() {
        profile("a", lastEvidenceMs = now - day, updatedAtMs = now)
        profile("b", lastEvidenceMs = null, updatedAtMs = now - 2 * day)
        assertEquals(0, prune(SchemaV22.PRUNE_PROFILES_WHERE, cutoffMs = now - 120 * day))
        assertEquals(2, remaining())
    }

    @Test fun `only references older than the cutoff are deleted`() {
        profile("old", lastEvidenceMs = now - 200 * day, updatedAtMs = now)
        profile("old-no-evidence", lastEvidenceMs = null, updatedAtMs = now - 200 * day)
        profile("recent", lastEvidenceMs = now - day, updatedAtMs = now - 300 * day)
        assertEquals(2, prune(SchemaV22.PRUNE_PROFILES_WHERE, cutoffMs = now - 120 * day))
        assertEquals(1, remaining())
    }

    @Test fun `the old condition deleted every reference`() {
        profile("a", lastEvidenceMs = now - day, updatedAtMs = now)
        profile("b", lastEvidenceMs = now - 2 * day, updatedAtMs = now)
        assertEquals("reproducción del bug", 2, prune("COALESCE(last_evidence_ms, updated_at_ms) < ?", cutoffMs = now - 120 * day))
    }

    @Test fun `the store uses the shared condition`() {
        val src = listOf("src/main/java/com/alexisgordr/icdetector/storage/LocalProfileStore.kt",
            "app/src/main/java/com/alexisgordr/icdetector/storage/LocalProfileStore.kt").map(::File).first { it.exists() }.readText()
        assertTrue(src.contains("db.delete(P, SchemaV22.PRUNE_PROFILES_WHERE, arrayOf(cutoffMs.toString()))"))
    }
}
