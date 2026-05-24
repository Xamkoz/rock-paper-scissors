package com.rpsonline.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val GreenPrimary = Color(0xFF2E7D32)
private val GreenOnPrimary = Color(0xFFFFFFFF)
private val GreenPrimaryContainer = Color(0xFFC8E6C9)
private val GreenOnPrimaryContainer = Color(0xFF1B5E20)
private val GreenSecondary = Color(0xFF388E3C)
private val GreenTertiary = Color(0xFF81C784)

private val LightColors = lightColorScheme(
    primary = GreenPrimary,
    onPrimary = GreenOnPrimary,
    primaryContainer = GreenPrimaryContainer,
    onPrimaryContainer = GreenOnPrimaryContainer,
    secondary = GreenSecondary,
    onSecondary = GreenOnPrimary,
    secondaryContainer = Color(0xFFE8F5E9),
    onSecondaryContainer = GreenOnPrimaryContainer,
    tertiary = GreenTertiary,
    onTertiary = Color(0xFF1B5E20),
    tertiaryContainer = Color(0xFFE8F5E9),
    onTertiaryContainer = GreenOnPrimaryContainer,
    background = Color(0xFFF7FBF7),
    onBackground = Color(0xFF1A1C19),
    surface = Color(0xFFF7FBF7),
    onSurface = Color(0xFF1A1C19),
    surfaceVariant = Color(0xFFDEE5DE),
    onSurfaceVariant = Color(0xFF424940),
    surfaceContainer = Color(0xFFEBF2EB),
    surfaceContainerHigh = Color(0xFFE3EAE3),
    surfaceContainerHighest = Color(0xFFDEE5DE),
    outline = Color(0xFF727970),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CCC9A),
    onPrimary = Color(0xFF0A390F),
    primaryContainer = Color(0xFF1B5E20),
    onPrimaryContainer = GreenPrimaryContainer,
    secondary = Color(0xFFA5D6A7),
    onSecondary = Color(0xFF0A390F),
    secondaryContainer = Color(0xFF2E4A30),
    onSecondaryContainer = Color(0xFFC8E6C9),
    tertiary = GreenTertiary,
    onTertiary = Color(0xFF0A390F),
    tertiaryContainer = Color(0xFF2E4A30),
    onTertiaryContainer = Color(0xFFC8E6C9),
    background = Color(0xFF101410),
    onBackground = Color(0xFFE2E3DD),
    surface = Color(0xFF101410),
    onSurface = Color(0xFFE2E3DD),
    surfaceVariant = Color(0xFF424940),
    onSurfaceVariant = Color(0xFFC2C9BE),
    surfaceContainer = Color(0xFF1A1F1A),
    surfaceContainerHigh = Color(0xFF242924),
    surfaceContainerHighest = Color(0xFF2F342F),
    outline = Color(0xFF8C9388),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun RpsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent,
                contentColor = colorScheme.onBackground,
                content = content,
            )
        },
    )
}
