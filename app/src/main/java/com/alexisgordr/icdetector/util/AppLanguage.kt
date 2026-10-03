package com.alexisgordr.icdetector.util

/**
 * v2.10.6 — Idioma con el que arranca la app cuando el usuario todavía no ha elegido uno en
 * Ajustes. Antes era siempre castellano. Ahora sigue al sistema: castellano si el sistema está en
 * castellano (cualquier región), inglés en cualquier otro caso (ruso, alemán…) o si no se conoce.
 */
internal object AppLanguage {
    const val SPANISH = "es"
    const val ENGLISH = "en"
    val supported = setOf(SPANISH, ENGLISH)

    fun resolve(saved: String?, systemLanguage: String?): String =
        saved?.takeIf(supported::contains) ?: forSystem(systemLanguage)

    fun forSystem(systemLanguage: String?): String =
        if (systemLanguage?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase() == SPANISH) SPANISH
        else ENGLISH
}
