package com.rpsonline.app.platform

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.preferences.MatchmakingPreferences
import com.rpsonline.app.data.repository.MatchSessionMonitor

/** Posts match-found / in-match notifications; does not auto-launch the app from the background. */
object MatchForegroundLaunchCoordinator {
    private var lastLaunchKey: String? = null
    private var lastNotifiedMatchId: String? = null

    fun onMatchSessionChanged(context: Context, match: Match?) {
        val appContext = context.applicationContext
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val visibleMatchScreenId = MatchSessionMonitor.visibleMatchScreenId.value

        MatchmakingBackgroundCoordinator.sync(appContext)

        if (
            MatchFoundNotificationPolicy.shouldDismissJoinMatchNotification(
                match,
                uid,
                visibleMatchScreenId,
                activeJoinMatchNotificationId = lastNotifiedMatchId,
            )
        ) {
            MatchNotificationHelper.dismissMatchFound(appContext, match, uid)
            lastNotifiedMatchId = null
        } else if (uid != null) {
            val liveSession = match ?: MatchSessionMonitor.activeMatch.value
            val suppressMatchFound = MatchFoundNotificationPolicy.shouldSuppressMatchFoundAlerts(
                liveMatch = liveSession,
                uid = uid,
                backgroundUsageEnabled =
                    MatchmakingPreferences(appContext).isBackgroundUsageEnabled(),
            )
            val lobbyMatch = when {
                suppressMatchFound -> null
                match != null && match.isParticipant(uid) && match.status == MatchStatus.LOBBY -> match
                else -> JoinMatchNotificationState.lobbyMatch()
                    ?.takeIf { it.isParticipant(uid) && it.status == MatchStatus.LOBBY }
            }
            if (lobbyMatch != null) {
                MatchSessionMonitor.setMatchmakingInProgress(true)
                postMatchFoundIfNeeded(appContext, uid, lobbyMatch)
            }
            if (match != null && match.isParticipant(uid)) {
                when (match.status) {
                    MatchStatus.ACTIVE -> {
                        if (
                            MatchFoundNotificationPolicy.shouldMaintainInMatchNotification(
                                match,
                                uid,
                                visibleMatchScreenId,
                            )
                        ) {
                            MatchNotificationHelper.showInMatch(appContext, match, uid)
                        }
                    }
                    else -> Unit
                }
            }
        }

    }

    fun activeJoinMatchNotificationId(): String? = lastNotifiedMatchId

    /** Posts or refreshes the lobby match-found alert (including while the app is foregrounded). */
    fun postLobbyMatchFoundIfNeeded(context: Context, match: Match) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!match.isParticipant(uid) || match.status != MatchStatus.LOBBY) return
        postMatchFoundIfNeeded(context.applicationContext, uid, match)
    }

    private fun postMatchFoundIfNeeded(appContext: Context, uid: String, match: Match) {
        val prefs = MatchmakingPreferences(appContext)
        val fgsOwnsDisplay =
            MatchmakingBackgroundCoordinator.foregroundServiceOwnsMatchFoundDisplay(appContext)
        val liveSession = MatchSessionMonitor.activeMatch.value
        val mayPostShade = MatchFoundNotificationPolicy.shouldShowJoinMatchNotification(
            appInForeground = AppForegroundTracker.isInForeground,
            matchStatus = match.status,
            matchFoundNotificationsEnabled = prefs.isMatchFoundNotificationsEnabled(),
            backgroundUsageEnabled = prefs.isBackgroundUsageEnabled(),
            hasPostNotificationsPermission =
                NotificationPermissionHelper.hasPostNotificationsPermission(appContext),
            matchId = match.id,
            foregroundServiceOwnsDisplay = fgsOwnsDisplay,
            liveSessionMatch = liveSession,
            uid = uid,
        )
        if (fgsOwnsDisplay || mayPostShade) {
            if (MatchNotificationHelper.showMatchFound(appContext, match, uid)) {
                lastNotifiedMatchId = match.id
            }
        } else if (
            lastNotifiedMatchId == match.id ||
            JoinMatchNotificationState.activeMatchId() == match.id
        ) {
            MatchNotificationHelper.maintainJoinMatchNotification(appContext, match, uid)
        }
    }

    fun clearLaunchDedup() {
        lastLaunchKey = null
        lastNotifiedMatchId = null
        JoinMatchNotificationState.clear()
        MatchNotificationHelper.resetMatchFoundAlertSession()
    }

    fun noteLaunchAttempted(matchId: String, status: MatchStatus?) {
        if (status == null) return
        lastLaunchKey = "$matchId:$status"
    }
}
