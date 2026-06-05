package com.rpsonline.app.ui.util

import android.content.Context
import android.media.AudioAttributes
import android.os.Build

/** Attribution-tagged context for short game sound effects (manifest [ATTRIBUTION_TAG]). */
object GameAudioContext {
    const val ATTRIBUTION_TAG = "gameAudio"

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return context
        return context.createAttributionContext(ATTRIBUTION_TAG)
    }

    fun gameSoundAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    fun notificationSoundAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
}
