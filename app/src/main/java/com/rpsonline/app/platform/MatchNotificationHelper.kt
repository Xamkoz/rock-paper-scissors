package com.rpsonline.app.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rpsonline.app.R
import com.rpsonline.app.ui.segment.SegmentedNotificationStatus
import com.rpsonline.app.ui.segment.SegmentedSpinnerStyle
import com.rpsonline.app.ui.segment.TopBarStatusRowSpec
import com.rpsonline.app.ui.util.triggerMatchFoundFeedback

object MatchNotificationHelper {
    /** v2 channel so installs pick up the default notification sound. */
    private const val MATCH_FOUND_CHANNEL_ID = "match_found_alert"
    const val MATCH_FOUND_NOTIFICATION_ID = 2001

    private fun defaultNotificationSoundUri(context: Context) =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val soundUri = defaultNotificationSoundUri(context)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            MATCH_FOUND_CHANNEL_ID,
            context.getString(R.string.match_found_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.match_found_notification_channel_desc)
            setSound(soundUri, audioAttributes)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** Background-only: heads-up alert with the default notification sound. */
    fun showMatchFound(context: Context, matchId: String, opponentName: String?) {
        if (AppForegroundTracker.isInForeground) return
        if (!NotificationPermissionHelper.hasPostNotificationsPermission(context)) {
            triggerMatchFoundFeedback(context, matchId)
            return
        }
        if (MatchmakingForegroundService.isRunning()) {
            MatchmakingForegroundService.requestLaunchAlert()
        }
        ensureChannels(context)
        val openAppIntent = MatchLaunchHelper.buildLaunchIntent(context, matchId)
        val pendingIntent = PendingIntent.getActivity(
            context,
            matchId.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = opponentName?.takeIf { it.isNotBlank() }?.let { name ->
            context.getString(R.string.match_found_notification_body_vs, name)
        } ?: context.getString(R.string.match_found_notification_body)
        val timerAnchorMs = System.currentTimeMillis()
        val segmentedDisplay = TopBarStatusRowSpec(
            status = SegmentedNotificationStatus.MATCH_FOUND,
            onlineCount = SegmentedNotificationState.onlineCount,
            showLiveTime = true,
            elapsedSeconds = 0L,
            timerAnchorMs = timerAnchorMs,
            spinnerStyle = SegmentedSpinnerStyle.QUEUE,
        )
        val defaultSound = defaultNotificationSoundUri(context)
        val notification = NotificationCompat.Builder(context, MATCH_FOUND_CHANNEL_ID)
            .setSmallIcon(RpsStatusBarNotification.smallIconRes)
            .setSortKey("match_found")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setSound(defaultSound)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .also { builder ->
                SevenSegmentNotificationRenderer.applySegmentedStatusViews(
                    builder = builder,
                    context = context,
                    state = segmentedDisplay,
                    accessibilitySummary = body,
                )
            }
            .build()
        NotificationManagerCompat.from(context).notify(MATCH_FOUND_NOTIFICATION_ID, notification)
    }

    fun dismissMatchFound(context: Context) {
        NotificationManagerCompat.from(context).cancel(MATCH_FOUND_NOTIFICATION_ID)
    }
}
