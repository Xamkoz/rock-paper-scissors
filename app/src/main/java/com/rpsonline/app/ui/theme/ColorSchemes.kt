package com.rpsonline.app.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.rpsonline.app.data.preferences.AppThemeStyle

/** Menu swatch colors; canonical light/dark so previews match their labels on Android 12+. */
internal fun themeIconPreviewScheme(context: Context, style: AppThemeStyle): ColorScheme = when (style) {
    AppThemeStyle.LIGHT -> previewLightColorScheme()
    AppThemeStyle.DARK -> previewDarkColorScheme()
    else -> colorSchemeFor(context, style)
}

fun colorSchemeFor(context: Context, style: AppThemeStyle): ColorScheme = when (style) {
    AppThemeStyle.LIGHT -> systemLightColorScheme(context)
    AppThemeStyle.DARK -> systemDarkColorScheme(context)
    AppThemeStyle.CYBERPUNK -> darkColorScheme(
        primary = NeonCyan,
        onPrimary = Color(0xFF001820),
        primaryContainer = Color(0xFF003D4D),
        onPrimaryContainer = Color(0xFFB8FCFF),
        secondary = NeonMagenta,
        onSecondary = Color(0xFF2A0010),
        secondaryContainer = Color(0xFF4D1030),
        onSecondaryContainer = Color(0xFFFFB8D0),
        tertiary = NeonYellow,
        onTertiary = Color(0xFF1A1400),
        tertiaryContainer = Color(0xFF3D3200),
        onTertiaryContainer = Color(0xFFFFF0A8),
        background = CyberDeepBg,
        onBackground = CyberOnBg,
        surface = CyberSurface,
        onSurface = CyberOnBg,
        surfaceVariant = Color(0xFF1E2840),
        onSurfaceVariant = CyberMuted,
        surfaceContainer = CyberPanel,
        surfaceContainerHigh = CyberPanelHigh,
        surfaceContainerHighest = Color(0xFF242E48),
        surfaceContainerLow = Color(0xFF0E1424),
        surfaceContainerLowest = CyberDeepBg,
        outline = NeonCyan.copy(alpha = 0.45f),
        outlineVariant = Color(0xFF2A3550),
        error = Color(0xFFFF3D5A),
        onError = Color(0xFF2A0008),
        errorContainer = Color(0xFF5C1020),
        onErrorContainer = Color(0xFFFFB8C8),
    )

    AppThemeStyle.COSMOS -> darkColorScheme(
        primary = Color(0xFFB388FF),
        onPrimary = Color(0xFF1A0A30),
        primaryContainer = Color(0xFF3D2060),
        onPrimaryContainer = Color(0xFFE8D4FF),
        secondary = Color(0xFF80D8FF),
        onSecondary = Color(0xFF001828),
        secondaryContainer = Color(0xFF123850),
        onSecondaryContainer = Color(0xFFB8ECFF),
        tertiary = Color(0xFFFFB74D),
        onTertiary = Color(0xFF2A1400),
        tertiaryContainer = Color(0xFF4A3000),
        onTertiaryContainer = Color(0xFFFFE0B2),
        background = Color(0xFF050510),
        onBackground = Color(0xFFE8E4FF),
        surface = Color(0xFF0A0A18),
        onSurface = Color(0xFFE8E4FF),
        surfaceVariant = Color(0xFF1E1A38),
        onSurfaceVariant = Color(0xFFB8B0D8),
        surfaceContainer = Color(0xFF12102A),
        surfaceContainerHigh = Color(0xFF1A1838),
        surfaceContainerHighest = Color(0xFF242048),
        surfaceContainerLow = Color(0xFF080818),
        surfaceContainerLowest = Color(0xFF050510),
        outline = Color(0xFFB388FF).copy(alpha = 0.4f),
        outlineVariant = Color(0xFF2A2548),
        error = Color(0xFFFF8A9A),
        onError = Color(0xFF3A0010),
        errorContainer = Color(0xFF5C1830),
        onErrorContainer = Color(0xFFFFD0D8),
    )

    AppThemeStyle.FIRE -> darkColorScheme(
        primary = Color(0xFFFF5722),
        onPrimary = Color(0xFF2A0800),
        primaryContainer = Color(0xFF6D1F00),
        onPrimaryContainer = Color(0xFFFFD4C8),
        secondary = Color(0xFFFFD54F),
        onSecondary = Color(0xFF2A2000),
        secondaryContainer = Color(0xFF5C4800),
        onSecondaryContainer = Color(0xFFFFF0B0),
        tertiary = Color(0xFFFF8A65),
        onTertiary = Color(0xFF2A1008),
        tertiaryContainer = Color(0xFF5C2808),
        onTertiaryContainer = Color(0xFFFFD4C8),
        background = Color(0xFF120606),
        onBackground = Color(0xFFFFE8E0),
        surface = Color(0xFF1A0808),
        onSurface = Color(0xFFFFE8E0),
        surfaceVariant = Color(0xFF3A1810),
        onSurfaceVariant = Color(0xFFD8B0A8),
        surfaceContainer = Color(0xFF220C08),
        surfaceContainerHigh = Color(0xFF2E1208),
        surfaceContainerHighest = Color(0xFF3A1810),
        surfaceContainerLow = Color(0xFF180404),
        surfaceContainerLowest = Color(0xFF120606),
        outline = Color(0xFFFF5722).copy(alpha = 0.45f),
        outlineVariant = Color(0xFF4A2018),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF410008),
        errorContainer = Color(0xFF6D1020),
        onErrorContainer = Color(0xFFFFDAD6),
    )
}

private fun systemLightColorScheme(context: Context): ColorScheme {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(context)
    } else {
        lightColorScheme()
    }
}

/** Static light palette for theme picker previews (dynamic wallpaper colors can look inverted in swatches). */
private fun previewLightColorScheme(): ColorScheme = lightColorScheme()

private fun systemDarkColorScheme(context: Context): ColorScheme {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        darkColorScheme()
    }
}

/** Static dark palette for theme picker previews. */
private fun previewDarkColorScheme(): ColorScheme = darkColorScheme()
