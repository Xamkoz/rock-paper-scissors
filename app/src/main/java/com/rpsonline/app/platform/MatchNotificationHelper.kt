package com.rpsonline.app.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rpsonline.app.R
import com.rpsonline.app.ui.segment.SegmentedNotificationStatus
import com.rpsonline.app.ui.segment.SegmentedSpinnerStyle
import com.rpsonline.app.ui.segment.TopBarStatusRowSpec

object MatchNotificationHelper {
    private const val MATCH_FOUND_CHANNEL_ID = "match_found"
    const val MATCH_FOUND_NOTIFICATION_ID = 2001

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            MATCH_FOUND_CHANNEL_ID,
            context.getString(R.string.match_found_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.match_found_notification_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    /** Ongoing until [dismissMatchFound] — cleared when the game screen opens or the lobby ends. */
    fun showMatchFound(context: Context, matchId: String, opponentName: String?) {
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
        val notification = NotificationCompat.Builder(context, MATCH_FOUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_match_found)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
