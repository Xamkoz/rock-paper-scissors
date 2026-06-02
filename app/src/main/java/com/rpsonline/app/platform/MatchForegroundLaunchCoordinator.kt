package com.rpsonline.app.platform

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.preferences.MatchmakingPreferences
import com.rpsonline.app.data.repository.MatchSessionMonitor

/**
 * When background matchmaking is running and a match enters LOBBY or ACTIVE, bring the app
 * to the foreground so the user sees pre-game sync (home) or the game screen (ACTIVE).
 */
object MatchForegroundLaunchCoordinator {
    private var lastLaunchKey: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun onMatchSessionChanged(context: Context, match: Match?) {
        if (AppForegroundTracker.isInForeground) {
            return
        }
        if (!MatchmakingPreferences(context.applicationContext).isBackgroundUsageEnabled()) {
            return
        }
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val sessionMatch = match ?: return
        if (!sessionMatch.isParticipant(uid)) return
        when (sessionMatch.status) {
            MatchStatus.LOBBY, MatchStatus.ACTIVE -> Unit
            else -> return
        }

        val launchKey = "${sessionMatch.id}:${sessionMatch.status}"
        if (launchKey == lastLaunchKey) return
        lastLaunchKey = launchKey

        MatchSessionMonitor.noteMatchLaunchIntent(sessionMatch.id)
        if (sessionMatch.status == MatchStatus.ACTIVE) {
            MatchSessionMonitor.requestGameNavigation(sessionMatch.id)
        }
        val appContext = context.applicationContext
        mainHandler.post {
            MatchmakingForegroundService.requestLaunchAlert()
            MatchLaunchHelper.launchMatch(appContext, sessionMatch.id)
        }
    }

    fun clearLaunchDedup() {
        lastLaunchKey = null
    }

    fun noteLaunchAttempted(matchId: String, status: MatchStatus?) {
        if (status == null) return
        lastLaunchKey = "$matchId:$status"
    }
}
