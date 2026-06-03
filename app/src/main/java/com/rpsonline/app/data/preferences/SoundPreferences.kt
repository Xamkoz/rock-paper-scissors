package com.rpsonline.app.data.preferences

import android.content.Context

private const val PREFS_NAME = "rps_sound_prefs"
private const val KEY_CLOCK_MUTED = "clock_muted"
private const val KEY_FEEDBACK_MODE = "feedback_mode"

class SoundPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(): SoundFeedbackMode {
        if (prefs.contains(KEY_FEEDBACK_MODE)) {
            return SoundFeedbackMode.fromStorage(prefs.getString(KEY_FEEDBACK_MODE, null))
        }
        return if (prefs.getBoolean(KEY_CLOCK_MUTED, false)) {
            SoundFeedbackMode.OFF
        } else {
            SoundFeedbackMode.SOUND_AND_HAPTIC
        }
    }

    fun setMode(mode: SoundFeedbackMode) {
        prefs.edit()
            .putString(KEY_FEEDBACK_MODE, mode.toStorage())
            .apply()
    }

    /** @deprecated Use [getMode] — true when feedback is fully off. */
    fun isClockMuted(): Boolean = getMode() == SoundFeedbackMode.OFF

    /** @deprecated Use [setMode]. */
    fun setClockMuted(muted: Boolean) {
        setMode(if (muted) SoundFeedbackMode.OFF else SoundFeedbackMode.SOUND_AND_HAPTIC)
    }
}
