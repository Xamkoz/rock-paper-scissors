package com.rpsonline.app.data.repository

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus

/** Avoid cached LOBBY snapshots overwriting a live ACTIVE match after background resume. */
fun shouldReplaceActiveMatch(
    incoming: Match,
    current: Match?,
    fromCache: Boolean,
): Boolean {
    if (current == null || current.id != incoming.id) {
        if (
            current != null &&
            current.id != incoming.id &&
            (incoming.status == MatchStatus.COMPLETED || incoming.status == MatchStatus.ABANDONED) &&
            (current.status == MatchStatus.LOBBY || current.status == MatchStatus.ACTIVE)
        ) {
            return false
        }
        return true
    }
    if (current.status == MatchStatus.ACTIVE && incoming.status == MatchStatus.LOBBY) return false
    if (current.status == MatchStatus.LOBBY && incoming.status == MatchStatus.ACTIVE) return true
    if (
        fromCache &&
        current.status == MatchStatus.ACTIVE &&
        (incoming.status == MatchStatus.LOBBY || incoming.status == MatchStatus.ACTIVE)
    ) {
        return false
    }
    if (incoming.lastActivityAt > current.lastActivityAt) return true
    if (incoming.status != current.status) {
        return activeMatchStatusRank(incoming.status) > activeMatchStatusRank(current.status)
    }
    return !fromCache && incoming.lastActivityAt >= current.lastActivityAt
}

private fun activeMatchStatusRank(status: MatchStatus): Int = when (status) {
    MatchStatus.LOBBY -> 1
    MatchStatus.ACTIVE -> 2
    MatchStatus.COMPLETED -> 3
    MatchStatus.ABANDONED -> 3
}
