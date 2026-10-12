package com.alexisgordr.icdetector.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — Ajustes tiene flecha de volver; Historial se cierra con «MONITOR» o con el «atrás» del sistema,
 * que vuelve a la pantalla principal sin salir de la app.
 */
class BackNavigationTest {
    private val app = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private fun ui(name: String) = File(app, "java/com/alexisgordr/icdetector/ui/$name").readText()

    @Test fun `settings shows a back arrow and history relies on MONITOR and system back`() {
        val settings = ui("SettingsScreen.kt")
        assertTrue(settings.contains("IconButton(onClick = onBack)"))
        assertTrue(settings.contains("Icons.AutoMirrored.Filled.ArrowBack"))
        assertFalse("Historial no tiene flecha propia", ui("HistoryScreen.kt").contains("IconButton(onClick = onBack)"))
        assertTrue(ui("MainScreen.kt").contains("onBack = { showSettings = false }"))
    }

    @Test fun `system back returns to the previous screen`() {
        val main = ui("MainScreen.kt")
        assertTrue(main.contains("BackHandler(enabled = showSettings || showHistory)"))
        assertTrue(ui("StableSiteScreen.kt").contains("BackHandler { if (selected != null) selected = null else onBack() }"))
    }
}
