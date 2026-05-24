package com.rpsonline.app.ui.theme

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.rpsonline.app.data.preferences.AppBackground
import com.rpsonline.app.data.preferences.BackgroundPreferences

@Stable
class BackgroundController(context: Context) {
    private val preferences = BackgroundPreferences(context)

    private val backgroundState = mutableStateOf(preferences.getBackground())

    val background: AppBackground
        get() = backgroundState.value

    fun setBackground(background: AppBackground) {
        if (backgroundState.value == background) return
        preferences.setBackground(background)
        backgroundState.value = background
    }
}

val LocalBackgroundController = staticCompositionLocalOf<BackgroundController> {
    error("BackgroundController not provided")
}
