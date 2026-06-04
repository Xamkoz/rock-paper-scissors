package com.rpsonline.app.ui.util

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.repository.MatchSessionMonitor
import com.rpsonline.app.platform.JoinMatchNotificationState

/** Pre-game lobby waiting (both players), not only the match-found notification burst. */
object PreGameLobbySoundPolicy {
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
