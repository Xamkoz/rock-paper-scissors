package com.rpsonline.app.platform

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus

/** When to post or clear session shade notifications (match found / in match). */
internal object MatchFoundNotificationPolicy {
    /**
     * HIGH-importance match-found shade when backgrounded, or while the app is open with the
     * background matchmaking foreground service running.
     */
    fun shouldUseHighImportanceMatchFoundShade(
        backgroundUsageEnabled: Boolean = false,
        foregroundServiceRunning: Boolean = false,
    ): Boolean =
        !AppForegroundTracker.isInForeground ||
            (backgroundUsageEnabled && foregroundServiceRunning)

    /** Match-found shade always uses IMPORTANCE_HIGH so it stays visible on the lock screen and status bar. */
    fun shouldUsePersistentHighPriorityMatchFoundShade(): Boolean = true

    /** Heads-up peek for the first 20s after posting (background only — avoids duplicate banners in-app). */
    fun shouldUseProminentMatchFoundHeadsUp(): Boolean =
        !AppForegroundTracker.isInForeground &&
            JoinMatchNotificationState.isWithinProminentAlertWindow()

    /** Full-screen launch alerts forcibly foreground the app; disabled for background matchmaking. */
    fun shouldUseFullScreenMatchLaunchAlert(backgroundUsageEnabled: Boolean): Boolean =
        !backgroundUsageEnabled

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
        if (match == null || uid == null) return false
        if (
            !shouldRunMatchFoundAlert(
                appInForeground = appInForeground,
                matchStatus = match.status,
                matchFoundNotificationsEnabled = matchFoundNotificationsEnabled,
                backgroundUsageEnabled = backgroundUsageEnabled,
                hasPostNotificationsPermission = hasPostNotificationsPermission,
                matchId = match.id,
                visibleMatchScreenId = visibleMatchScreenId,
                liveSessionMatch = liveSessionMatch,
                uid = uid,
            )
        ) {
            return false
        }
        return shouldPostMatchFoundShadeNotification(
            foregroundServiceOwnsDisplay = foregroundServiceOwnsDisplay,
            appInForeground = appInForeground,
            matchFoundNotificationsEnabled = matchFoundNotificationsEnabled,
            backgroundUsageEnabled = backgroundUsageEnabled,
        )
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

    /** Run match-found alert session (lobby phase, ticks, FGS tile). Shade 2001 is separate. */
    fun shouldRunMatchFoundAlert(
        appInForeground: Boolean,
        matchStatus: MatchStatus?,
        matchFoundNotificationsEnabled: Boolean,
        backgroundUsageEnabled: Boolean,
        hasPostNotificationsPermission: Boolean,
        matchId: String,
        visibleMatchScreenId: String?,
        liveSessionMatch: Match? = null,
        uid: String? = null,
    ): Boolean {
        if (
            shouldSuppressMatchFoundAlerts(
                liveMatch = liveSessionMatch,
                uid = uid,
                backgroundUsageEnabled = backgroundUsageEnabled,
            )
        ) {
            return false
        }
        if (matchStatus != MatchStatus.LOBBY) return false
        if (visibleMatchScreenId == matchId) return false
        if (!hasPostNotificationsPermission) return false
        if (matchId.isBlank()) return false
        if (appInForeground && !matchFoundNotificationsEnabled && !backgroundUsageEnabled) {
            return false
        }
        if (!matchFoundNotificationsEnabled && !backgroundUsageEnabled) return false
        return true
    }

    /**
     * Post shade notification 2001. When the FGS tile is active, skip duplicate shade unless the app
     * is foregrounded with match-found notifications enabled (status bar visibility while on home).
     */
    fun shouldPostMatchFoundShadeNotification(
        foregroundServiceOwnsDisplay: Boolean,
        appInForeground: Boolean,
        matchFoundNotificationsEnabled: Boolean,
        backgroundUsageEnabled: Boolean = false,
    ): Boolean {
        if (!foregroundServiceOwnsDisplay) return true
        return appInForeground && (matchFoundNotificationsEnabled || backgroundUsageEnabled)
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
        visibleMatchScreenId: String? = null,
    ): Boolean {
        if (
            !shouldRunMatchFoundAlert(
                appInForeground = appInForeground,
                matchStatus = matchStatus,
                matchFoundNotificationsEnabled = matchFoundNotificationsEnabled,
                backgroundUsageEnabled = backgroundUsageEnabled,
                hasPostNotificationsPermission = hasPostNotificationsPermission,
                matchId = matchId,
                visibleMatchScreenId = visibleMatchScreenId,
                liveSessionMatch = liveSessionMatch,
                uid = uid,
            )
        ) {
            return false
        }
        return shouldPostMatchFoundShadeNotification(
            foregroundServiceOwnsDisplay = foregroundServiceOwnsDisplay,
            appInForeground = appInForeground,
            matchFoundNotificationsEnabled = matchFoundNotificationsEnabled,
            backgroundUsageEnabled = backgroundUsageEnabled,
        )
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
