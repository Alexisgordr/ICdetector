package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.backup.BackupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.1.3 (#50) — Tamaño del texto: Normal (predeterminado) y Grande. 3.1.4: sin «Muy grande». */
class TextScaleTest {
    private fun read(path: String) =
        listOf(File(path), File("app/$path"), File("../$path")).first { it.exists() }.readText()

    @Test fun `normal is the default, also for unknown stored values`() {
        assertEquals(TextScale.NORMAL, TextScale.fromStored(null))
        assertEquals(TextScale.NORMAL, TextScale.fromStored(""))
        assertEquals(TextScale.NORMAL, TextScale.fromStored("HUGE"))
        assertEquals(TextScale.LARGE, TextScale.fromStored("LARGE"))
        // 3.1.4 — «Muy grande» de 3.1.3 (preferencias o copia) pasa a Grande, no a Normal.
        assertEquals(TextScale.LARGE, TextScale.fromStored("EXTRA_LARGE"))
        assertEquals(listOf(TextScale.NORMAL, TextScale.LARGE), TextScale.entries.toList())
    }

    @Test fun `normal leaves the app as it was and the others only grow`() {
        assertEquals(1.0f, TextScale.NORMAL.factor)
        val factors = TextScale.entries.map { it.factor }
        assertEquals(factors.sorted(), factors)
        assertEquals(factors.toSet().size, factors.size)
    }

    @Test fun `the app factor multiplies Android's font size`() {
        assertEquals(1.0f, TextScale.fontScale(1.0f, TextScale.NORMAL))
        assertEquals(1.3f, TextScale.fontScale(1.3f, TextScale.NORMAL))
        assertEquals(1.25f, TextScale.fontScale(1.0f, TextScale.LARGE))
        assertEquals(1.625f, TextScale.fontScale(1.3f, TextScale.LARGE), 0.0001f)
    }

    @Test fun `rows stack only with large text`() {
        // 3.1.4 — Normal con la letra de Android normal: nada cambia. Grande: se apilan.
        assertTrue(!TextScale.usesStackedLayout(TextScale.fontScale(1.0f, TextScale.NORMAL)))
        assertTrue(!TextScale.usesStackedLayout(TextScale.fontScale(1.15f, TextScale.NORMAL)))
        assertTrue(TextScale.usesStackedLayout(TextScale.fontScale(1.0f, TextScale.LARGE)))
        assertTrue(TextScale.usesStackedLayout(TextScale.fontScale(1.3f, TextScale.NORMAL)))
    }

    @Test fun `the setting travels in backups and old backups restore normal`() {
        assertEquals(BackupSettings.Type.STRING, BackupSettings.KEYS[TextScale.PREF_KEY])
        assertEquals(TextScale.NORMAL.storedValue, BackupSettings.DEFAULTS_FOR_MISSING[TextScale.PREF_KEY])
        assertEquals("LARGE", BackupSettings.fromJson("{\"text_scale\":\"LARGE\"}")[TextScale.PREF_KEY])
    }

    @Test fun `the whole interface is scaled in one place`() {
        val activity = read("src/main/java/com/alexisgordr/icdetector/MainActivity.kt")
        val content = activity.substringAfter("setContent {")
        assertTrue(content.contains("LocalDensity provides Density("))
        assertTrue(content.contains("TextScale.fontScale(systemDensity.fontScale, textScale)"))
        // El proveedor envuelve el tema, y con él todas las pantallas y diálogos de la actividad.
        assertTrue(content.indexOf("LocalDensity provides") < content.indexOf("MaterialTheme("))
    }
}
