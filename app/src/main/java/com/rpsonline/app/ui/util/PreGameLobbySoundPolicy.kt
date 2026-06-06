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
     * Match-found alert ticks/haptics (every 500ms) until the first live round starts or the
     * match ends. Silent on the in-game "waiting for opponent" lobby screen only.
     */
    fun shouldRunMatchFoundLobbyAlert(
        match: Match?,
        uid: String?,
        visibleMatchScreenId: String?,
    ): Boolean {
        if (uid == null) return false
        val lobbyMatch = when {
            match != null && match.isParticipant(uid) -> match
            else -> JoinMatchNotificationState.lobbyMatch()
        } ?: return false
        if (!lobbyMatch.isParticipant(uid)) return false
        if (
            visibleMatchScreenId == lobbyMatch.id &&
            lobbyMatch.status == MatchStatus.LOBBY
        ) {
            return false
        }
        return when (lobbyMatch.status) {
            MatchStatus.LOBBY -> true
            MatchStatus.ACTIVE -> {
                if (lobbyMatch.isPlayerClockRunning(uid)) return false
                lobbyMatch.isPreGameSegmentedDisplayPhase(uid)
            }
            else -> false
        }
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
            match.isParticipant(uid) &&
            match.isPreGameSegmentedDisplayPhase(uid)
    }
}
