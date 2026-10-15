package com.alexisgordr.icdetector.backup

import com.alexisgordr.icdetector.backup.BackupPolicy.Rejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0 — Qué copias se aceptan. El corte de la campaña v2.10.x es metodológico, no técnico. */
class BackupPolicyTest {

    private val current = 21
    private fun manifest(
        version: String = "3.0.0-beta4+d0e75dd",
        schema: Int = 21,
        format: Int = BackupFormat.FORMAT,
        appId: String = BackupFormat.APP_ID,
        bytes: Long = 4096
    ) = BackupManifest(format, appId, version, schema, "2026-10-11T10:00:00Z", "ab".repeat(32), bytes)

    @Test fun `every 3_0 beta and later version is accepted explicitly`() {
        listOf("3.0.0-beta1", "3.0.0-beta4", "3.0.0-beta4+d0e75dd", "3.0.0-beta4+d0e75dd-dirty",
            "3.0.0-beta4+nogit", "3.0.0", "3.0.1", "3.1.0", "4.0.0").forEach {
            assertTrue(it, BackupPolicy.isThreeZeroOrLater(it))
            assertNull(it, BackupPolicy.check(manifest(version = it), current))
        }
    }

    @Test fun `campaign versions are rejected even if the database could be migrated`() {
        listOf("2.10.10", "2.10.5", "2.9.0", "2.99.99", "1.0.0").forEach {
            assertFalse(it, BackupPolicy.isThreeZeroOrLater(it))
            // Incluso con un esquema de la 3.0: la versión de origen decide.
            assertEquals(it, Rejection.CAMPAIGN_VERSION, BackupPolicy.check(manifest(version = it, schema = 21), current))
        }
    }

    @Test fun `odd versions are not guessed`() {
        listOf("3", "3.0", "3.0.0.1", "v3.0.0", "3.x.0", " ", "beta").forEach {
            assertFalse(it, BackupPolicy.isThreeZeroOrLater(it))
        }
        assertEquals(Rejection.MISSING_VERSION, BackupPolicy.check(manifest(version = ""), current))
    }

    @Test fun `schema outside the 3_0 range is rejected, also a future one`() {
        assertEquals(Rejection.UNSUPPORTED_SCHEMA, BackupPolicy.check(manifest(schema = 19), current))
        assertNull(BackupPolicy.check(manifest(schema = 20), current))
        assertEquals(Rejection.FUTURE_SCHEMA, BackupPolicy.check(manifest(schema = 22), current))
    }

    @Test fun `unknown formats, other apps and missing manifests are rejected`() {
        assertEquals(Rejection.NOT_A_BACKUP, BackupPolicy.check(null, current))
        assertEquals(Rejection.UNKNOWN_FORMAT, BackupPolicy.check(manifest(format = 2), current))
        assertEquals(Rejection.UNKNOWN_FORMAT, BackupPolicy.check(manifest(format = 0), current))
        assertEquals(Rejection.NOT_ICDETECTION, BackupPolicy.check(manifest(appId = "com.example"), current))
        assertEquals(Rejection.TOO_LARGE, BackupPolicy.check(manifest(bytes = 0), current))
    }

    @Test fun `the manifest survives a round trip and a broken one is not a manifest`() {
        val m = manifest()
        assertEquals(m, BackupManifest.parse(m.toJson()))
        assertNull(BackupManifest.parse("{}"))
        assertNull(BackupManifest.parse("not json"))
    }
}
