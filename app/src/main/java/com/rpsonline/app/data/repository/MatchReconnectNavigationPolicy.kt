package com.rpsonline.app.data.repository

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus

/** Background matchmaking must not auto-open the game until the user taps match found or returns to the app. */
fun requiresExplicitMatchLaunchWhenBackgrounded(
    backgroundUsageEnabled: Boolean,
    appInForeground: Boolean,
): Boolean = backgroundUsageEnabled && !appInForeground

/** Server ready-confirm and game navigation while backgrounded require a notification tap or foreground restore. */
fun shouldAllowPassiveMatchJoinWhenBackgrounded(
    backgroundUsageEnabled: Boolean,
    appInForeground: Boolean,
    explicitLaunchMatchId: String?,
    matchId: String,
): Boolean {
    if (!requiresExplicitMatchLaunchWhenBackgrounded(backgroundUsageEnabled, appInForeground)) {
        return true
    }
    return explicitLaunchMatchId == matchId
}

/** Whether the app should open the game screen without tapping Reconnect. */
fun shouldAutoNavigateToLiveMatch(
    match: Match,
    userId: String,
    fromCache: Boolean,
    matchmakingInProgress: Boolean,
    autoNavigationSuppressed: Boolean,
    resumingFromQueueOrJoin: Boolean,
    backgroundUsageEnabled: Boolean = false,
    appInForeground: Boolean = true,
    explicitLaunchMatchId: String? = null,
): Boolean {
    if (fromCache || autoNavigationSuppressed) return false
    if (!match.isParticipant(userId) || !match.isLiveForReconnect()) return false
    if (
        requiresExplicitMatchLaunchWhenBackgrounded(backgroundUsageEnabled, appInForeground) &&
        explicitLaunchMatchId != match.id
    ) {
        return false
    }
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
