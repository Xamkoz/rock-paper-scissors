package com.rpsonline.app.data.preferences

/** Top-bar feedback cycle: sound+haptic → haptic only → off. */
enum class SoundFeedbackMode {
    SOUND_AND_HAPTIC,
    HAPTIC_ONLY,
    OFF;

    fun allowsSound(): Boolean = this == SOUND_AND_HAPTIC

    fun allowsHaptic(): Boolean = this != OFF

    /** Match-found lobby clock ticks (including [HAPTIC_ONLY]); in-match clock still uses [allowsSound]. */
    fun allowsLobbyAlertTickSounds(): Boolean = this != OFF

    fun next(): SoundFeedbackMode = when (this) {
        SOUND_AND_HAPTIC -> HAPTIC_ONLY
        HAPTIC_ONLY -> OFF
        OFF -> SOUND_AND_HAPTIC
    }

    fun toStorage(): String = when (this) {
        SOUND_AND_HAPTIC -> STORAGE_SOUND
        HAPTIC_ONLY -> STORAGE_HAPTIC
        OFF -> STORAGE_OFF
    }

    companion object {
        const val STORAGE_SOUND = "sound"
        const val STORAGE_HAPTIC = "haptic"
        const val STORAGE_OFF = "off"

        fun fromStorage(value: String?): SoundFeedbackMode = when (value) {
            STORAGE_HAPTIC -> HAPTIC_ONLY
            STORAGE_OFF -> OFF
            else -> SOUND_AND_HAPTIC
        }
    }
}
