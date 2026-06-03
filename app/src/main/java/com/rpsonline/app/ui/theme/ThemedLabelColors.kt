package com.rpsonline.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.rpsonline.app.data.preferences.AppThemeStyle

/** Accent label on styled palettes (Cyberpunk, Cosmos, Fire); neutral on Light/Dark. */
@Composable
fun themedPrimaryLabelColor(): Color = outlinedActionLabelColor(
    style = currentAppThemeStyle(),
    onSurface = MaterialTheme.colorScheme.onSurface,
    primary = MaterialTheme.colorScheme.primary,
)

/** Shared by outlined nav buttons, player names, and profile/opponent row labels. */
fun outlinedActionLabelColor(
    style: AppThemeStyle,
    onSurface: Color,
    primary: Color,
): Color = when (style) {
    AppThemeStyle.LIGHT, AppThemeStyle.DARK -> onSurface
    else -> primary
}
