package com.rpsonline.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            elapsed = queueElapsedSecondsFromAnchor(anchorMs)
            delay(1_000)
        }
    }

    return anchorMs?.let { elapsed }
}
