package com.rpsonline.app.data.repository

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus

/** Whether [pendingMatchId] should open the game screen now. */
fun shouldOpenPendingGameScreen(
    pendingMatchId: String,
    sessionMatch: Match?,
): Boolean {
    if (sessionMatch == null) return true
    if (sessionMatch.id != pendingMatchId) return false
    return sessionMatch.status == MatchStatus.ACTIVE ||
        sessionMatch.status == MatchStatus.LOBBY
}

/** Pending navigation targets a match that is already over — drop it. */
fun shouldDropPendingGameNavigation(
    pendingMatchId: String,
    sessionMatch: Match?,
): Boolean =
    sessionMatch?.id == pendingMatchId &&
        (sessionMatch.status == MatchStatus.COMPLETED ||
            sessionMatch.status == MatchStatus.ABANDONED)
