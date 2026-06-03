package com.rpsonline.app.ui.util

import android.content.Context
import com.rpsonline.app.data.preferences.SoundPreferences

/** One feedback burst per match id (notification, lobby assign, game screen). */
internal object MatchFoundFeedbackGate {
    private var lastMatchId: String? = null

    fun tryAcknowledge(matchId: String): Boolean {
        if (matchId.isBlank() || matchId == lastMatchId) return false
        lastMatchId = matchId
        return true
    }

    fun reset() {
        lastMatchId = null
    }
}

/** Sound and/or haptic when a match is found — notification, lobby, or game screen open. */
fun triggerMatchFoundFeedback(context: Context, matchId: String) {
    if (!MatchFoundFeedbackGate.tryAcknowledge(matchId)) return
    val appContext = context.applicationContext
    val mode = SoundPreferences(appContext).getMode()
    val tickPlayer = ClockTickPlayer(appContext)
    try {
        playMatchFoundFeedback(appContext, tickPlayer, mode)
    } finally {
        tickPlayer.release()
    }
}
