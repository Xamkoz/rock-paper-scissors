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
            MatchNotificationHelper.dismissMatchFound(appContext)
            lastNotifiedMatchId = null
        } else if (uid != null) {
            val lobbyMatch = when {
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

        if (AppForegroundTracker.isInForeground) {
            return
        }
        val sessionMatch = match ?: return
        if (uid == null || !sessionMatch.isParticipant(uid)) return
        if (!MatchmakingPreferences(appContext).isBackgroundUsageEnabled()) {
            return
        }

        when (sessionMatch.status) {
            MatchStatus.LOBBY -> Unit
            MatchStatus.ACTIVE -> handleBackgroundAutoLaunch(appContext, sessionMatch)
            else -> Unit
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
        val mayPost = MatchFoundNotificationPolicy.shouldShowJoinMatchNotification(
            appInForeground = AppForegroundTracker.isInForeground,
            matchStatus = match.status,
            matchFoundNotificationsEnabled = prefs.isMatchFoundNotificationsEnabled(),
            backgroundUsageEnabled = prefs.isBackgroundUsageEnabled(),
            hasPostNotificationsPermission =
                NotificationPermissionHelper.hasPostNotificationsPermission(appContext),
            matchId = match.id,
            foregroundServiceRunning = MatchmakingForegroundService.isRunning(),
        )
        if (mayPost) {
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

    private fun handleBackgroundAutoLaunch(appContext: Context, sessionMatch: Match) {
        val launchKey = "${sessionMatch.id}:${sessionMatch.status}"
        if (launchKey == lastLaunchKey) return
        lastLaunchKey = launchKey

        MatchSessionMonitor.noteMatchLaunchIntent(sessionMatch.id)
        MatchSessionMonitor.requestGameNavigation(sessionMatch.id)
        mainHandler.post {
            MatchLaunchHelper.launchMatch(appContext, sessionMatch.id)
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
