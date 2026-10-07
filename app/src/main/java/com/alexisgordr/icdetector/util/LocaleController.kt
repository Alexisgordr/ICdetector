package com.alexisgordr.icdetector.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/** Keeps the explicitly selected app language independent from the system language. */
object LocaleController {
    const val PREFS = "miniic_prefs"
    const val KEY = "app_language"

    /**
     * Idioma elegido en Ajustes o, si nunca se eligió, el del sistema (castellano o, si no lo es,
     * inglés). El del sistema se lee de [Resources.getSystem] porque [Locale.getDefault] ya refleja
     * el idioma que la propia app impuso en [localizedContext].
     */
    fun selectedLanguage(context: Context): String = AppLanguage.resolve(
        saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null),
        systemLanguage = Resources.getSystem().configuration.locales[0]?.language
    )

    fun selectLanguage(context: Context, languageTag: String) {
        require(languageTag in AppLanguage.supported)
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
