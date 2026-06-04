package com.rpsonline.app.platform

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus

/** When to post or clear session shade notifications (match found / in match). */
internal object MatchFoundNotificationPolicy {
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
    ): Boolean {
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
        @Suppress("UNUSED_PARAMETER") foregroundServiceRunning: Boolean = false,
    ): Boolean {
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
        foregroundServiceRunning: Boolean = false,
    ): Boolean {
        if (
            !shouldShowJoinMatchNotification(
                appInForeground,
                matchStatus,
                matchFoundNotificationsEnabled,
                backgroundUsageEnabled,
                hasPostNotificationsPermission,
                matchId,
                foregroundServiceRunning,
            )
        ) {
            return false
        }
        return matchId != lastNotifiedMatchId
    }
}
