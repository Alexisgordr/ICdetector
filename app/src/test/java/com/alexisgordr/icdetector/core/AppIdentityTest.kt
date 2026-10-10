package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — Betas y versión final son la MISMA app (`com.alexisgordr.icdetector`, "ICdetection"), para
 * poder actualizar de una beta a la release. La 3.0 pide instalación limpia; después, cada
 * versión actualiza a la anterior.
 *
 * Las betas no se publican: todas usan el versionCode 39, el siguiente a la 2.10.10 (38), que es
 * el que conserva la 3.0. Las notas de la tienda (F-Droid) van por versionCode: no puede quedar
 * una nota de beta con un número mayor, porque la cogería una 3.0.x futura.
 */
class AppIdentityTest {
    private fun locate(path: String) =
        listOf(File(path), File("app/$path"), File("../$path")).first { it.exists() }

    private fun file(path: String) = locate(path).readText()

    private fun gradle() = file("build.gradle.kts").takeIf { it.contains("applicationId") }
        ?: file("app/build.gradle.kts")

    @Test fun `every build keeps the stable application id`() {
        val gradle = gradle()
        assertTrue(gradle.contains("applicationId = \"com.alexisgordr.icdetector\""))
        assertFalse(gradle.contains("applicationIdSuffix"))
    }

    @Test fun `the launcher name is ICdetection`() {
        val manifest = file("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:label=\"ICdetection\""))
    }

    @Test fun `3_0 follows 2_10_10 and no store note is ahead of it`() {
        val code = Regex("versionCode = (\\d+)").find(gradle())!!.groupValues[1].toInt()
        assertEquals(39, code)
        val fastlane = listOf(File("fastlane"), File("../fastlane")).first { it.isDirectory }
        for (locale in listOf("en-US", "es-ES")) {
            val dir = File(fastlane, "metadata/android/$locale/changelogs")
            assertTrue("$locale/$code.txt", File(dir, "$code.txt").isFile)
            val latest = dir.listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()
            assertEquals(locale, code, latest)
        }
    }
}
