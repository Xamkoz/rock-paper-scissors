package com.rpsonline.app.platform

import com.rpsonline.app.ui.segment.SegmentedLobbyAlertTiming

/** Aligns with server lobby ready deadline (20s). */
object MatchLobbyNotificationTiming {
    const val LOBBY_ALERT_MS = SegmentedLobbyAlertTiming.ALERT_MS

    fun isWithinAlertWindow(startedAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        SegmentedLobbyAlertTiming.isWithinAlertWindow(startedAtMs, nowMs)

    fun remainingAlertSeconds(startedAtMs: Long, nowMs: Long = System.currentTimeMillis()): Long =
        SegmentedLobbyAlertTiming.remainingSeconds(startedAtMs, nowMs)
}
