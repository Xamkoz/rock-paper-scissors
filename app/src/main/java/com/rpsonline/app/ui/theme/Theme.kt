package com.rpsonline.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.rpsonline.app.data.preferences.AppThemeStyle

@Composable
fun RpsTheme(
    style: AppThemeStyle,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = colorSchemeFor(context, style)
    val typography = Typography()
    val shapes = Shapes()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !style.isDark
            insetsController.isAppearanceLightNavigationBars = !style.isDark
        }
    }

    CompositionLocalProvider(
        LocalAppThemeStyle provides style,
        LocalRpsDarkTheme provides style.isDark,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            content = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .appBackground(style),
                ) {
                    content()
                }
            },
        )
    }
}
