package com.rpsonline.app.platform

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus

/**
 * Holds the lobby match for shade/FGS until the user opens the game or the match ends.
 * While [isLobbyAlertPhase], FGS must show match-found (not in-queue).
 */
internal object JoinMatchNotificationState {
    @Volatile
    private var lobbyMatch: Match? = null

    @Volatile
    private var lobbyAlertPhase = false

    @Volatile
    private var lobbyAlertMatchId: String? = null

    fun beginLobbyAlertPhase(match: Match) {
        lobbyAlertPhase = true
        lobbyAlertMatchId = match.id
        bindLobby(match)
    }

    fun endLobbyAlertPhase() {
        lobbyAlertPhase = false
        lobbyAlertMatchId = null
    }

    fun isLobbyAlertPhase(): Boolean = lobbyAlertPhase

    fun bindLobby(match: Match) {
        if (match.status == MatchStatus.LOBBY) {
            lobbyMatch = match
        }
    }

    fun lobbyMatch(): Match? = lobbyMatch

    fun activeMatchId(): String? = lobbyAlertMatchId?.takeIf { lobbyAlertPhase } ?: lobbyMatch?.id

    fun clear() {
        lobbyMatch = null
        endLobbyAlertPhase()
    }
}
