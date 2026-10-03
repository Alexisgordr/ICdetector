package com.alexisgordr.icdetector.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** v2.10.6 — Primer arranque: sigue al sistema; castellano solo si el sistema es castellano. */
class AppLanguageTest {
    @Test fun `spanish system starts in spanish whatever the region`() {
        listOf("es", "ES", "es-ES", "es-MX", "es_AR").forEach {
            assertEquals(it, "es", AppLanguage.resolve(saved = null, systemLanguage = it))
        }
    }

    @Test fun `english and any other system language start in english`() {
        listOf("en", "en-US", "ru", "de", "fr", "ca", "pt-BR", "", null).forEach {
            assertEquals("$it", "en", AppLanguage.resolve(saved = null, systemLanguage = it))
        }
    }

    @Test fun `a language chosen in settings always wins`() {
        assertEquals("es", AppLanguage.resolve(saved = "es", systemLanguage = "de"))
        assertEquals("en", AppLanguage.resolve(saved = "en", systemLanguage = "es"))
    }

    @Test fun `an unknown saved value falls back to the system rule`() {
        assertEquals("es", AppLanguage.resolve(saved = "fr", systemLanguage = "es"))
        assertEquals("en", AppLanguage.resolve(saved = "fr", systemLanguage = "ru"))
    }
}
