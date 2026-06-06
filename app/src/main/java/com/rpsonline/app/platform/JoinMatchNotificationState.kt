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

    @Volatile
    private var lobbyAlertStartedAtMs: Long = 0L

    fun beginLobbyAlertPhase(match: Match) {
        lobbyAlertPhase = true
        lobbyAlertMatchId = match.id
        lobbyAlertStartedAtMs = System.currentTimeMillis()
        bindLobby(match)
    }

    fun endLobbyAlertPhase() {
        lobbyAlertPhase = false
        lobbyAlertMatchId = null
        lobbyAlertStartedAtMs = 0L
    }

    fun isLobbyAlertPhase(): Boolean = lobbyAlertPhase

    /** True for [MatchLobbyNotificationTiming.LOBBY_ALERT_MS] after match-found is posted. */
    fun isWithinProminentAlertWindow(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!lobbyAlertPhase) return false
        return MatchLobbyNotificationTiming.isWithinAlertWindow(lobbyAlertStartedAtMs, nowMs)
    }

    fun lobbyAlertStartedAtMs(): Long? = lobbyAlertStartedAtMs.takeIf { it > 0L }

    fun bindLobby(match: Match) {
        when (match.status) {
            MatchStatus.LOBBY -> lobbyMatch = match
            MatchStatus.ACTIVE -> {
                if (lobbyMatch?.id == match.id || lobbyAlertMatchId == match.id) {
                    lobbyMatch = match
                }
                if (match.hasGameplayStarted()) {
                    endLobbyAlertPhase()
                }
            }
            else -> {
                if (lobbyMatch?.id == match.id || lobbyAlertMatchId == match.id) {
                    lobbyMatch = null
                    endLobbyAlertPhase()
                }
            }
        }
    }

    fun lobbyMatch(): Match? = lobbyMatch

    fun activeMatchId(): String? = lobbyAlertMatchId?.takeIf { lobbyAlertPhase } ?: lobbyMatch?.id

    fun clear() {
        lobbyMatch = null
        endLobbyAlertPhase()
    }
}
