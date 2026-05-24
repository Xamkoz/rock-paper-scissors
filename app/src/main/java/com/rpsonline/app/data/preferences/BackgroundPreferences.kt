package com.rpsonline.app.data.preferences

import android.content.Context

class BackgroundPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBackground(): AppBackground =
        AppBackground.fromPreference(prefs.getString(KEY_BACKGROUND, AppBackground.TROPHY.name))

    fun setBackground(background: AppBackground) {
        prefs.edit().putString(KEY_BACKGROUND, background.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "rps_appearance"
        private const val KEY_BACKGROUND = "app_background"
    }
}
