package com.rpsonline.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.rpsonline.app.data.preferences.SoundFeedbackMode

@Composable
fun ClockSoundMuteButton(
    mode: SoundFeedbackMode,
    onModeChange: (SoundFeedbackMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, contentDescription) = when (mode) {
        SoundFeedbackMode.SOUND_AND_HAPTIC -> {
            Icons.AutoMirrored.Outlined.VolumeUp to "Sound and haptic on"
        }
        SoundFeedbackMode.HAPTIC_ONLY -> {
            Icons.Outlined.Vibration to "Haptic only"
        }
        SoundFeedbackMode.OFF -> {
            Icons.AutoMirrored.Outlined.VolumeOff to "Sound and haptic off"
        }
    }
    TopBarSegmentedIconButton(
        onClick = { onModeChange(mode.next()) },
        icon = icon,
        active = mode != SoundFeedbackMode.OFF,
        contentDescription = contentDescription,
        modifier = modifier,
    )
}
