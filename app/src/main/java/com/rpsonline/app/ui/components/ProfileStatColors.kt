package com.rpsonline.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.rpsonline.app.data.preferences.AppThemeStyle
import com.rpsonline.app.ui.theme.currentAppThemeStyle

/** Green for gains, red for losses — shared by match history, opponents, and in-game ELO labels. */
@Composable
fun eloDeltaColor(delta: Int): Color = when {
    delta > 0 -> MaterialTheme.colorScheme.primary
    delta < 0 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun eloDeltaColor(delta: Double): Color = when {
    delta > 0 -> MaterialTheme.colorScheme.primary
    delta < 0 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Highlight color for profile stats (ELO, win rates) — follows the active theme, not leaderboard spectrum. */
@Composable
fun profileStatValueColor(): Color =
    if (currentAppThemeStyle() == AppThemeStyle.CYBERPUNK) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
