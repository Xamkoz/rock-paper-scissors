package com.rpsonline.app.data.preferences

import android.content.Context

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

class ThemePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getThemeMode(): ThemeMode {
        val stored = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return ThemeMode.entries.find { it.name == stored } ?: ThemeMode.SYSTEM
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "rps_appearance"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
