package com.alexisgordr.icdetector.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — Betas y versión final son la MISMA app (`com.alexisgordr.icdetector`, "ICdetection"), para
 * poder actualizar de una beta a la release. La 3.0 pide instalación limpia; después, cada
 * versión actualiza a la anterior.
 */
class AppIdentityTest {
    private fun file(path: String) =
        listOf(File(path), File("app/$path"), File("../$path")).first { it.exists() }.readText()

    @Test fun `every build keeps the stable application id`() {
        val gradle = file("build.gradle.kts").takeIf { it.contains("applicationId") }
            ?: file("app/build.gradle.kts")
        assertTrue(gradle.contains("applicationId = \"com.alexisgordr.icdetector\""))
        assertFalse(gradle.contains("applicationIdSuffix"))
    }

    @Test fun `the launcher name is ICdetection`() {
        val manifest = file("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:label=\"ICdetection\""))
    }
}
