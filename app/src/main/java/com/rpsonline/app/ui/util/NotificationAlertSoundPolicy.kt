package com.rpsonline.app.ui.util

import android.content.Context
import android.media.AudioManager
import android.os.Build

/** Whether match-found notification ticks should be audible (system notification volume). */
object NotificationAlertSoundPolicy {
    fun notificationSoundsAudible(context: Context): Boolean {
        val am = context.applicationContext.getSystemService(AudioManager::class.java) ?: return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            am.isStreamMute(AudioManager.STREAM_NOTIFICATION)
        ) {
            return false
        }
        return am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0
    }
}
