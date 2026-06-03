package com.rpsonline.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rpsonline.app.ui.segment.SevenSegmentColonBlink
import kotlinx.coroutines.delay

/** Elapsed whole seconds since queue `joinedAt` (anchor must already be [normalizeQueueAnchorMs]). */
fun queueElapsedSecondsFromAnchor(
    anchorMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): Long {
    if (anchorMs <= 0L) return 0L
    return ((nowMs - anchorMs) / 1_000).coerceAtLeast(0L)
}

/**
 * Ticks every second while [anchorMs] is set. Prefer this for queue UI instead of a one-shot snapshot.
 */
@Composable
fun rememberQueueElapsedSeconds(anchorMs: Long?): Long? {
    var elapsed by remember(anchorMs) { mutableLongStateOf(anchorMs?.let { 0L } ?: 0L) }

    LaunchedEffect(anchorMs) {
        if (anchorMs == null) {
            return@LaunchedEffect
        }
        while (true) {
            val nowMs = System.currentTimeMillis()
            elapsed = queueElapsedSecondsFromAnchor(anchorMs, nowMs)
            delay(
                SevenSegmentColonBlink.delayMsUntilNextSecondBoundary(anchorMs, nowMs)
                    .coerceAtLeast(1L),
            )
        }
    }

    return anchorMs?.let { elapsed }
}

/** 500ms on / 500ms off, aligned to the same second phase as [rememberQueueElapsedSeconds]. */
@Composable
fun rememberColonBlinkLit(showLiveTime: Boolean, timerAnchorMs: Long?): Boolean {
    if (!showLiveTime) return false
    var nowMs by remember(timerAnchorMs) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(showLiveTime, timerAnchorMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(SevenSegmentColonBlink.delayUntilToggle(timerAnchorMs, nowMs).coerceAtLeast(1L))
        }
    }
    return SevenSegmentColonBlink.isLit(showLiveTime, timerAnchorMs, nowMs)
}
