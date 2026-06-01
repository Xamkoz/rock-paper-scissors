package com.rpsonline.app.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.google.firebase.auth.FirebaseAuth
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rpsonline.app.MainActivity
import com.rpsonline.app.R
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.repository.MatchRepository
import com.rpsonline.app.data.repository.MatchSessionMonitor
import com.rpsonline.app.data.repository.PresenceRepository
import com.rpsonline.app.ui.segment.SegmentedNotificationStatus
import com.rpsonline.app.ui.segment.SegmentedSpinnerStyle
import com.rpsonline.app.ui.segment.TopBarStatusRowSpec
import com.rpsonline.app.ui.util.MatchClockSoundController
import com.rpsonline.app.ui.util.formatQueueTimeMmSs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MatchmakingForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var notificationUpdateJob: Job? = null
    private var sessionObserverJob: Job? = null
    private var clockSoundJob: Job? = null
    private val matchRepository by lazy { MatchRepository() }
    private val presenceRepository by lazy { PresenceRepository() }

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        SegmentedNotificationState.setOnContentChangedListener { repostForegroundNotification() }
        ensureForegroundChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!MatchmakingBackgroundCoordinator.shouldRunService(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        MatchSessionMonitor.ensureStarted()
        val notification = buildForegroundNotification()
        ServiceCompat.startForeground(
            this,
            FOREGROUND_NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        startHeartbeatLoop()
        startNotificationUpdateLoop()
        startSessionObserver()
        startClockSoundLoop()
        repostForegroundNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        notificationUpdateJob?.cancel()
        sessionObserverJob?.cancel()
        clockSoundJob?.cancel()
        if (runningInstance === this) {
            runningInstance = null
            SegmentedNotificationState.setOnContentChangedListener(null)
        }
        MatchClockSoundController.sync(false)
        getSystemService(NotificationManager::class.java)?.cancel(FOREGROUND_NOTIFICATION_ID)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            var queueFailures = 0
            var presenceBeat = 0
            var queueBeat = 0
            while (isActive) {
                FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                    runCatching {
                        presenceBeat++
                        val nowMs = System.currentTimeMillis()
                        presenceRepository.touchPresence(
                            uid,
                            awaitServerAck = presenceBeat == 1 || presenceBeat % 2 == 0,
                            includeOnlineCount = PresenceRepository.shouldRequestOnlineCount(nowMs),
                        )
                    }.onSuccess {
                        presenceRepository.onlineCount.value?.let { count ->
                            SegmentedNotificationState.setOnlineCount(count)
                        }
                    }
                }
                if (MatchSessionMonitor.shouldSendQueueHeartbeats()) {
                    queueBeat++
                    if (queueBeat % 3 == 0) {
                        MatchSessionMonitor.verifyQueueOnServer()
                    }
                    if (matchRepository.sendQueueHeartbeat()) {
                        queueFailures = 0
                    } else {
                        queueFailures += 1
                        if (queueFailures >= 3) {
                            MatchSessionMonitor.signalQueueDocLost()
                            queueFailures = 0
                        }
                    }
                } else if (MatchSessionMonitor.isMatchmakingInProgress()) {
                    MatchSessionMonitor.requestQueueRecovery()
                }
                delay(PresenceRepository.HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun startNotificationUpdateLoop() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = serviceScope.launch {
            while (isActive) {
                updateForegroundNotification()
                delay(NOTIFICATION_TICK_MS)
            }
        }
    }

    private fun startSessionObserver() {
        sessionObserverJob?.cancel()
        sessionObserverJob = serviceScope.launch {
            combine(
                MatchSessionMonitor.activeMatch,
                MatchSessionMonitor.queueJoinedAtMs,
                MatchSessionMonitor.hasQueueEntry,
                MatchSessionMonitor.matchmakingInProgress,
            ) { match, joinedAt, hasQueueEntry, matchmakingInProgress ->
                SessionObserverSnapshot(
                    match = match,
                    queueJoinedAtMs = joinedAt,
                    hasQueueEntry = hasQueueEntry,
                    matchmakingInProgress = matchmakingInProgress,
                )
            }
                .distinctUntilChanged()
                .collect { snapshot ->
                    MatchForegroundLaunchCoordinator.onMatchSessionChanged(
                        this@MatchmakingForegroundService,
                        snapshot.match,
                    )
                    if (MatchmakingBackgroundCoordinator.shouldRunService(this@MatchmakingForegroundService)) {
                        updateForegroundNotification()
                    } else {
                        stopSelf()
                    }
                }
        }
    }

    private data class SessionObserverSnapshot(
        val match: Match?,
        val queueJoinedAtMs: Long?,
        val hasQueueEntry: Boolean,
        val matchmakingInProgress: Boolean,
    )

    private fun startClockSoundLoop() {
        clockSoundJob?.cancel()
        val appContext = applicationContext
        clockSoundJob = serviceScope.launch {
            while (isActive) {
                MatchClockSoundController.syncFromSessionWhenBackground(appContext)
                delay(500)
            }
        }
    }

    private fun updateForegroundNotification() {
        if (!MatchmakingBackgroundCoordinator.shouldRunService(this)) {
            stopSelf()
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification())
    }

    /** RemoteViews can miss early binds; repost quickly on a background thread after [startForeground]. */
    private fun repostForegroundNotification() {
        serviceScope.launch {
            updateForegroundNotification()
            for (delayMs in REPOST_DELAYS_MS) {
                delay(delayMs)
                if (!isActive) return@launch
                updateForegroundNotification()
            }
        }
    }

    private fun resolveNotificationDisplay(): TopBarStatusRowSpec {
        val match = MatchSessionMonitor.activeMatch.value
        val queueJoinedAt = MatchSessionMonitor.queueJoinedAtMs.value
        val now = System.currentTimeMillis()
        val uid = FirebaseAuth.getInstance().currentUser?.uid

        if (uid != null && match?.status == MatchStatus.ACTIVE && match.isParticipant(uid)) {
            val startedAtMs = match.createdAt.takeIf { it > 0L } ?: now
            val clockStopped = !match.isPlayerClockRunning(uid)
            return TopBarStatusRowSpec(
                status = SegmentedNotificationStatus.IN_MATCH,
                onlineCount = SegmentedNotificationState.onlineCount,
                showLiveTime = true,
                elapsedSeconds = ((now - startedAtMs) / 1_000).coerceAtLeast(0L),
                spinnerStyle = if (clockStopped) {
                    SegmentedSpinnerStyle.MATCH_CLOCK_STOPPED
                } else {
                    SegmentedSpinnerStyle.MATCH
                },
            )
        }

        if (uid != null && match?.status == MatchStatus.LOBBY && match.isParticipant(uid)) {
            val startedAtMs = match.createdAt.takeIf { it > 0L } ?: now
            return TopBarStatusRowSpec(
                status = SegmentedNotificationStatus.MATCH_FOUND,
                onlineCount = SegmentedNotificationState.onlineCount,
                showLiveTime = true,
                elapsedSeconds = ((now - startedAtMs) / 1_000).coerceAtLeast(0L),
                spinnerStyle = SegmentedSpinnerStyle.QUEUE,
            )
        }

        val joinedAt = queueJoinedAt?.takeIf { it > 0L }
        val inQueue = MatchSessionMonitor.hasQueueEntry.value || joinedAt != null
        return TopBarStatusRowSpec(
            status = SegmentedNotificationStatus.IN_QUEUE,
            onlineCount = SegmentedNotificationState.onlineCount,
            showLiveTime = inQueue,
            elapsedSeconds = joinedAt?.let { ((now - it) / 1_000).coerceAtLeast(0L) } ?: 0L,
            spinnerStyle = SegmentedSpinnerStyle.QUEUE,
        )
    }

    private fun ensureForegroundChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val queueChannel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            getString(R.string.background_usage_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.background_usage_notification_channel_desc)
        }
        val matchChannel = NotificationChannel(
            FOREGROUND_ALERT_CHANNEL_ID,
            getString(R.string.match_found_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.match_found_notification_channel_desc)
        }
        manager.createNotificationChannel(queueChannel)
        manager.createNotificationChannel(matchChannel)
    }

    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
    }

    private fun shouldUseLaunchAlert(display: TopBarStatusRowSpec): Boolean {
        if (System.currentTimeMillis() < launchAlertUntilMs) return true
        return display.status == SegmentedNotificationStatus.MATCH_FOUND &&
            !AppForegroundTracker.isInForeground
    }

    private fun buildForegroundNotification(): Notification {
        val match = MatchSessionMonitor.activeMatch.value
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val launchMatchId = if (
            uid != null &&
            match != null &&
            match.isParticipant(uid) &&
            (match.status == MatchStatus.LOBBY || match.status == MatchStatus.ACTIVE)
        ) {
            match.id
        } else {
            null
        }
        val openAppIntent = if (launchMatchId != null) {
            MatchLaunchHelper.buildLaunchIntent(this, launchMatchId)
        } else {
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val display = resolveNotificationDisplay()
        val accessibilityTime = formatQueueTimeMmSs(display.elapsedSeconds)
        val needsLaunchAlert = shouldUseLaunchAlert(display)
        val channelId = if (needsLaunchAlert) {
            FOREGROUND_ALERT_CHANNEL_ID
        } else {
            FOREGROUND_CHANNEL_ID
        }
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_match_found)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
        if (needsLaunchAlert) {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
            builder.setCategory(NotificationCompat.CATEGORY_CALL)
            if (canUseFullScreenIntent()) {
                builder.setFullScreenIntent(pendingIntent, true)
            }
        } else {
            builder.setSilent(true)
        }
        SevenSegmentNotificationRenderer.applySegmentedStatusViews(
            builder = builder,
            context = applicationContext,
            state = display,
            accessibilitySummary = accessibilityTime,
        )
        return builder.build()
    }

    companion object {
        private const val FOREGROUND_CHANNEL_ID = "matchmaking_background"
        private const val FOREGROUND_ALERT_CHANNEL_ID = "matchmaking_background_alert"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val NOTIFICATION_TICK_MS = 500L
        private const val LAUNCH_ALERT_WINDOW_MS = 8_000L
        private val REPOST_DELAYS_MS = longArrayOf(50L, 150L, 350L)

        @Volatile
        private var runningInstance: MatchmakingForegroundService? = null

        @Volatile
        private var launchAlertUntilMs: Long = 0L

        fun requestLaunchAlert() {
            launchAlertUntilMs = System.currentTimeMillis() + LAUNCH_ALERT_WINDOW_MS
        }

        fun clearLaunchAlert() {
            launchAlertUntilMs = 0L
        }

        fun refreshNotificationIfRunning() {
            runningInstance?.repostForegroundNotification()
        }

        fun isRunning(): Boolean = runningInstance != null

        fun sync(context: Context, shouldRun: Boolean) {
            if (shouldRun) {
                val intent = Intent(context, MatchmakingForegroundService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(Intent(context, MatchmakingForegroundService::class.java))
            }
        }
    }
}
