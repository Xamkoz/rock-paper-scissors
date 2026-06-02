package com.rpsonline.app.data.repository

/** Whether a terminal match snapshot should end the local matchmaking session. */
fun shouldEndMatchmakingOnTerminalMatch(
    terminalMatchId: String,
    trackedMatchId: String?,
    listeningMatchId: String?,
    hasQueueEntry: Boolean,
    matchmakingInProgress: Boolean,
): Boolean {
    val trackingThisMatch = terminalMatchId == trackedMatchId ||
        terminalMatchId == listeningMatchId
    if (trackingThisMatch) return true
    if (hasQueueEntry && matchmakingInProgress) return false
    return !hasQueueEntry
}
