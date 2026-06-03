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
    private var lastNotifiedMatchId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun onMatchSessionChanged(context: Context, match: Match?) {
        val appContext = context.applicationContext
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (MatchFoundNotificationPolicy.shouldDismissJoinMatchNotification(
                match,
                uid,
                MatchSessionMonitor.visibleMatchScreenId.value,
            )
        ) {
            MatchNotificationHelper.dismissMatchFound(appContext)
            if (match?.status != MatchStatus.LOBBY) {
                lastNotifiedMatchId = null
            }
        }
        if (AppForegroundTracker.isInForeground) {
            return
        }
        val sessionMatch = match ?: return
        if (uid == null || !sessionMatch.isParticipant(uid)) return

        when (sessionMatch.status) {
            MatchStatus.LOBBY -> {
                maintainJoinMatchNotification(appContext, uid, sessionMatch)
                handleBackgroundAutoLaunch(appContext, sessionMatch)
            }
            MatchStatus.ACTIVE -> handleBackgroundAutoLaunch(appContext, sessionMatch)
            else -> Unit
        }
    }

    private fun maintainJoinMatchNotification(
        appContext: Context,
        uid: String,
        match: Match,
    ) {
        val prefs = MatchmakingPreferences(appContext)
        if (
            !MatchFoundNotificationPolicy.shouldShowJoinMatchNotification(
                appInForeground = false,
                matchStatus = match.status,
                matchFoundNotificationsEnabled = prefs.isMatchFoundNotificationsEnabled(),
                backgroundUsageEnabled = prefs.isBackgroundUsageEnabled(),
                hasPostNotificationsPermission =
                    NotificationPermissionHelper.hasPostNotificationsPermission(appContext),
                matchId = match.id,
            )
        ) {
            return
        }
        lastNotifiedMatchId = match.id
        MatchNotificationHelper.showMatchFound(
            appContext,
            match.id,
            match.opponentName(uid),
        )
    }

    private fun handleBackgroundAutoLaunch(appContext: Context, sessionMatch: Match) {
        if (!MatchmakingPreferences(appContext).isBackgroundUsageEnabled()) {
            return
        }
        val launchKey = "${sessionMatch.id}:${sessionMatch.status}"
        if (launchKey == lastLaunchKey) return
        lastLaunchKey = launchKey

        MatchSessionMonitor.noteMatchLaunchIntent(sessionMatch.id)
        if (sessionMatch.status == MatchStatus.ACTIVE) {
            MatchSessionMonitor.requestGameNavigation(sessionMatch.id)
        }
        mainHandler.post {
            MatchmakingForegroundService.requestLaunchAlert()
            MatchLaunchHelper.launchMatch(appContext, sessionMatch.id)
        }
    }

    fun clearLaunchDedup() {
        lastLaunchKey = null
        lastNotifiedMatchId = null
    }

    fun noteLaunchAttempted(matchId: String, status: MatchStatus?) {
        if (status == null) return
        lastLaunchKey = "$matchId:$status"
    }
}
