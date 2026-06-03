package com.rpsonline.app.platform

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus

/** When to post the dedicated high-priority "match found / confirm ready" notification. */
internal object MatchFoundNotificationPolicy {
    fun shouldDismissJoinMatchNotification(
        match: Match?,
        uid: String?,
        visibleMatchScreenId: String?,
    ): Boolean {
        if (uid == null || match == null) return true
        if (!match.isParticipant(uid)) return true
        if (match.status != MatchStatus.LOBBY) return true
        if (visibleMatchScreenId == match.id) return true
        return false
    }

    fun shouldShowJoinMatchNotification(
        appInForeground: Boolean,
        matchStatus: MatchStatus?,
        matchFoundNotificationsEnabled: Boolean,
        backgroundUsageEnabled: Boolean,
        hasPostNotificationsPermission: Boolean,
        matchId: String,
    ): Boolean {
        if (appInForeground) return false
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
    ): Boolean {
        if (!shouldShowJoinMatchNotification(
                appInForeground,
                matchStatus,
                matchFoundNotificationsEnabled,
                backgroundUsageEnabled,
                hasPostNotificationsPermission,
                matchId,
            )
        ) {
            return false
        }
        return matchId != lastNotifiedMatchId
    }
}
