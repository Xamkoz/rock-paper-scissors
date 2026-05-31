package com.rpsonline.app.data.preferences

import android.content.Context

private const val PREFS_NAME = "rps_online_filter_prefs"
private const val KEY_ONLINE_ONLY = "online_only_filter"

class OnlineFilterPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isOnlineOnlyEnabled(): Boolean = prefs.getBoolean(KEY_ONLINE_ONLY, false)

    fun setOnlineOnlyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ONLINE_ONLY, enabled).apply()
    }
}
