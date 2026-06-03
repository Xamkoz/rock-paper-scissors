package com.rpsonline.app.platform

import android.app.ForegroundServiceStartNotAllowedException
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
import com.rpsonline.app.ui.segment.SevenSegmentColonBlink
import com.rpsonline.app.ui.segment.SegmentedNotificationStatus
import com.rpsonline.app.ui.segment.SegmentedSpinnerStyle
import com.rpsonline.app.ui.segment.TopBarStatusRowSpec
import com.rpsonline.app.ui.util.MatchClockSoundController
import com.rpsonline.app.ui.util.formatQueueTimeMmSs
import com.rpsonline.app.ui.util.queueElapsedSecondsFromAnchor
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
    private var lastPostedFingerprint: NotificationFingerprint? = null
    private var lastPostedAtMs = 0L
    private val notificationPostLock = Any()
    @Volatile
    private var forceNextNotificationPost = false
    private var foregroundPromoted = false

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        ensureForegroundChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground counts toward enqueue rate — only once per service instance.
        if (!foregroundPromoted) {
            promoteToForeground()
            foregroundPromoted = true
        }
        if (!MatchmakingBackgroundCoordinator.shouldRunService(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        MatchSessionMonitor.ensureStarted()
        startHeartbeatLoop()
        startNotificationUpdateLoop()
        startSessionObserver()
        startClockSoundLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        foregroundPromoted = false
        heartbeatJob?.cancel()
        notificationUpdateJob?.cancel()
        sessionObserverJob?.cancel()
        clockSoundJob?.cancel()
        if (runningInstance === this) {
            runningInstance = null
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
            while (isActive) {
                FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                    runCatching {
                        presenceBeat++
                        val nowMs = System.currentTimeMillis()
                        val needsOnlineCount = presenceRepository.onlineCount.value == null
                        presenceRepository.touchPresence(
                            uid,
                            awaitServerAck = false,
                            includeOnlineCount = needsOnlineCount ||
                                PresenceRepository.shouldRequestOnlineCount(nowMs),
                        )
                    }.onSuccess {
                        presenceRepository.onlineCount.value?.let { count ->
                            SegmentedNotificationState.setOnlineCount(count)
                        }
                    }
                }
                if (MatchSessionMonitor.shouldSendQueueHeartbeats()) {
                    if (matchRepository.sendQueueHeartbeat()) {
                        queueFailures = 0
                    } else {
                        queueFailures += 1
                        if (queueFailures >= 3) {
                            runCatching {
                                MatchSessionMonitor.signalQueueDocLostIfAbsentOnServer()
                            }
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
            delay(notificationDelayMs())
            while (isActive) {
                updateForegroundNotification()
                delay(notificationDelayMs())
            }
        }
    }

    /** Phase-locked to colon toggles (500ms on/off) and anchor-aligned second ticks. */
    private fun notificationDelayMs(): Long {
        val display = resolveNotificationDisplay()
        val nowMs = System.currentTimeMillis()
        return if (display.showLiveTime) {
            SevenSegmentColonBlink.delayUntilToggle(display.timerAnchorMs, nowMs).coerceAtLeast(1L)
        } else {
            NOTIFICATION_TICK_MS
        }
    }

    private fun minNotificationPostIntervalMs(display: TopBarStatusRowSpec): Long =
        if (display.showLiveTime) {
            SevenSegmentColonBlink.ON_MS
        } else {
            MIN_NOTIFICATION_POST_INTERVAL_MS
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
                    if (!MatchmakingBackgroundCoordinator.shouldRunService(this@MatchmakingForegroundService)) {
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

    private fun promoteToForeground() {
        val notification = buildForegroundNotification()
        synchronized(notificationPostLock) {
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
            lastPostedFingerprint = currentNotificationFingerprint(resolveNotificationDisplay())
            lastPostedAtMs = System.currentTimeMillis()
        }
    }

    /** Sole path for notification updates after [promoteToForeground] — max ~1/s to avoid system shedding. */
    private fun updateForegroundNotification() {
        synchronized(notificationPostLock) {
            if (!MatchmakingBackgroundCoordinator.shouldRunService(this)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
                stopSelf()
                return
            }
            val force = forceNextNotificationPost
            forceNextNotificationPost = false
            val nowMs = System.currentTimeMillis()
            val display = resolveNotificationDisplay()
            val fingerprint = currentNotificationFingerprint(display, nowMs)
            val minIntervalMs = minNotificationPostIntervalMs(display)
            if (
                !force &&
                fingerprint == lastPostedFingerprint &&
                nowMs - lastPostedAtMs < minIntervalMs
            ) {
                return
            }
            if (!force && fingerprint == lastPostedFingerprint) {
                return
            }
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification())
            lastPostedFingerprint = fingerprint
            lastPostedAtMs = nowMs
        }
    }

    private fun requestImmediateNotificationRefresh() {
        forceNextNotificationPost = true
        notificationUpdateJob?.cancel()
        notificationUpdateJob = serviceScope.launch {
            updateForegroundNotification()
            delay(notificationDelayMs())
            while (isActive) {
                updateForegroundNotification()
                delay(notificationDelayMs())
            }
        }
    }

    private fun currentNotificationFingerprint(
        display: TopBarStatusRowSpec,
        nowMs: Long = System.currentTimeMillis(),
    ): NotificationFingerprint {
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
        return NotificationFingerprint(
            status = display.status,
            elapsedSeconds = display.elapsedSeconds,
            onlineCount = display.onlineCount,
            colonBlinkLit = SevenSegmentColonBlink.isLit(
                display.showLiveTime,
                display.timerAnchorMs,
                nowMs,
            ),
            launchAlert = shouldUseLaunchAlert(display),
            launchMatchId = launchMatchId,
        )
    }

    private data class NotificationFingerprint(
        val status: SegmentedNotificationStatus,
        val elapsedSeconds: Long,
        val onlineCount: Int?,
        val colonBlinkLit: Boolean,
        val launchAlert: Boolean,
        val launchMatchId: String?,
    )

    private fun resolveNotificationDisplay(): TopBarStatusRowSpec {
        val match = MatchSessionMonitor.activeMatch.value
        val queueJoinedAt = MatchSessionMonitor.queueElapsedAnchorMs()
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
                timerAnchorMs = startedAtMs,
                spinnerStyle = if (clockStopped) {
                    SegmentedSpinnerStyle.MATCH_CLOCK_STOPPED
                } else {
                    SegmentedSpinnerStyle.MATCH
                },
                animateSpinner = !clockStopped,
            )
        }

        if (uid != null && match?.status == MatchStatus.LOBBY && match.isParticipant(uid)) {
            val startedAtMs = match.createdAt.takeIf { it > 0L } ?: now
            return TopBarStatusRowSpec(
                status = SegmentedNotificationStatus.MATCH_FOUND,
                onlineCount = SegmentedNotificationState.onlineCount,
                showLiveTime = true,
                elapsedSeconds = ((now - startedAtMs) / 1_000).coerceAtLeast(0L),
                timerAnchorMs = startedAtMs,
                spinnerStyle = SegmentedSpinnerStyle.QUEUE,
            )
        }

        val joinedAt = queueJoinedAt?.takeIf { it > 0L }
        val inQueue = computeConfirmedInQueue(
            uid = uid,
            match = match,
            queueJoinedAtMs = joinedAt,
            matchmakingInProgress = MatchSessionMonitor.isMatchmakingInProgress(),
        )
        if (!inQueue) {
            return TopBarStatusRowSpec(
                status = SegmentedNotificationStatus.IN_QUEUE,
                onlineCount = SegmentedNotificationState.onlineCount,
                showLiveTime = false,
                elapsedSeconds = 0L,
                spinnerStyle = SegmentedSpinnerStyle.QUEUE,
            )
        }
        return TopBarStatusRowSpec(
            status = SegmentedNotificationStatus.IN_QUEUE,
            onlineCount = SegmentedNotificationState.onlineCount,
            showLiveTime = inQueue,
            elapsedSeconds = joinedAt?.let { queueElapsedSecondsFromAnchor(it, now) } ?: 0L,
            timerAnchorMs = joinedAt,
            spinnerStyle = SegmentedSpinnerStyle.QUEUE,
        )
    }

    private fun ensureForegroundChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val queueChannel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            getString(R.string.background_usage_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.background_usage_notification_channel_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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
        val statusBarIcon = if (needsLaunchAlert) {
            R.drawable.ic_stat_match_found
        } else {
            R.drawable.ic_stat_rps_session
        }
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(statusBarIcon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (needsLaunchAlert) {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
            builder.setCategory(NotificationCompat.CATEGORY_CALL)
            if (canUseFullScreenIntent()) {
                builder.setFullScreenIntent(pendingIntent, true)
            }
        } else {
            builder
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setSilent(true)
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
        /** New id so existing installs pick up [NotificationManager.IMPORTANCE_DEFAULT] for the status bar icon. */
        private const val FOREGROUND_CHANNEL_ID = "matchmaking_background_status"
        private const val FOREGROUND_ALERT_CHANNEL_ID = "matchmaking_background_alert"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        /** Idle status (no live MM:SS) — 1 Hz stays under NotificationService enqueue limits. */
        private const val NOTIFICATION_TICK_MS = 1_000L
        private const val MIN_NOTIFICATION_POST_INTERVAL_MS = 1_000L
        private const val LAUNCH_ALERT_WINDOW_MS = 8_000L

        @Volatile
        private var runningInstance: MatchmakingForegroundService? = null

        @Volatile
        private var launchAlertUntilMs: Long = 0L

        fun requestLaunchAlert() {
            launchAlertUntilMs = System.currentTimeMillis() + LAUNCH_ALERT_WINDOW_MS
            runningInstance?.requestImmediateNotificationRefresh()
        }

        fun clearLaunchAlert() {
            launchAlertUntilMs = 0L
        }

        /** No-op: [MatchmakingForegroundService] tick loop owns notification updates. */
        fun refreshNotificationIfRunning() = Unit

        fun isRunning(): Boolean = runningInstance != null

        @Volatile
        private var pendingStart = false

        /**
         * Starts or stops the foreground service. When Android blocks background FGS launch
         * ([ForegroundServiceStartNotAllowedException]), the start is deferred until the app is
         * foreground again — call [retryPendingStart] from [MainActivity.onResume].
         */
        fun sync(context: Context, shouldRun: Boolean) {
            if (!shouldRun) {
                pendingStart = false
                context.stopService(Intent(context, MatchmakingForegroundService::class.java))
                return
            }
            if (isRunning()) {
                pendingStart = false
                return
            }
            if (startForegroundServiceSafe(context)) {
                pendingStart = false
            } else {
                pendingStart = true
            }
        }

        fun retryPendingStart(context: Context) {
            if (!pendingStart) return
            sync(context, MatchmakingBackgroundCoordinator.shouldRunService(context))
        }

        private fun startForegroundServiceSafe(context: Context): Boolean {
            val intent = Intent(context, MatchmakingForegroundService::class.java)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (_: ForegroundServiceStartNotAllowedException) {
                false
            } catch (e: IllegalStateException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    false
                } else {
                    throw e
                }
            }
        }
    }
}
