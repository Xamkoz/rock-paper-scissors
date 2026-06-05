package com.rpsonline.app.ui.util

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.repository.MatchSessionMonitor
import com.rpsonline.app.platform.JoinMatchNotificationState

/** Pre-game lobby waiting (both players), not only the match-found notification burst. */
object PreGameLobbySoundPolicy {
    /**
     * Match-found alert ticks/haptics (every 500ms) until the user opens this match's game screen.
     * Distinct from the in-lobby "waiting for opponent" screen, which stays silent.
     */
    fun shouldRunMatchFoundLobbyAlert(
        match: Match?,
        uid: String?,
        visibleMatchScreenId: String?,
    ): Boolean {
        if (match == null || uid == null) return JoinMatchNotificationState.isLobbyAlertPhase()
        if (match.status != MatchStatus.LOBBY || !match.isParticipant(uid)) {
            return JoinMatchNotificationState.isLobbyAlertPhase()
        }
        if (visibleMatchScreenId == match.id) return false
        return true
    }

    fun shouldRunMatchFoundLobbyAlert(context: Context): Boolean {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val match = MatchSessionMonitor.activeMatch.value
            ?: JoinMatchNotificationState.lobbyMatch()
        val visibleMatchScreenId = MatchSessionMonitor.visibleMatchScreenId.value
        return shouldRunMatchFoundLobbyAlert(match, uid, visibleMatchScreenId)
    }

    @Suppress("UNUSED_PARAMETER")
    fun isUserInPreGameLobby(context: Context): Boolean {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        val match = MatchSessionMonitor.activeMatch.value
            ?: JoinMatchNotificationState.lobbyMatch()
        return match != null &&
            match.status == MatchStatus.LOBBY &&
            match.isParticipant(uid)
    }
}
