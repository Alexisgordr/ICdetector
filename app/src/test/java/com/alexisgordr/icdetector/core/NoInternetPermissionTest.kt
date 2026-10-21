package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.1.0 — ICdetector no tiene permiso de Internet ni código que abra conexiones. La prueba
 * instrumentada (NoInternetPermissionInstrumentedTest) lo comprueba en el APK instalado.
 */
class NoInternetPermissionTest {
    private val app = listOf(File("."), File("app")).first { File(it, "src/main/AndroidManifest.xml").exists() }
    private val internet = Regex("""<uses-permission[^>]*android\.permission\.INTERNET[^>]*>""")

    /** Los permisos de INTERNET que pide un manifiesto (los marcados para quitar no cuentan). */
    private fun requested(manifest: String) = internet.findAll(manifest).map { it.value }.filterNot { "tools:node=\"remove\"" in it }.toList()

    @Test fun `the manifest does not request INTERNET and removes it from the merged one`() {
        val manifest = File(app, "src/main/AndroidManifest.xml").readText()
        assertEquals(emptyList<String>(), requested(manifest))
        assertTrue("se quita del manifiesto fusionado aunque una dependencia lo declare",
            manifest.contains("""<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />"""))
        assertTrue("se conserva el estado de red (Wi-Fi o datos)", manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
    }

    /** Si Gradle ya fusionó el manifiesto (antes de los tests de la variante), tampoco lo pide. */
    @Test fun `any merged manifest already built has no INTERNET permission`() {
        File(app, "build/intermediates").takeIf { it.exists() }?.walkTopDown()
            ?.filter { it.isFile && it.name == "AndroidManifest.xml" && "merged_manifest" in it.path && "androidTest" !in it.path }
            ?.forEach { assertEquals(it.path, emptyList<String>(), requested(it.readText())) }
    }

    @Test fun `no source opens a network connection`() {
        val forbidden = Regex("""\bjava\.net\b|\bokhttp3\b|\bHttpURLConnection\b|\bSocket\b""")
        val offenders = File(app, "src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
            .filter { forbidden.containsMatchIn(it.readText()) }
            .map { it.path }.toList()
        assertEquals(emptyList<String>(), offenders)
        assertFalse(File(app, "build.gradle.kts").readText().contains("okhttp"))
    }
}
