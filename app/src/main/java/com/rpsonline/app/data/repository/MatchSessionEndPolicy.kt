package com.rpsonline.app.data.repository

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus

/** Whether clearing [activeMatchId] on the user doc should drop local session state without a server fetch. */
fun shouldClearActiveMatchOnUserDocClear(
    finalizedMatchId: String?,
    currentMatch: Match?,
    matchmakingInProgress: Boolean,
    hasPendingGameNavigation: Boolean,
): Boolean {
    if (finalizedMatchId.isNullOrBlank()) return true
    if (!matchmakingInProgress && !hasPendingGameNavigation) return true
    val current = currentMatch ?: return false
    return current.id == finalizedMatchId &&
        (current.status == MatchStatus.COMPLETED || current.status == MatchStatus.ABANDONED)
}
