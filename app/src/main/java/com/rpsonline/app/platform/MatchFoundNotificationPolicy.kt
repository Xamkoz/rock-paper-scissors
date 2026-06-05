package com.rpsonline.app.platform

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus

/** When to post or clear session shade notifications (match found / in match). */
internal object MatchFoundNotificationPolicy {
    /** Heads-up / IMPORTANCE_HIGH match-found shade only when the app is not in the foreground. */
    fun shouldUseHighImportanceMatchFoundShade(): Boolean =
        !AppForegroundTracker.isInForeground

    /** Ongoing match-found tile uses HIGH importance while alerting (foreground shade + background). */
    fun shouldUsePersistentHighPriorityMatchFoundShade(): Boolean =
        JoinMatchNotificationState.isLobbyAlertPhase() ||
            shouldUseHighImportanceMatchFoundShade()

    /** Full-screen launch alerts forcibly foreground the app; disabled for background matchmaking. */
    fun shouldUseFullScreenMatchLaunchAlert(backgroundUsageEnabled: Boolean): Boolean =
        !backgroundUsageEnabled

    /** Heads-up / high-importance match-found alert for the first 20s after posting. */
    fun shouldUseProminentMatchFoundHeadsUp(): Boolean =
        shouldUseHighImportanceMatchFoundShade() &&
            JoinMatchNotificationState.isWithinProminentAlertWindow()

    /**
     * While in an active match with background usage on, match-found heads-up / high-importance
     * alerts must not compete with the in-match FGS tile.
     */
    fun shouldSuppressMatchFoundAlerts(
        liveMatch: Match?,
        uid: String?,
        backgroundUsageEnabled: Boolean,
    ): Boolean {
        if (!backgroundUsageEnabled) return false
        if (liveMatch == null || uid == null) return false
        if (!liveMatch.isParticipant(uid)) return false
        return liveMatch.status == MatchStatus.ACTIVE
    }

    fun shouldDismissJoinMatchNotification(
        match: Match?,
        uid: String?,
        visibleMatchScreenId: String?,
        activeJoinMatchNotificationId: String? = null,
    ): Boolean {
        if (uid == null) return true
        if (match == null) {
            val retainId = activeJoinMatchNotificationId
                ?: JoinMatchNotificationState.activeMatchId()
            if (retainId.isNullOrBlank()) return true
            return visibleMatchScreenId == retainId
        }
        if (!match.isParticipant(uid)) return true
        if (
            match.status == MatchStatus.COMPLETED ||
            match.status == MatchStatus.ABANDONED
        ) {
            return true
        }
        return visibleMatchScreenId == match.id
    }

    /** Keep the ongoing match-found shade through pre-game lobby until the game screen opens. */
    fun shouldMaintainJoinMatchNotification(
        appInForeground: Boolean,
        match: Match?,
        uid: String?,
        visibleMatchScreenId: String?,
        matchFoundNotificationsEnabled: Boolean,
        backgroundUsageEnabled: Boolean,
        hasPostNotificationsPermission: Boolean,
        foregroundServiceOwnsDisplay: Boolean = false,
        liveSessionMatch: Match? = null,
    ): Boolean {
        if (foregroundServiceOwnsDisplay) return false
        if (
            shouldSuppressMatchFoundAlerts(
                liveMatch = liveSessionMatch,
                uid = uid,
                backgroundUsageEnabled = backgroundUsageEnabled,
            )
        ) {
            return false
        }
        if (appInForeground && !matchFoundNotificationsEnabled) return false
        if (match == null || uid == null) return false
        if (!match.isParticipant(uid)) return false
        if (match.status != MatchStatus.LOBBY) return false
        if (visibleMatchScreenId == match.id) return false
        if (!hasPostNotificationsPermission) return false
        if (!matchFoundNotificationsEnabled && !backgroundUsageEnabled) return false
        return true
    }

    /** Keep the ongoing in-match shade tile until the user opens this match's game screen. */
    fun shouldMaintainInMatchNotification(
        match: Match?,
        uid: String?,
        visibleMatchScreenId: String?,
    ): Boolean {
        if (match == null || uid == null) return false
        if (!match.isParticipant(uid)) return false
        if (match.status != MatchStatus.ACTIVE) return false
        return visibleMatchScreenId != match.id
    }

    fun shouldShowJoinMatchNotification(
        appInForeground: Boolean,
        matchStatus: MatchStatus?,
        matchFoundNotificationsEnabled: Boolean,
        backgroundUsageEnabled: Boolean,
        hasPostNotificationsPermission: Boolean,
        matchId: String,
        foregroundServiceOwnsDisplay: Boolean = false,
        liveSessionMatch: Match? = null,
        uid: String? = null,
    ): Boolean {
        if (foregroundServiceOwnsDisplay) return false
        if (
            shouldSuppressMatchFoundAlerts(
                liveMatch = liveSessionMatch,
                uid = uid,
                backgroundUsageEnabled = backgroundUsageEnabled,
            )
        ) {
            return false
        }
        if (appInForeground && !matchFoundNotificationsEnabled) return false
        if (matchStatus != MatchStatus.LOBBY) return false
        if (!hasPostNotificationsPermission) return false
        if (!matchFoundNotificationsEnabled && !backgroundUsageEnabled) return false
        if (matchId.isBlank()) return false
        return true
    }

    /** First post from Compose only — avoids a duplicate alert when the coordinator already posted. */
    fun shouldPostJoinMatchNotification(
        appInForeground: Boolean,
        matchStatus: MatchStatus?,
        matchFoundNotificationsEnabled: Boolean,
        backgroundUsageEnabled: Boolean,
        hasPostNotificationsPermission: Boolean,
        lastNotifiedMatchId: String?,
        matchId: String,
        foregroundServiceOwnsDisplay: Boolean = false,
        liveSessionMatch: Match? = null,
        uid: String? = null,
    ): Boolean {
        if (
            !shouldShowJoinMatchNotification(
                appInForeground,
                matchStatus,
                matchFoundNotificationsEnabled,
                backgroundUsageEnabled,
                hasPostNotificationsPermission,
                matchId,
                foregroundServiceOwnsDisplay,
                liveSessionMatch,
                uid,
            )
        ) {
            return false
        }
        return matchId != lastNotifiedMatchId
    }
}
