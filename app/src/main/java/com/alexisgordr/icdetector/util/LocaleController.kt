package com.alexisgordr.icdetector.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Keeps the explicitly selected app language independent from the system language. */
object LocaleController {
    private const val PREFS = "miniic_prefs"
    private const val KEY = "app_language"
    private const val DEFAULT_LANGUAGE = "es"
    private val supported = setOf("es", "en")

    fun selectedLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, DEFAULT_LANGUAGE)
            ?.takeIf(supported::contains)
            ?: DEFAULT_LANGUAGE

    fun selectLanguage(context: Context, languageTag: String) {
        require(languageTag in supported)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, languageTag)
            .apply()
    }

    fun localizedContext(context: Context): Context {
        val locale = Locale.forLanguageTag(selectedLanguage(context))
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        return context.createConfigurationContext(configuration)
    }
}
