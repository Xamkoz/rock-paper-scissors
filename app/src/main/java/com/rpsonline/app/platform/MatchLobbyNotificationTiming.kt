package com.rpsonline.app.platform

/** Aligns with server [LOBBY_READY_MS] / pre-game ready deadline (20s). */
internal object MatchLobbyNotificationTiming {
    const val LOBBY_ALERT_MS = 20_000L
}
