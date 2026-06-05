package com.rpsonline.app.ui.util

/** True once [delayMs] have elapsed on the monotonic clock since the local tick loop started. */
fun matchClockHapticDelayElapsed(
    anchorElapsedMs: Long,
    nowElapsedMs: Long,
    delayMs: Long = MatchClockSoundPolicy.HAPTIC_AFTER_CLOCK_RUNNING_MS,
): Boolean = nowElapsedMs - anchorElapsedMs >= delayMs

const val LOBBY_ALERT_TICK_MS = 500L

fun currentLobbyAlertBeatIndex(
    anchorMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): Long = ((nowMs - anchorMs).coerceAtLeast(0L) / LOBBY_ALERT_TICK_MS)

/** Milliseconds until the next anchor-aligned lobby alert beat (tick + haptic). */
fun delayMsUntilNextLobbyAlertBeat(
    anchorMs: Long,
    beatIndex: Long,
    nowMs: Long = System.currentTimeMillis(),
): Long = (anchorMs + beatIndex * LOBBY_ALERT_TICK_MS - nowMs).coerceAtLeast(0L)
