package com.rpsonline.app.ui.util

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.preferences.SoundPreferences
import com.rpsonline.app.data.repository.MatchSessionMonitor

object MatchClockSoundPolicy {
    /** In-match tick sounds wait this long after the local clock tick loop starts. */
    const val TICK_AFTER_CLOCK_RUNNING_MS = 3_000L

    /** Haptic pulses wait this long after the local match-clock tick loop starts; sound uses [TICK_AFTER_CLOCK_RUNNING_MS]. */
    const val HAPTIC_AFTER_CLOCK_RUNNING_MS = 7_000L

    fun shouldRunMatchClock(context: Context): Boolean {
        val mode = SoundPreferences(context).getMode()
        if (!mode.allowsSound() && !mode.allowsHaptic()) return false
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        val match = MatchSessionMonitor.activeMatch.value ?: return false
        if (match.status != MatchStatus.ACTIVE || !match.isParticipant(uid)) return false
        return match.isPlayerClockRunning(uid)
    }

    fun shouldPlayMatchClockSound(context: Context): Boolean {
        if (!SoundPreferences(context).getMode().allowsSound()) return false
        return shouldRunMatchClock(context)
    }

    /** @deprecated Use [shouldPlayMatchClockSound]. */
    fun shouldPlayMatchClock(context: Context): Boolean = shouldPlayMatchClockSound(context)

}
