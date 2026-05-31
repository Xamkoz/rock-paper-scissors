package com.rpsonline.app.ui.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rpsonline.app.domain.GameRules
import com.rpsonline.app.ui.components.MoveSlotLockedAlpha

@Composable
fun CircularGameClock(
    secondsRemaining: Int,
    isRunning: Boolean,
    compact: Boolean,
    ringFullSeconds: Int = GameRules.CLOCK_RING_FULL_SECONDS,
    modifier: Modifier = Modifier,
) {
    val activeColor = when {
        secondsRemaining <= 10 -> MaterialTheme.colorScheme.error
        secondsRemaining <= 30 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val ringColor = if (isRunning) {
        activeColor
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    }
    val textColor = if (isRunning) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
    val trackColor = if (isRunning) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }
    val timerSize = if (compact) 56.dp else 72.dp
    val ringFull = ringFullSeconds.toFloat()
    val ringSeconds = secondsRemaining.coerceAtMost(ringFullSeconds)
    val progress = (ringSeconds / ringFull).coerceIn(0f, 1f)
    val strokeWidth = if (isRunning) {
        ProgressIndicatorDefaults.CircularStrokeWidth
    } else {
        ProgressIndicatorDefaults.CircularStrokeWidth * 0.7f
    }

    Box(
        modifier = modifier.alpha(if (isRunning) 1f else MoveSlotLockedAlpha),
        contentAlignment = Alignment.Center,
    ) {
        @Suppress("DEPRECATION")
        CircularProgressIndicator(
            progress = progress.coerceIn(0f, 1f),
            modifier = Modifier
                .size(timerSize)
                .graphicsLayer { scaleX = -1f },
            color = ringColor,
            trackColor = trackColor,
            strokeWidth = strokeWidth,
        )
        Text(
            text = formatClockSeconds(secondsRemaining),
            style = if (compact) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.titleLarge
            },
            fontWeight = if (isRunning) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
        )
    }
}
