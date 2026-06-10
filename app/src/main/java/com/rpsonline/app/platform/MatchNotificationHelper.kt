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
import com.rpsonline.app.ui.segment.SegmentedSpinnerStyle
import com.rpsonline.app.ui.segment.TopBarStatusRowSpec
import com.rpsonline.app.ui.segment.matchFoundSegmentedDisplay
import com.rpsonline.app.ui.util.formatQueueTimeMmSs
import com.rpsonline.app.ui.util.MatchClockSoundController
import com.rpsonline.app.ui.util.PreGameLobbySoundPolicy
import com.rpsonline.app.ui.util.triggerMatchFoundFeedback

object MatchNotificationHelper {
    /** v5 channel: HIGH importance for lock-screen / status-bar visibility on all installs. */
    private const val MATCH_FOUND_CHANNEL_ID = "match_found_alert_v5"
    private const val MATCH_FOUND_HEADS_UP_CHANNEL_ID = "match_found_heads_up_v3"
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
            enableVibration(false)
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
            !MatchFoundNotificationPolicy.shouldRunMatchFoundAlert(
                appInForeground = AppForegroundTracker.isInForeground,
                matchStatus = match.status,
                matchFoundNotificationsEnabled =
                    MatchmakingPreferences(appContext).isMatchFoundNotificationsEnabled(),
                backgroundUsageEnabled =
                    MatchmakingPreferences(appContext).isBackgroundUsageEnabled(),
                hasPostNotificationsPermission =
                    NotificationPermissionHelper.hasPostNotificationsPermission(appContext),
                matchId = match.id,
                visibleMatchScreenId = MatchSessionMonitor.visibleMatchScreenId.value,
                liveSessionMatch = match,
                uid = uid,
            )
        ) {
            return false
        }
        val matchId = match.id
        val playAlert = MatchFoundNotificationGate.tryNotify(matchId)
        if (!playAlert) {
            maintainJoinMatchNotification(context, match, uid)
            MatchClockSoundController.initialize(appContext)
            if (PreGameLobbySoundPolicy.shouldRunMatchFoundLobbyAlert(appContext)) {
                MatchClockSoundController.syncLobbyAlert(true)
            } else {
                MatchClockSoundController.syncLobbyAlert(false)
            }
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
            if (!fgsRunning && MatchFoundNotificationPolicy.shouldUseProminentMatchFoundHeadsUp()) {
                postMatchFoundHeadsUp(context, match, uid)
            } else {
                manager.cancel(MATCH_FOUND_HEADS_UP_NOTIFICATION_ID)
            }
            maintainJoinMatchNotification(context, match, uid)
        } else {
            syncMatchFoundForegroundDisplay(appContext, match)
            if (
                MatchFoundNotificationPolicy.shouldPostMatchFoundShadeNotification(
                    foregroundServiceOwnsDisplay = true,
                    appInForeground = AppForegroundTracker.isInForeground,
                    matchFoundNotificationsEnabled =
                        MatchmakingPreferences(appContext).isMatchFoundNotificationsEnabled(),
                    backgroundUsageEnabled =
                        MatchmakingPreferences(appContext).isBackgroundUsageEnabled(),
                )
            ) {
                maintainJoinMatchNotification(context, match, uid)
            } else {
                manager.cancel(MATCH_FOUND_NOTIFICATION_ID)
                manager.cancel(MATCH_FOUND_HEADS_UP_NOTIFICATION_ID)
            }
        }
        triggerMatchFoundFeedback(context, matchId, playReadyBurst = false)
        startLobbyAlertRefresh(context, match, uid)
        return true
    }

    /** High-importance peek while backgrounded; refreshed during the 20s alert window. */
    private fun postMatchFoundHeadsUp(context: Context, match: Match, uid: String) {
        if (!MatchFoundNotificationPolicy.shouldUseProminentMatchFoundHeadsUp()) return
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
                if (MatchSessionMonitor.visibleMatchScreenId.value == match.id) {
                    stopLobbyAlertRefresh()
                    return
                }
                if (!JoinMatchNotificationState.isLobbyAlertPhase()) {
                    lobbyAlertRefreshRunnable = null
                    return
                }
                val latest = JoinMatchNotificationState.lobbyMatch() ?: match
                if (
                    JoinMatchNotificationState.isWithinProminentAlertWindow() &&
                    MatchFoundNotificationPolicy.shouldUseProminentMatchFoundHeadsUp() &&
                    !MatchmakingForegroundService.isRunning() &&
                    !MatchmakingBackgroundCoordinator.foregroundServiceOwnsMatchFoundDisplay(appContext)
                ) {
                    postMatchFoundHeadsUp(appContext, latest, uid)
                }
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

    private fun cancelLobbyAlertTimers(@Suppress("UNUSED_PARAMETER") context: Context) {
        stopLobbyAlertRefresh()
        MatchClockSoundController.syncLobbyAlert(false)
    }

    /** Refreshes lobby match-found UI: FGS tile when running, plus shade 2001 when policy allows. */
    fun maintainJoinMatchNotification(context: Context, match: Match, uid: String) {
        if (MatchSessionMonitor.visibleMatchScreenId.value == match.id) return
        if (suppressMatchFoundDuringActiveSession(context.applicationContext, uid)) return
        if (!match.isParticipant(uid)) return
        val inProminentWindow = JoinMatchNotificationState.isWithinProminentAlertWindow()
        val maintainableStatus = when (match.status) {
            MatchStatus.LOBBY -> true
            MatchStatus.ACTIVE -> inProminentWindow
            else -> false
        }
        if (!maintainableStatus) return
        JoinMatchNotificationState.bindLobby(match)
        MatchmakingForegroundService.applySessionMatchHint(match)
        val appContext = context.applicationContext
        val fgsOwnsDisplay =
            MatchmakingBackgroundCoordinator.foregroundServiceOwnsMatchFoundDisplay(appContext)
        if (fgsOwnsDisplay || MatchmakingForegroundService.isRunning()) {
            syncMatchFoundForegroundDisplay(appContext, match)
        }
        val prefs = MatchmakingPreferences(appContext)
        if (
            !MatchFoundNotificationPolicy.shouldPostMatchFoundShadeNotification(
                foregroundServiceOwnsDisplay = fgsOwnsDisplay,
                appInForeground = AppForegroundTracker.isInForeground,
                matchFoundNotificationsEnabled = prefs.isMatchFoundNotificationsEnabled(),
                backgroundUsageEnabled = prefs.isBackgroundUsageEnabled(),
            )
        ) {
            NotificationManagerCompat.from(appContext).apply {
                cancel(MATCH_FOUND_NOTIFICATION_ID)
                cancel(MATCH_FOUND_HEADS_UP_NOTIFICATION_ID)
            }
            return
        }
        if (
            AppForegroundTracker.isInForeground &&
            !prefs.isMatchFoundNotificationsEnabled() &&
            !prefs.isBackgroundUsageEnabled()
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        if (!NotificationPermissionHelper.hasPostNotificationsPermission(context)) return
        ensureChannels(context)
        if (!manager.areNotificationsEnabled()) return
        manager.notify(
            MATCH_FOUND_NOTIFICATION_ID,
            buildJoinMatchNotification(context, match, uid, headsUpAlert = false),
        )
    }

    private fun syncMatchFoundForegroundDisplay(context: Context, match: Match) {
        if (MatchmakingForegroundService.isRunning()) {
            MatchmakingForegroundService.persistMatchFoundForegroundDisplay()
        } else {
            MatchmakingBackgroundCoordinator.sync(context)
        }
        MatchmakingForegroundService.applySessionMatchHint(match)
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
        val startedAtMs = JoinMatchNotificationState.lobbyAlertStartedAtMs()?.takeIf { it > 0L }
            ?: match.createdAt.takeIf { it > 0L }
            ?: now
        val segmentedDisplay = matchFoundSegmentedDisplay(
            onlineCount = SegmentedNotificationState.onlineCount,
            startedAtMs = startedAtMs,
            nowMs = now,
        )
        val useHeadsUp = headsUpAlert && MatchFoundNotificationPolicy.shouldUseProminentMatchFoundHeadsUp()
        val channelId = if (useHeadsUp) {
            MATCH_FOUND_HEADS_UP_CHANNEL_ID
        } else {
            MATCH_FOUND_CHANNEL_ID
        }
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(RpsStatusBarNotification.smallIconRes)
            .setSortKey(if (useHeadsUp) "match_found_heads_up" else "match_found")
            .setPriority(
                if (useHeadsUp) {
                    NotificationCompat.PRIORITY_MAX
                } else {
                    NotificationCompat.PRIORITY_HIGH
                },
            )
            .setCategory(
                if (useHeadsUp) {
                    NotificationCompat.CATEGORY_ALARM
                } else {
                    NotificationCompat.CATEGORY_EVENT
                },
            )
            .setOngoing(!useHeadsUp || JoinMatchNotificationState.isWithinProminentAlertWindow())
            .setOnlyAlertOnce(!JoinMatchNotificationState.isWithinProminentAlertWindow())
        if (useHeadsUp) {
            builder
                .setSilent(false)
                .setDefaults(0)
                .setVibrate(null)
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
