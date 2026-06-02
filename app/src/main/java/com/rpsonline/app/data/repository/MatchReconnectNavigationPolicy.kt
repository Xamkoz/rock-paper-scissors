package com.rpsonline.app.data.repository

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus

/** Whether the app should open the game screen without tapping Reconnect. */
fun shouldAutoNavigateToLiveMatch(
    match: Match,
    userId: String,
    fromCache: Boolean,
    matchmakingInProgress: Boolean,
    autoNavigationSuppressed: Boolean,
    resumingFromQueueOrJoin: Boolean,
): Boolean {
    if (fromCache || autoNavigationSuppressed) return false
    if (!match.isParticipant(userId) || !match.isLiveForReconnect()) return false
    return when (match.status) {
        MatchStatus.ACTIVE, MatchStatus.LOBBY ->
            matchmakingInProgress || !resumingFromQueueOrJoin
        else -> false
    }
}

/** Home reconnect card: live ACTIVE match while not searching for a new game. */
fun shouldOfferReconnectToMatch(
    activeMatchId: String?,
    matchmakingInProgress: Boolean,
    openingMatchId: String?,
): Boolean =
    activeMatchId != null &&
        !matchmakingInProgress &&
        openingMatchId == null
