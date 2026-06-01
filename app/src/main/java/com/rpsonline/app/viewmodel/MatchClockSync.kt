package com.rpsonline.app.viewmodel

import com.rpsonline.app.data.model.Match

/** Match shape changes that mean a timeout request was handled — excludes live clock ticks. */
fun timeoutResolutionFingerprint(match: Match): String {
    val open = match.openRound()
    return buildString {
        append(match.status)
        append('|')
        append(match.lastActivityAt)
        append('|')
        append(match.rounds.size)
        append('|')
        append(match.player1Wins)
        append('|')
        append(match.player2Wins)
        append('|')
        append(open?.roundNumber)
        append('|')
        append(open?.deadline)
        append('|')
        append(open?.player1Submitted)
        append('|')
        append(open?.player2Submitted)
        append('|')
        append(open?.resolvedAt)
    }
}

/**
 * Keep a locally expired running clock pinned at zero until the server catches up,
 * so stale positive server ms does not bounce the widget back to 1s.
 */
fun reconcileClockBaseMs(
    running: Boolean,
    displayedSeconds: Int?,
    serverMs: Long,
): Pair<Long, Boolean> {
    if (running && displayedSeconds == 0 && serverMs > 0L) {
        return 0L to false
    }
    return serverMs to running
}
