package com.rpsonline.app.data.preferences

import android.content.Context

private const val PREFS_NAME = "rps_online_filter_prefs"
private const val KEY_LEGACY_ONLINE_ONLY = "online_only_filter"
private const val KEY_LEADERBOARD_ONLINE_ONLY = "leaderboard_online_only"
private const val KEY_OPPONENTS_ONLINE_ONLY = "opponents_online_only"

enum class OnlineFilterScreen {
    LEADERBOARD,
    OPPONENTS,
}

class OnlineFilterPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyOnlineOnlyIfNeeded()
    }

    fun isOnlineOnlyEnabled(screen: OnlineFilterScreen): Boolean =
        prefs.getBoolean(screen.prefsKey, false)

    fun setOnlineOnlyEnabled(screen: OnlineFilterScreen, enabled: Boolean) {
        prefs.edit().putBoolean(screen.prefsKey, enabled).apply()
    }

    private fun migrateLegacyOnlineOnlyIfNeeded() {
        if (!prefs.contains(KEY_LEGACY_ONLINE_ONLY)) return
        val legacy = prefs.getBoolean(KEY_LEGACY_ONLINE_ONLY, false)
        val editor = prefs.edit().remove(KEY_LEGACY_ONLINE_ONLY)
        if (!prefs.contains(KEY_LEADERBOARD_ONLINE_ONLY)) {
            editor.putBoolean(KEY_LEADERBOARD_ONLINE_ONLY, legacy)
        }
        if (!prefs.contains(KEY_OPPONENTS_ONLINE_ONLY)) {
            editor.putBoolean(KEY_OPPONENTS_ONLINE_ONLY, legacy)
        }
        editor.apply()
    }

    private val OnlineFilterScreen.prefsKey: String
        get() = when (this) {
            OnlineFilterScreen.LEADERBOARD -> KEY_LEADERBOARD_ONLINE_ONLY
            OnlineFilterScreen.OPPONENTS -> KEY_OPPONENTS_ONLINE_ONLY
        }
}
