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
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                if (MatchSessionMonitor.hasQueueEntry.value) {
                    if (matchRepository.sendQueueHeartbeat()) {
                        consecutiveFailures = 0
                    } else {
                        consecutiveFailures += 1
                        if (consecutiveFailures >= 3) {
                            MatchSessionMonitor.signalQueueDocLost()
                        }
                    }
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
                listOf(match?.id, match?.status, joinedAt, hasQueueEntry, matchmakingInProgress)
            }
                .distinctUntilChanged()
                .collect {
                    if (MatchmakingBackgroundCoordinator.shouldRunService(this@MatchmakingForegroundService)) {
                        updateForegroundNotification()
                    } else {
                        stopSelf()
                    }
                }
        }
    }

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
        val matchmakingActive = MatchSessionMonitor.matchmakingInProgress.value
        return TopBarStatusRowSpec(
            status = SegmentedNotificationStatus.IN_QUEUE,
            onlineCount = SegmentedNotificationState.onlineCount,
            showLiveTime = joinedAt != null || matchmakingActive,
            elapsedSeconds = joinedAt?.let { ((now - it) / 1_000).coerceAtLeast(0L) } ?: 0L,
            spinnerStyle = SegmentedSpinnerStyle.QUEUE,
        )
    }

    private fun ensureForegroundChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            getString(R.string.background_usage_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.background_usage_notification_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val display = resolveNotificationDisplay()
        val accessibilityTime = formatQueueTimeMmSs(display.elapsedSeconds)
        val builder = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_match_found)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
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
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val NOTIFICATION_TICK_MS = 500L
        private val REPOST_DELAYS_MS = longArrayOf(50L, 150L, 350L)

        @Volatile
        private var runningInstance: MatchmakingForegroundService? = null

        fun refreshNotificationIfRunning() {
            runningInstance?.repostForegroundNotification()
        }

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
