package com.rpsonline.app.platform

/** One shade match-found alert per [matchId] until [reset]. */
internal object MatchFoundNotificationGate {
    private var lastMatchId: String? = null

    fun tryNotify(matchId: String): Boolean {
        if (matchId.isBlank() || matchId == lastMatchId) return false
        lastMatchId = matchId
        return true
    }

    fun reset() {
        lastMatchId = null
    }
}
