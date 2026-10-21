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
 * Las betas de la 3.0 usaron el versionCode 39, el siguiente a la 2.10.10 (38), y la 3.0.0 lo
 * conservó. 3.1.0 es el 40. Las notas de la tienda (F-Droid) van por versionCode: la nota del
 * código actual tiene que existir y ninguna puede ir por delante de él.
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

    /**
     * 3.0 (corte de campaña) — Android 12 es el mínimo real: la recolección se registra con
     * TelephonyCallback (API 31) y no hay respaldo con PhoneStateListener. En Android 10/11 no
     * llegaban celdas, ServiceState ni DisplayInfo por callback (H3 siempre N/A, sin 5G NSA).
     */
    @Test fun `the minimum is Android 12, where the telephony callbacks exist`() {
        assertEquals(31, Regex("minSdk = (\\d+)").find(gradle())!!.groupValues[1].toInt())
        val controller = file("src/main/java/com/alexisgordr/icdetector/service/TelephonyCollectionController.kt")
        assertFalse("sin guardas muertas de API 31", controller.contains("VERSION_CODES.S"))
    }

    @Test fun `3_1 follows 3_0 and no store note is ahead of it`() {
        val code = Regex("versionCode = (\\d+)").find(gradle())!!.groupValues[1].toInt()
        assertEquals(40, code)
        val fastlane = listOf(File("fastlane"), File("../fastlane")).first { it.isDirectory }
        for (locale in listOf("en-US", "es-ES")) {
            val dir = File(fastlane, "metadata/android/$locale/changelogs")
            assertTrue("$locale/$code.txt", File(dir, "$code.txt").isFile)
            // 3.1.0 — Límite de las notas de versión de F-Droid y Google Play.
            assertTrue("$locale/$code.txt: 500 caracteres como mucho", File(dir, "$code.txt").readText().length <= 500)
            val latest = dir.listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()
            assertEquals(locale, code, latest)
        }
    }
}
