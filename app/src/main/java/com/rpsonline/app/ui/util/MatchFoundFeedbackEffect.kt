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

/**
 * One-shot match-found burst when opening the game screen.
 * Repeating lobby ticks use [MatchClockSoundController.syncLobbyAlert].
 *
 * @param playReadyBurst false when [syncLobbyAlert] already drives tick/haptic feedback.
 */
fun triggerMatchFoundFeedback(
    context: Context,
    matchId: String,
    playReadyBurst: Boolean = true,
) {
    if (!MatchFoundFeedbackGate.tryAcknowledge(matchId)) return
    if (!playReadyBurst) return
    val appContext = context.applicationContext
    MatchClockSoundController.initialize(appContext)
    val mode = SoundPreferences(appContext).getMode()
    if (mode.allowsSound()) {
        MatchClockSoundController.playReadyBurst()
    }
    if (mode.allowsHaptic() && NotificationAlertSoundPolicy.notificationHapticsAllowed(appContext)) {
        MatchClockHaptics.initialize(appContext)
        MatchClockHaptics.pulseTick()
    }
}
