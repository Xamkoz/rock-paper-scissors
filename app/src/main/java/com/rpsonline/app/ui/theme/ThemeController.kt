package com.rpsonline.app.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.rpsonline.app.data.preferences.ThemeMode
import com.rpsonline.app.data.preferences.ThemePreferences

@Stable
class ThemeController(context: Context) {
    private val preferences = ThemePreferences(context)

    private val themeModeState = mutableStateOf(preferences.getThemeMode())

    val themeMode: ThemeMode
        get() = themeModeState.value

    fun setThemeMode(mode: ThemeMode) {
        if (themeModeState.value == mode) return
        preferences.setThemeMode(mode)
        themeModeState.value = mode
    }

    @Composable
    fun useDarkTheme(): Boolean = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
}

val LocalThemeController = staticCompositionLocalOf<ThemeController> {
    error("ThemeController not provided")
}
