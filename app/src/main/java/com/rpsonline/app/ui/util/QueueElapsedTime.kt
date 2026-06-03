package com.rpsonline.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rpsonline.app.ui.segment.SevenSegmentColonBlink
import kotlinx.coroutines.delay

/** Max recomposition rate for top-bar segmented colon/spinner animation. */
const val TOP_BAR_SEGMENTED_UPDATE_INTERVAL_MS = 100L

/** Shared frame clock for [TopBarSegmentedFrameTimeProvider]; null outside the provider. */
val LocalTopBarSegmentedFrameTimeMs = compositionLocalOf<Long?> { null }

/** Delay until the next top-bar segmented tick (phase-aligned, min [TOP_BAR_SEGMENTED_UPDATE_INTERVAL_MS]). */
fun segmentedDisplayTickDelayMs(
    timerAnchorMs: Long?,
    lastTickAtMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): Long {
    val rateLimitRemainingMs =
        (TOP_BAR_SEGMENTED_UPDATE_INTERVAL_MS - (nowMs - lastTickAtMs)).coerceAtLeast(0L)
    val phaseDelayMs = SevenSegmentColonBlink.delayUntilToggle(timerAnchorMs, nowMs)
    return maxOf(phaseDelayMs, rateLimitRemainingMs).coerceAtLeast(1L)
}

/**
 * Single tick loop for [TopBarSegmentedStatusRow] so colon and spinner share one update per
 * [TOP_BAR_SEGMENTED_UPDATE_INTERVAL_MS].
 */
@Composable
fun TopBarSegmentedFrameTimeProvider(
    timerAnchorMs: Long?,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    var frameTimeMs by remember(timerAnchorMs, enabled) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(timerAnchorMs, enabled) {
        if (!enabled) {
            return@LaunchedEffect
        }
        var lastTickAtMs = 0L
        while (true) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastTickAtMs >= TOP_BAR_SEGMENTED_UPDATE_INTERVAL_MS) {
                frameTimeMs = nowMs
                lastTickAtMs = nowMs
            }
            delay(segmentedDisplayTickDelayMs(timerAnchorMs, lastTickAtMs, nowMs))
        }
    }
    if (enabled) {
        CompositionLocalProvider(LocalTopBarSegmentedFrameTimeMs provides frameTimeMs) {
            content()
        }
    } else {
        content()
    }
}

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
    val frameTimeMs = LocalTopBarSegmentedFrameTimeMs.current
        ?: rememberThrottledSegmentedFrameTimeMs(timerAnchorMs)
    return SevenSegmentColonBlink.isLit(showLiveTime, timerAnchorMs, frameTimeMs)
}

@Composable
private fun rememberThrottledSegmentedFrameTimeMs(timerAnchorMs: Long?): Long {
    var frameTimeMs by remember(timerAnchorMs) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(timerAnchorMs) {
        var lastTickAtMs = 0L
        while (true) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastTickAtMs >= TOP_BAR_SEGMENTED_UPDATE_INTERVAL_MS) {
                frameTimeMs = nowMs
                lastTickAtMs = nowMs
            }
            delay(segmentedDisplayTickDelayMs(timerAnchorMs, lastTickAtMs, nowMs))
        }
    }
    return frameTimeMs
}
