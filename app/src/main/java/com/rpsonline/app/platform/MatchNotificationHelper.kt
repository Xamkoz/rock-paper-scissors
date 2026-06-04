package com.rpsonline.app.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rpsonline.app.R
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.preferences.MatchmakingPreferences
import com.rpsonline.app.data.repository.MatchSessionMonitor
import com.rpsonline.app.ui.segment.SegmentedNotificationStatus
import com.rpsonline.app.ui.util.formatQueueTimeMmSs
import com.rpsonline.app.ui.segment.SegmentedSpinnerStyle
import com.rpsonline.app.ui.segment.TopBarStatusRowSpec
import com.rpsonline.app.ui.util.MatchClockSoundController
import com.rpsonline.app.ui.util.PreGameLobbySoundPolicy
import com.rpsonline.app.ui.util.triggerMatchFoundFeedback

object MatchNotificationHelper {
    /** v3 channel: fresh HIGH importance for heads-up / launcher visibility on upgraded installs. */
    /** Silent channel — match-found audio uses in-game ticks, not the system default sound. */
    private const val MATCH_FOUND_CHANNEL_ID = "match_found_alert_v4"
    private const val MATCH_FOUND_HEADS_UP_CHANNEL_ID = "match_found_heads_up_v1"
    const val MATCH_FOUND_NOTIFICATION_ID = 2001
    private const val MATCH_FOUND_HEADS_UP_NOTIFICATION_ID = 2002

    private val lobbyAlertHandler = Handler(Looper.getMainLooper())
    private var lobbyAlertRefreshRunnable: Runnable? = null

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            MATCH_FOUND_CHANNEL_ID,
            context.getString(R.string.match_found_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.match_found_notification_channel_desc)
            setSound(null, null)
            enableVibration(false)
            enableLights(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setAllowBubbles(false)
            }
        }
        val headsUpChannel = NotificationChannel(
            MATCH_FOUND_HEADS_UP_CHANNEL_ID,
            context.getString(R.string.match_found_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.match_found_notification_channel_desc)
            setSound(null, null)
            enableVibration(true)
            enableLights(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(headsUpChannel)
    }

    /**
     * One match-found alert per [matchId]. Ongoing FGS or 2001 shows match-found (not in-queue) for
     * the full pre-game lobby. Audio is in-game clock ticks, not the default notification sound.
     */
    fun showMatchFound(context: Context, match: Match, uid: String): Boolean {
        val appContext = context.applicationContext
        if (suppressMatchFoundDuringActiveSession(appContext, uid)) {
            return false
        }
        if (
            AppForegroundTracker.isInForeground &&
            !MatchmakingPreferences(appContext).isMatchFoundNotificationsEnabled()
        ) {
            return false
        }
        val matchId = match.id
        val playAlert = MatchFoundNotificationGate.tryNotify(matchId)
        if (!playAlert) {
            maintainJoinMatchNotification(context, match, uid)
            return false
        }
        val manager = NotificationManagerCompat.from(context)
        val canPostShade = NotificationPermissionHelper.hasPostNotificationsPermission(context) &&
            manager.areNotificationsEnabled()

        JoinMatchNotificationState.beginLobbyAlertPhase(match)
        manager.cancel(MATCH_FOUND_NOTIFICATION_ID)
        manager.cancel(MATCH_FOUND_HEADS_UP_NOTIFICATION_ID)
        val fgsOwnsDisplay =
            MatchmakingBackgroundCoordinator.foregroundServiceOwnsMatchFoundDisplay(appContext)
        val fgsRunning = MatchmakingForegroundService.isRunning()
        if (fgsOwnsDisplay) {
            MatchmakingBackgroundCoordinator.sync(appContext)
        }
        if (fgsRunning) {
            MatchmakingForegroundService.requestLaunchAlert(playSound = false)
            MatchmakingForegroundService.persistMatchFoundForegroundDisplay()
        }
        MatchmakingForegroundService.applySessionMatchHint(match)
        MatchClockSoundController.initialize(appContext)
        MatchClockSoundController.syncLobbyAlert(true)

        if (!canPostShade) {
            triggerMatchFoundFeedback(context, matchId, playReadyBurst = false)
            return true
        }

        if (!fgsOwnsDisplay) {
            if (!fgsRunning) {
                postMatchFoundHeadsUp(context, match, uid)
            } else {
                manager.cancel(MATCH_FOUND_HEADS_UP_NOTIFICATION_ID)
            }
            maintainJoinMatchNotification(context, match, uid)
        } else {
            manager.cancel(MATCH_FOUND_NOTIFICATION_ID)
            manager.cancel(MATCH_FOUND_HEADS_UP_NOTIFICATION_ID)
            if (fgsRunning) {
                MatchmakingForegroundService.persistMatchFoundForegroundDisplay()
            }
        }
        triggerMatchFoundFeedback(context, matchId, playReadyBurst = false)
        startLobbyAlertRefresh(context, match, uid)
        return true
    }

    /** One-shot high-importance peek when FGS is not the live tile (no 2001/1001 duplicate). */
    private fun postMatchFoundHeadsUp(context: Context, match: Match, uid: String) {
        if (MatchmakingForegroundService.isRunning()) return
        ensureChannels(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        manager.notify(
            MATCH_FOUND_HEADS_UP_NOTIFICATION_ID,
            buildJoinMatchNotification(context, match, uid, headsUpAlert = true),
        )
    }

    private fun startLobbyAlertRefresh(context: Context, match: Match, uid: String) {
        stopLobbyAlertRefresh()
        val appContext = context.applicationContext
        val tick = object : Runnable {
            override fun run() {
                if (!JoinMatchNotificationState.isLobbyAlertPhase()) {
                    lobbyAlertRefreshRunnable = null
                    return
                }
                val latest = JoinMatchNotificationState.lobbyMatch() ?: match
                maintainJoinMatchNotification(appContext, latest, uid)
                lobbyAlertHandler.postDelayed(this, 1_000L)
            }
        }
        lobbyAlertRefreshRunnable = tick
        lobbyAlertHandler.postDelayed(tick, 1_000L)
    }

    private fun stopLobbyAlertRefresh() {
        lobbyAlertRefreshRunnable?.let { lobbyAlertHandler.removeCallbacks(it) }
        lobbyAlertRefreshRunnable = null
    }

    private fun cancelLobbyAlertTimers(context: Context) {
        stopLobbyAlertRefresh()
        if (!PreGameLobbySoundPolicy.isUserInPreGameLobby(context)) {
            MatchClockSoundController.syncLobbyAlert(false)
        }
    }

    /** Refreshes lobby match-found UI: FGS tile when running, otherwise notification 2001 (never both). */
    fun maintainJoinMatchNotification(context: Context, match: Match, uid: String) {
        if (suppressMatchFoundDuringActiveSession(context.applicationContext, uid)) return
        if (match.status != MatchStatus.LOBBY || !match.isParticipant(uid)) return
        JoinMatchNotificationState.bindLobby(match)
        MatchmakingForegroundService.applySessionMatchHint(match)
        val manager = NotificationManagerCompat.from(context)
        if (MatchmakingBackgroundCoordinator.foregroundServiceOwnsMatchFoundDisplay(context)) {
            manager.cancel(MATCH_FOUND_NOTIFICATION_ID)
            manager.cancel(MATCH_FOUND_HEADS_UP_NOTIFICATION_ID)
            if (MatchmakingForegroundService.isRunning()) {
                MatchmakingForegroundService.persistMatchFoundForegroundDisplay()
            }
            return
        }
        if (MatchmakingForegroundService.isRunning()) {
            manager.cancel(MATCH_FOUND_NOTIFICATION_ID)
            manager.cancel(MATCH_FOUND_HEADS_UP_NOTIFICATION_ID)
            MatchmakingForegroundService.persistMatchFoundForegroundDisplay()
            return
        }
        if (
            AppForegroundTracker.isInForeground &&
            !MatchmakingPreferences(context.applicationContext).isMatchFoundNotificationsEnabled()
        ) {
            return
        }
        if (!NotificationPermissionHelper.hasPostNotificationsPermission(context)) return
        ensureChannels(context)
        if (!manager.areNotificationsEnabled()) return
        manager.notify(
            MATCH_FOUND_NOTIFICATION_ID,
            buildJoinMatchNotification(context, match, uid, headsUpAlert = false),
        )
    }

    private fun buildJoinMatchNotification(
        context: Context,
        match: Match,
        uid: String,
        headsUpAlert: Boolean,
    ): android.app.Notification {
        val matchId = match.id
        val opponentName = match.opponentName(uid)
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
        val now = System.currentTimeMillis()
        val startedAtMs = match.createdAt.takeIf { it > 0L } ?: now
        val elapsedSeconds = ((now - startedAtMs) / 1_000).coerceAtLeast(0L)
        val segmentedDisplay = TopBarStatusRowSpec(
            status = SegmentedNotificationStatus.MATCH_FOUND,
            onlineCount = SegmentedNotificationState.onlineCount,
            showLiveTime = true,
            elapsedSeconds = elapsedSeconds,
            timerAnchorMs = startedAtMs,
            spinnerStyle = SegmentedSpinnerStyle.QUEUE,
        )
        val channelId = if (headsUpAlert) {
            MATCH_FOUND_HEADS_UP_CHANNEL_ID
        } else {
            MATCH_FOUND_CHANNEL_ID
        }
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(RpsStatusBarNotification.smallIconRes)
            .setSortKey(if (headsUpAlert) "match_found_heads_up" else "match_found")
            .setPriority(
                if (headsUpAlert) {
                    NotificationCompat.PRIORITY_MAX
                } else {
                    NotificationCompat.PRIORITY_HIGH
                },
            )
            .setCategory(
                if (headsUpAlert) {
                    NotificationCompat.CATEGORY_ALARM
                } else {
                    NotificationCompat.CATEGORY_EVENT
                },
            )
            .setOngoing(!headsUpAlert)
            .setOnlyAlertOnce(true)
        if (headsUpAlert) {
            builder
                .setSilent(false)
                .setDefaults(0)
        } else {
            builder.setSilent(true)
        }
        return builder
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
    }

    private fun suppressMatchFoundDuringActiveSession(context: Context, uid: String): Boolean {
        val appContext = context.applicationContext
        val prefs = MatchmakingPreferences(appContext)
        val live = MatchSessionMonitor.activeMatch.value
        if (
            !MatchFoundNotificationPolicy.shouldSuppressMatchFoundAlerts(
                liveMatch = live,
                uid = uid,
                backgroundUsageEnabled = prefs.isBackgroundUsageEnabled(),
            )
        ) {
            return false
        }
        dismissMatchFound(appContext, live, uid)
        return true
    }

    private fun syncInMatchSession(context: Context, match: Match, uid: String) {
        if (match.status != MatchStatus.ACTIVE || !match.isParticipant(uid)) return
        MatchmakingForegroundService.applySessionMatchHint(match)
        MatchmakingForegroundService.clearLaunchAlert()
        if (MatchmakingForegroundService.isRunning()) {
            MatchmakingForegroundService.persistInMatchForegroundDisplay()
        }
    }

    /** Clears match-found alerts; keeps the in-match FGS tile when [liveMatch] is active. */
    fun dismissMatchFound(context: Context, liveMatch: Match? = null, uid: String? = null) {
        val appContext = context.applicationContext
        cancelLobbyAlertTimers(appContext)
        JoinMatchNotificationState.clear()
        val active = liveMatch?.takeIf {
            uid != null && it.isParticipant(uid) && it.status == MatchStatus.ACTIVE
        } ?: MatchSessionMonitor.activeMatch.value?.takeIf {
            uid != null && it.isParticipant(uid) && it.status == MatchStatus.ACTIVE
        }
        if (active != null && uid != null) {
            syncInMatchSession(appContext, active, uid)
        } else {
            MatchmakingForegroundService.applySessionMatchHint(null)
            MatchmakingForegroundService.clearLaunchAlert()
        }
        dismissMatchFoundNotifications(appContext, liveMatch, uid)
    }

    private fun dismissMatchFoundNotifications(
        context: Context,
        @Suppress("UNUSED_PARAMETER") liveMatch: Match? = null,
        @Suppress("UNUSED_PARAMETER") uid: String? = null,
    ) {
        NotificationManagerCompat.from(context.applicationContext).apply {
            cancel(MATCH_FOUND_NOTIFICATION_ID)
            cancel(MATCH_FOUND_HEADS_UP_NOTIFICATION_ID)
        }
    }

    /** True when only the FGS tile (1001) should show in-match — never duplicate 2001. */
    private fun deferInMatchShadeToForegroundService(context: Context): Boolean {
        val appContext = context.applicationContext
        if (
            !MatchmakingBackgroundCoordinator.foregroundServiceOwnsMatchFoundDisplay(appContext) &&
            !MatchmakingForegroundService.isRunning()
        ) {
            return false
        }
        dismissMatchFoundNotifications(appContext)
        return true
    }

    /** Ongoing in-match shade until the game screen for this match is visible. */
    fun showInMatch(context: Context, match: Match, uid: String) {
        cancelLobbyAlertTimers(context)
        JoinMatchNotificationState.clear()
        syncInMatchSession(context, match, uid)
        if (deferInMatchShadeToForegroundService(context)) return
        if (!NotificationPermissionHelper.hasPostNotificationsPermission(context)) return
        ensureChannels(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val now = System.currentTimeMillis()
        val startedAtMs = match.createdAt.takeIf { it > 0L } ?: now
        val clockStopped = !match.isPlayerClockRunning(uid)
        val elapsedSeconds = ((now - startedAtMs) / 1_000).coerceAtLeast(0L)
        val segmentedDisplay = TopBarStatusRowSpec(
            status = SegmentedNotificationStatus.IN_MATCH,
            onlineCount = SegmentedNotificationState.onlineCount,
            showLiveTime = true,
            elapsedSeconds = elapsedSeconds,
            timerAnchorMs = startedAtMs,
            spinnerStyle = if (clockStopped) {
                SegmentedSpinnerStyle.MATCH_CLOCK_STOPPED
            } else {
                SegmentedSpinnerStyle.MATCH
            },
            animateSpinner = !clockStopped,
        )
        val accessibilityTime = context.getString(
            R.string.in_match_with_time,
            formatQueueTimeMmSs(elapsedSeconds),
        )
        val openAppIntent = MatchLaunchHelper.buildLaunchIntent(context, match.id)
        val pendingIntent = PendingIntent.getActivity(
            context,
            match.id.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, MATCH_FOUND_CHANNEL_ID)
            .setSmallIcon(RpsStatusBarNotification.smallIconRes)
            .setSortKey("in_match")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setSilent(true)
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
                    accessibilitySummary = accessibilityTime,
                )
            }
            .build()
        manager.notify(MATCH_FOUND_NOTIFICATION_ID, notification)
    }

    fun resetMatchFoundAlertSession() {
        MatchFoundNotificationGate.reset()
    }
}
