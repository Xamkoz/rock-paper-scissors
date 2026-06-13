package com.rpsonline.app.ui.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short pulse paired with match clock ticks in the low-time window. */
object MatchClockHaptics {
    private var vibrator: Vibrator? = null

    fun initialize(context: Context) {
        if (vibrator != null) return
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun cancel() {
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
    }

    fun pulseTick() {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.cancel()
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.cancel()
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        TICK_PULSE_MS,
                        TICK_AMPLITUDE,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(TICK_PULSE_MS)
            }
        } catch (_: Exception) {
        }
    }

    private const val TICK_PULSE_MS = 32L
    private const val TICK_AMPLITUDE = 220
}
