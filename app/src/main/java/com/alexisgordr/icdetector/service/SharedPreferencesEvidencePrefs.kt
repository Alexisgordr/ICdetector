package com.alexisgordr.icdetector.service

import android.content.SharedPreferences
import com.alexisgordr.icdetector.core.TimingAdvanceEvidenceStore
import androidx.core.content.edit

/** 3.1.0 (#43) — [TimingAdvanceEvidenceStore.Prefs] sobre las preferencias de la app. */
internal class SharedPreferencesEvidencePrefs(private val preferences: SharedPreferences) : TimingAdvanceEvidenceStore.Prefs {
    override fun getString(key: String): String? = preferences.getString(key, null)
    override fun getStringSet(key: String): Set<String>? = preferences.getStringSet(key, null)
    override fun contains(key: String): Boolean = preferences.contains(key)
    override fun write(put: Map<String, String>, remove: Set<String>) {
        preferences.edit {
            remove.forEach(::remove)
            put.forEach { (key, value) -> putString(key, value) }
        }
    }
}
