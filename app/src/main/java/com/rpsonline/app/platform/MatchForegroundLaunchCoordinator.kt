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
            val sessionMatch = match
            if (
                sessionMatch != null &&
                uid != null &&
                sessionMatch.isParticipant(uid) &&
                sessionMatch.status == MatchStatus.LOBBY
            ) {
                postLobbyMatchFoundIfNeeded(appContext, sessionMatch)
            }
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
            !MatchFoundNotificationPolicy.shouldRunMatchFoundAlert(
                appInForeground = false,
                matchStatus = match.status,
                matchFoundNotificationsEnabled = prefs.isMatchFoundNotificationsEnabled(),
                backgroundUsageEnabled = prefs.isBackgroundUsageEnabled(),
                hasPostNotificationsPermission =
                    NotificationPermissionHelper.hasPostNotificationsPermission(appContext),
                matchId = match.id,
                visibleMatchScreenId = MatchSessionMonitor.visibleMatchScreenId.value,
                liveSessionMatch = match,
                uid = uid,
            )
        ) {
            return
        }
        lastNotifiedMatchId = match.id
        MatchNotificationHelper.showMatchFound(
            appContext,
            match,
            uid,
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

    fun activeJoinMatchNotificationId(): String? = lastNotifiedMatchId

    /** Posts or refreshes the lobby match-found alert (including while the app is foregrounded). */
    fun postLobbyMatchFoundIfNeeded(context: Context, match: Match) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!match.isParticipant(uid) || match.status != MatchStatus.LOBBY) return
        val appContext = context.applicationContext
        val prefs = MatchmakingPreferences(appContext)
        if (
            !MatchFoundNotificationPolicy.shouldRunMatchFoundAlert(
                appInForeground = AppForegroundTracker.isInForeground,
                matchStatus = match.status,
                matchFoundNotificationsEnabled = prefs.isMatchFoundNotificationsEnabled(),
                backgroundUsageEnabled = prefs.isBackgroundUsageEnabled(),
                hasPostNotificationsPermission =
                    NotificationPermissionHelper.hasPostNotificationsPermission(appContext),
                matchId = match.id,
                visibleMatchScreenId = MatchSessionMonitor.visibleMatchScreenId.value,
                liveSessionMatch = MatchSessionMonitor.activeMatch.value,
                uid = uid,
            )
        ) {
            return
        }
        if (MatchNotificationHelper.showMatchFound(appContext, match, uid)) {
            lastNotifiedMatchId = match.id
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
