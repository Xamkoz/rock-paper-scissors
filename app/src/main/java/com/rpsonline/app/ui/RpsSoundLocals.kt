package com.rpsonline.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.rpsonline.app.data.preferences.SoundFeedbackMode

val LocalSoundFeedbackMode = staticCompositionLocalOf { SoundFeedbackMode.SOUND_AND_HAPTIC }
