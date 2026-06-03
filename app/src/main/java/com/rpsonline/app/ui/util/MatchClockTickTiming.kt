package com.rpsonline.app.ui.util

/** True once [delayMs] have elapsed on the monotonic clock since the local tick loop started. */
fun matchClockHapticDelayElapsed(
    anchorElapsedMs: Long,
    nowElapsedMs: Long,
    delayMs: Long = MatchClockSoundPolicy.HAPTIC_AFTER_CLOCK_RUNNING_MS,
): Boolean = nowElapsedMs - anchorElapsedMs >= delayMs
