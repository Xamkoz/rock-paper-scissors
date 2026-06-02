package com.rpsonline.app.viewmodel

/** Local submit optimism only applies while the same round is still open on the server. */
internal fun computeHasSubmittedMove(
    alreadySubmitted: Boolean,
    locallySubmittedRound: Int?,
    openRoundNumber: Int?,
    matchActive: Boolean,
): Boolean {
    val localSubmitPending = locallySubmittedRound != null &&
        matchActive &&
        openRoundNumber != null &&
        locallySubmittedRound == openRoundNumber
    return alreadySubmitted || localSubmitPending
}

/** True when the UI should show "waiting for opponent" under the move picker. */
internal fun shouldShowWaitingForOpponentMessage(
    hasSubmittedMove: Boolean,
    opponentHasSubmitted: Boolean,
    isSubmitting: Boolean,
    isResolvingTimeout: Boolean,
    hasOpenRound: Boolean,
    hasPanelOutcome: Boolean,
    roundRecapActive: Boolean = false,
    awaitingServerRoundResolve: Boolean = false,
    opponentSubmittedOnServer: Boolean = false,
): Boolean {
    if (
        isSubmitting ||
        isResolvingTimeout ||
        hasPanelOutcome ||
        !hasOpenRound ||
        roundRecapActive ||
        awaitingServerRoundResolve
    ) {
        return false
    }
    if (opponentSubmittedOnServer) return false
    return hasSubmittedMove && !opponentHasSubmitted
}
