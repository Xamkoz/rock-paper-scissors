package com.rpsonline.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import android.Manifest
import android.os.Build
import com.rpsonline.app.data.monitoring.NetworkConnectionMonitor
import com.rpsonline.app.data.monitoring.NetworkConnectionStatus
import com.rpsonline.app.data.monitoring.NetworkDataActivityKind
import com.rpsonline.app.data.monitoring.NetworkDataActivityTracker
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.repository.AuthRepository
import com.rpsonline.app.data.repository.MatchRepository
import com.rpsonline.app.data.repository.MatchSessionMonitor
import com.rpsonline.app.data.repository.PresenceRepository
import com.rpsonline.app.data.preferences.MatchmakingPreferences
import com.rpsonline.app.data.preferences.SoundPreferences
import com.rpsonline.app.data.preferences.ThemePreferences
import com.rpsonline.app.platform.AppForegroundTracker
import com.rpsonline.app.platform.SegmentedNotificationState
import com.rpsonline.app.platform.BatteryOptimizationHelper
import com.rpsonline.app.platform.MatchFoundNotificationPolicy
import com.rpsonline.app.platform.MatchNotificationHelper
import com.rpsonline.app.platform.MatchmakingBackgroundCoordinator
import com.rpsonline.app.platform.PresenceEngagementTracker
import com.rpsonline.app.platform.computeSessionNeedsPresenceHeartbeat
import com.rpsonline.app.platform.MatchmakingForegroundService
import com.rpsonline.app.platform.NotificationPermissionHelper
import com.rpsonline.app.navigation.RpsNavGraph
import com.rpsonline.app.ui.components.AppearanceMenuButton
import com.rpsonline.app.ui.components.BackgroundUsageToggleButton
import com.rpsonline.app.ui.components.ClockSoundMuteButton
import com.rpsonline.app.ui.components.MatchFoundNotificationToggleButton
import com.rpsonline.app.ui.components.LocalNetworkConnectionStatus
import com.rpsonline.app.ui.components.SegmentedDisplayPulseEffect
import com.rpsonline.app.ui.components.isServerConnected
import com.rpsonline.app.ui.components.TopBarOnlineCountDisplay
import com.rpsonline.app.ui.components.TopBarSegmentedQueueIndicator
import com.rpsonline.app.ui.components.RpsTopStatusBar
import com.rpsonline.app.ui.util.applyImmersiveFullscreen
import com.rpsonline.app.ui.util.findActivity
import com.rpsonline.app.ui.theme.RpsTheme
import com.rpsonline.app.ui.util.MatchClockSoundController
import com.rpsonline.app.ui.util.LocalRoundResolutionPulse
import com.rpsonline.app.ui.util.RoundResolutionFeedbackEffect
import com.rpsonline.app.ui.util.RoundResolutionPulseNotifier
import com.rpsonline.app.ui.segment.SevenSegmentColonBlink
import com.rpsonline.app.ui.util.rememberQueueElapsedSeconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RpsApp() {
    val context = LocalContext.current
    val activity = context.findActivity()
    val themePreferences = remember { ThemePreferences(context) }
    val soundPreferences = remember { SoundPreferences(context) }
    val matchmakingPreferences = remember { MatchmakingPreferences(context) }
    var themeStyle by remember { mutableStateOf(themePreferences.get()) }
    var clockSoundMuted by remember { mutableStateOf(soundPreferences.isClockMuted()) }
    var backgroundUsageEnabled by remember {
        mutableStateOf(matchmakingPreferences.isBackgroundUsageEnabled())
    }
    var matchFoundNotificationsEnabled by remember {
        mutableStateOf(
            matchmakingPreferences.isMatchFoundNotificationsEnabled() &&
                NotificationPermissionHelper.hasPostNotificationsPermission(context),
        )
    }
    var lastNotifiedMatchId by remember { mutableStateOf<String?>(null) }
    var appInForeground by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    appInForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                }
                Lifecycle.Event.ON_RESUME -> {
                    PresenceEngagementTracker.syncScreenInteractive(context)
                    PresenceEngagementTracker.recordInteraction()
                    appInForeground = true
                    MatchmakingForegroundService.clearLaunchAlert()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    appInForeground = false
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        appInForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            matchFoundNotificationsEnabled = true
            matchmakingPreferences.setMatchFoundNotificationsEnabled(true)
            MatchNotificationHelper.ensureChannels(context)
        } else {
            matchFoundNotificationsEnabled = false
            matchmakingPreferences.setMatchFoundNotificationsEnabled(false)
        }
    }
    val scope = rememberCoroutineScope()
    val connectionMonitor = remember { NetworkConnectionMonitor(context) }
    val connectionStatus by connectionMonitor.status.collectAsStateWithLifecycle()
    val activeNetworkKinds by NetworkDataActivityTracker.activeKinds.collectAsStateWithLifecycle()

    DisposableEffect(connectionMonitor, scope) {
        connectionMonitor.start(scope)
        onDispose { connectionMonitor.stop() }
    }

    LifecycleResumeEffect(activity) {
        activity?.applyImmersiveFullscreen()
        onPauseOrDispose { }
    }

    DisposableEffect(Unit) {
        MatchSessionMonitor.ensureStarted()
        onDispose { }
    }

    val authRepository = remember { AuthRepository() }
    val matchRepository = remember { MatchRepository() }
    val user by authRepository.authStateFlow().collectAsStateWithLifecycle(initialValue = authRepository.currentUser)

    val presenceRepository = remember { PresenceRepository() }
    val onlinePlayerCount by presenceRepository.onlineCount.collectAsStateWithLifecycle()
    val activeMatch by MatchSessionMonitor.activeMatch.collectAsStateWithLifecycle()
    val hasQueueEntry by MatchSessionMonitor.hasQueueEntry.collectAsStateWithLifecycle()
    val queueJoinedAtMs by MatchSessionMonitor.queueJoinedAtMs.collectAsStateWithLifecycle()
    val queueTimerAnchorMs by MatchSessionMonitor.queueTimerAnchorMs.collectAsStateWithLifecycle()
    val matchmakingInProgress by MatchSessionMonitor.matchmakingInProgress.collectAsStateWithLifecycle()
    var userEngaged by remember { mutableStateOf(PresenceEngagementTracker.isEngaged()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(PresenceEngagementTracker.ENGAGEMENT_POLL_MS)
            val engaged = PresenceEngagementTracker.isEngaged()
            if (engaged != userEngaged) {
                userEngaged = engaged
            }
        }
    }

    LaunchedEffect(user?.uid) {
        if (user?.uid == null) {
            presenceRepository.clearOnlineCount()
        }
    }

    LaunchedEffect(onlinePlayerCount) {
        SegmentedNotificationState.setOnlineCount(onlinePlayerCount)
    }

    var appForegroundGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(appInForeground) {
        if (appInForeground) {
            appForegroundGeneration++
        }
    }

    LaunchedEffect(appForegroundGeneration, user?.uid, backgroundUsageEnabled) {
        val uid = user?.uid ?: return@LaunchedEffect
        if (
            MatchmakingBackgroundCoordinator.foregroundServiceOwnsHeartbeats(
                context,
                backgroundUsageEnabled,
            )
        ) {
            return@LaunchedEffect
        }
        PresenceRepository.prepareOnlineCountRefreshOnResume()
        delay(PresenceRepository.ONLINE_COUNT_RESUME_REFRESH_DELAY_MS)
        if (!appInForeground) return@LaunchedEffect
        if (
            MatchmakingBackgroundCoordinator.foregroundServiceOwnsHeartbeats(
                context,
                backgroundUsageEnabled,
            )
        ) {
            return@LaunchedEffect
        }
        runCatching {
            presenceRepository.touchPresence(uid, includeOnlineCount = true)
        }
    }

    LaunchedEffect(
        user?.uid,
        appInForeground,
        userEngaged,
        hasQueueEntry,
        matchmakingInProgress,
        activeMatch?.id,
        activeMatch?.status,
        backgroundUsageEnabled,
    ) {
        val uid = user?.uid ?: return@LaunchedEffect
        fun shouldMaintainPresence(): Boolean =
            computeSessionNeedsPresenceHeartbeat(
                appInForeground = appInForeground,
                userEngaged = userEngaged,
                uid = uid,
                match = activeMatch,
                hasQueueEntry = hasQueueEntry,
                queueJoinedAtMs = queueJoinedAtMs,
                matchmakingInProgress = matchmakingInProgress,
            )
        fun serviceOwnsHeartbeats(): Boolean =
            MatchmakingBackgroundCoordinator.foregroundServiceOwnsHeartbeats(
                context,
                backgroundUsageEnabled,
            )
        fun stopLocalPresence(clearOnlineCount: Boolean) {
            presenceRepository.clearPresence(uid)
            if (clearOnlineCount) {
                presenceRepository.clearOnlineCount()
            }
        }

        if (!shouldMaintainPresence()) {
            if (!serviceOwnsHeartbeats() && !MatchSessionMonitor.isMatchmakingInProgress()) {
                stopLocalPresence(clearOnlineCount = !PresenceEngagementTracker.isEngaged())
            }
            return@LaunchedEffect
        }
        if (serviceOwnsHeartbeats()) {
            return@LaunchedEffect
        }
        val queueOnlyPresence = matchmakingInProgress && activeMatch == null
        if (queueOnlyPresence && onlinePlayerCount == null) {
            runCatching {
                presenceRepository.touchPresence(
                    uid,
                    forceAuthRefresh = false,
                    awaitServerAck = false,
                    includeOnlineCount = true,
                )
            }
        }
        if (queueOnlyPresence) {
            val joinDeadlineMs = System.currentTimeMillis() + 12_000L
            while (
                MatchSessionMonitor.queueElapsedAnchorMs() == null &&
                System.currentTimeMillis() < joinDeadlineMs
            ) {
                if (!shouldMaintainPresence()) return@LaunchedEffect
                delay(200)
            }
        }
        presenceRepository.touchPresence(
            uid,
            forceAuthRefresh = false,
            awaitServerAck = false,
            includeOnlineCount = onlinePlayerCount == null,
        )
        var heartbeat = 0
        while (true) {
            delay(PresenceRepository.HEARTBEAT_INTERVAL_MS)
            if (!shouldMaintainPresence()) {
                if (!serviceOwnsHeartbeats() && !MatchSessionMonitor.isMatchmakingInProgress()) {
                    stopLocalPresence(clearOnlineCount = !PresenceEngagementTracker.isEngaged())
                }
                break
            }
            if (serviceOwnsHeartbeats()) {
                break
            }
            heartbeat++
            val nowMs = System.currentTimeMillis()
            presenceRepository.touchPresence(
                uid,
                awaitServerAck = false,
                includeOnlineCount = !queueOnlyPresence &&
                    PresenceRepository.shouldRequestOnlineCount(nowMs),
            )
        }
    }

    LifecycleResumeEffect(user?.uid) {
        PresenceEngagementTracker.recordInteraction()
        userEngaged = true
        val uid = user?.uid
        if (uid != null) {
            scope.launch {
                runCatching {
                    MatchSessionMonitor.refreshOnResume(
                        forceServerSync = MatchSessionMonitor.isMatchmakingInProgress(),
                    )
                }
            }
        }
        onPauseOrDispose { }
    }
    val queueAnchorMs = queueTimerAnchorMs ?: queueJoinedAtMs
    val queueElapsedSeconds = rememberQueueElapsedSeconds(
        anchorMs = queueAnchorMs?.takeIf { matchmakingInProgress },
    ) ?: 0L
    var matchElapsedSeconds by remember(activeMatch?.id) { mutableStateOf(0L) }

    LaunchedEffect(activeMatch?.id, activeMatch?.status, activeMatch?.createdAt) {
        val match = activeMatch
        if (match == null || match.createdAt <= 0L) {
            matchElapsedSeconds = 0L
            return@LaunchedEffect
        }
        if (match.status == MatchStatus.COMPLETED || match.status == MatchStatus.ABANDONED) {
            matchElapsedSeconds = ((System.currentTimeMillis() - match.createdAt) / 1_000).coerceAtLeast(0L)
            return@LaunchedEffect
        }
        if (match.status != MatchStatus.ACTIVE && match.status != MatchStatus.LOBBY) {
            matchElapsedSeconds = 0L
            return@LaunchedEffect
        }
        val startedAtMs = match.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        while (true) {
            val nowMs = System.currentTimeMillis()
            matchElapsedSeconds = ((nowMs - startedAtMs) / 1_000).coerceAtLeast(0L)
            delay(
                SevenSegmentColonBlink.delayMsUntilNextSecondBoundary(startedAtMs, nowMs)
                    .coerceAtLeast(1L),
            )
        }
    }

    LaunchedEffect(
        hasQueueEntry,
        matchmakingInProgress,
        backgroundUsageEnabled,
    ) {
        if (!MatchSessionMonitor.shouldSendQueueHeartbeats()) return@LaunchedEffect
        var consecutiveFailures = 0
        fun serviceOwnsHeartbeats(): Boolean =
            MatchmakingBackgroundCoordinator.foregroundServiceOwnsHeartbeats(
                context,
                backgroundUsageEnabled,
            )
        if (!serviceOwnsHeartbeats()) {
            matchRepository.sendQueueHeartbeat()
        }
        while (true) {
            if (!MatchSessionMonitor.shouldSendQueueHeartbeats()) break
            if (serviceOwnsHeartbeats()) {
                delay(PresenceRepository.HEARTBEAT_INTERVAL_MS)
                continue
            }
            if (!matchRepository.sendQueueHeartbeat()) {
                consecutiveFailures += 1
                if (consecutiveFailures >= 3) {
                    runCatching { MatchSessionMonitor.signalQueueDocLostIfAbsentOnServer() }
                    break
                }
            } else {
                consecutiveFailures = 0
            }
            delay(PresenceRepository.HEARTBEAT_INTERVAL_MS)
        }
    }

    LaunchedEffect(connectionStatus, matchmakingInProgress, hasQueueEntry) {
        if (!connectionStatus.isServerConnected()) return@LaunchedEffect
        if (!matchmakingInProgress) return@LaunchedEffect
        if (
            !MatchSessionMonitor.shouldSendQueueHeartbeats() &&
            !hasQueueEntry &&
            MatchSessionMonitor.queueJoinedAtMs.value == null
        ) {
            MatchSessionMonitor.requestQueueRecovery()
        }
        MatchmakingBackgroundCoordinator.sync(context)
    }

    LifecycleResumeEffect(activity, backgroundUsageEnabled, matchFoundNotificationsEnabled) {
        matchFoundNotificationsEnabled =
            matchmakingPreferences.isMatchFoundNotificationsEnabled() &&
                NotificationPermissionHelper.hasPostNotificationsPermission(context)
        onPauseOrDispose { }
    }

    LaunchedEffect(backgroundUsageEnabled) {
        MatchmakingBackgroundCoordinator.sync(context)
    }

    LaunchedEffect(
        backgroundUsageEnabled,
        activeMatch?.id,
        activeMatch?.status,
        queueJoinedAtMs,
        hasQueueEntry,
        matchmakingInProgress,
    ) {
        MatchmakingBackgroundCoordinator.sync(context)
    }

    LifecycleResumeEffect(backgroundUsageEnabled, user?.uid) {
        MatchmakingBackgroundCoordinator.sync(context)
        onPauseOrDispose { }
    }

    LaunchedEffect(
        activeMatch?.id,
        activeMatch?.status,
        matchFoundNotificationsEnabled,
        backgroundUsageEnabled,
        user?.uid,
        appInForeground,
    ) {
        val match = activeMatch
        val uid = user?.uid ?: return@LaunchedEffect
        if (
            !MatchFoundNotificationPolicy.shouldPostJoinMatchNotification(
                appInForeground = appInForeground,
                matchStatus = match?.status,
                matchFoundNotificationsEnabled = matchFoundNotificationsEnabled,
                backgroundUsageEnabled = backgroundUsageEnabled,
                hasPostNotificationsPermission =
                    NotificationPermissionHelper.hasPostNotificationsPermission(context),
                lastNotifiedMatchId = lastNotifiedMatchId,
                matchId = match?.id.orEmpty(),
            )
        ) {
            return@LaunchedEffect
        }
        val opponentName = match!!.opponentName(uid)
        MatchNotificationHelper.showMatchFound(context, match.id, opponentName)
        lastNotifiedMatchId = match.id
    }

    LifecycleResumeEffect(hasQueueEntry, queueJoinedAtMs, matchmakingInProgress, backgroundUsageEnabled) {
        if (
            MatchSessionMonitor.shouldSendQueueHeartbeats() &&
            !MatchmakingBackgroundCoordinator.foregroundServiceOwnsHeartbeats(
                context,
                backgroundUsageEnabled,
            )
        ) {
            scope.launch {
                matchRepository.sendQueueHeartbeat()
            }
        }
        onPauseOrDispose { }
    }

    RpsTheme(style = themeStyle) {
        val roundResolutionPulseNotifier = remember { RoundResolutionPulseNotifier() }
        CompositionLocalProvider(
            LocalClockSoundMuted provides clockSoundMuted,
            LocalNetworkConnectionStatus provides connectionStatus,
            LocalRoundResolutionPulse provides roundResolutionPulseNotifier,
        ) {
            RoundResolutionFeedbackEffect(
                activeMatch = activeMatch,
                userId = user?.uid,
                pulseNotifier = roundResolutionPulseNotifier,
            )
            GlobalMatchClockTickEffect(
                activeMatch = activeMatch,
                userId = user?.uid,
                muted = clockSoundMuted,
                appInForeground = appInForeground,
                pulseNotifier = roundResolutionPulseNotifier,
            )
            val topPanelColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
            val topPanelGradient = Brush.linearGradient(
                colors = listOf(
                    topPanelColor.copy(alpha = 0.98f),
                    androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f),
                    topPanelColor,
                    androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
                    topPanelColor,
                ),
            )
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RectangleShape,
                    color = topPanelColor,
                    tonalElevation = 2.dp,
                    shadowElevation = 2.dp,
                ) {
                    RpsTopStatusBar(
                        background = Modifier.background(topPanelGradient),
                        leftContent = {
                            if (user != null) {
                                val matchEndTransitionActive = activeMatch?.let { match ->
                                    (match.status == MatchStatus.COMPLETED || match.status == MatchStatus.ABANDONED) &&
                                        roundResolutionPulseNotifier.isLiveMatch(match.id)
                                } == true
                                val inLobby = activeMatch?.status == MatchStatus.LOBBY
                                val inMatch = activeMatch?.status == MatchStatus.ACTIVE || matchEndTransitionActive
                                val inQueue = queueJoinedAtMs != null &&
                                    matchmakingInProgress &&
                                    !inMatch &&
                                    !inLobby
                                val sessionTimerSeconds = when {
                                    inMatch || inLobby -> matchElapsedSeconds
                                    else -> queueElapsedSeconds
                                }
                                val sessionTimerAnchorMs = when {
                                    inMatch || inLobby ->
                                        activeMatch?.createdAt?.takeIf { it > 0L }
                                    else -> queueAnchorMs?.takeIf { matchmakingInProgress }
                                }
                                val playerClockStopped = inMatch &&
                                    (matchEndTransitionActive ||
                                        activeMatch?.isPlayerClockRunning(user?.uid) != true)
                                val onlineCountDisplay = when (connectionStatus) {
                                    NetworkConnectionStatus.Offline -> TopBarOnlineCountDisplay.Offline
                                    NetworkConnectionStatus.Checking -> TopBarOnlineCountDisplay.Loading
                                    NetworkConnectionStatus.Connected -> when (val count = onlinePlayerCount) {
                                        null -> TopBarOnlineCountDisplay.Loading
                                        else -> TopBarOnlineCountDisplay.Value(count)
                                    }
                                }
                                val connectionProbeActive =
                                    (inQueue || inLobby || inMatch) &&
                                    connectionStatus == NetworkConnectionStatus.Checking &&
                                    NetworkDataActivityKind.Connection !in activeNetworkKinds &&
                                    NetworkDataActivityKind.Queue !in activeNetworkKinds
                                SegmentedDisplayPulseEffect(
                                    activeNetworkKinds = activeNetworkKinds,
                                    connectionProbeActive = connectionProbeActive,
                                ) {
                                    TopBarSegmentedQueueIndicator(
                                        onlineCount = onlineCountDisplay,
                                        inMatch = inMatch,
                                        inQueue = inQueue,
                                        inLobby = inLobby,
                                        elapsedSeconds = sessionTimerSeconds,
                                        playerClockStopped = playerClockStopped,
                                        timerAnchorMs = sessionTimerAnchorMs,
                                    )
                                }
                            }
                        },
                        rightContent = {
                            val iconSlot = Modifier.weight(1f)
                            CompositionLocalProvider(
                                LocalMinimumInteractiveComponentSize provides 0.dp,
                            ) {
                                BackgroundUsageToggleButton(
                                    enabled = backgroundUsageEnabled,
                                    modifier = iconSlot,
                                    onToggle = {
                                        if (backgroundUsageEnabled) {
                                            backgroundUsageEnabled = false
                                            matchmakingPreferences.setBackgroundUsageEnabled(false)
                                            MatchmakingBackgroundCoordinator.sync(context)
                                        } else {
                                            backgroundUsageEnabled = true
                                            matchmakingPreferences.setBackgroundUsageEnabled(true)
                                            MatchmakingBackgroundCoordinator.sync(context)
                                            if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) {
                                                BatteryOptimizationHelper.openBatteryOptimizationSettings(context)
                                            }
                                        }
                                    },
                                )
                                MatchFoundNotificationToggleButton(
                                    enabled = matchFoundNotificationsEnabled,
                                    modifier = iconSlot,
                                    onToggle = {
                                        if (matchFoundNotificationsEnabled) {
                                            matchFoundNotificationsEnabled = false
                                            matchmakingPreferences.setMatchFoundNotificationsEnabled(false)
                                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notificationPermissionLauncher.launch(
                                                Manifest.permission.POST_NOTIFICATIONS,
                                            )
                                        } else {
                                            matchFoundNotificationsEnabled = true
                                            matchmakingPreferences.setMatchFoundNotificationsEnabled(true)
                                            MatchNotificationHelper.ensureChannels(context)
                                        }
                                    },
                                )
                                ClockSoundMuteButton(
                                    muted = clockSoundMuted,
                                    modifier = iconSlot,
                                    onMutedChange = { muted ->
                                        clockSoundMuted = muted
                                        soundPreferences.setClockMuted(muted)
                                    },
                                )
                                AppearanceMenuButton(
                                    currentStyle = themeStyle,
                                    modifier = iconSlot,
                                    onStyleSelected = { style ->
                                        themeStyle = style
                                        themePreferences.set(style)
                                    },
                                )
                            }
                        },
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    RpsNavGraph()
                }
            }
        }
    }
}

@Composable
private fun GlobalMatchClockTickEffect(
    activeMatch: Match?,
    userId: String?,
    muted: Boolean,
    appInForeground: Boolean,
    pulseNotifier: RoundResolutionPulseNotifier,
) {
    val myClockRunning = activeMatch?.isPlayerClockRunning(userId) == true
    val suppressForResolutionFeedback = activeMatch?.let { match ->
        pulseNotifier.shouldSuppressClockTickFor(match.lastResolvedRound(), match.id)
    } == true
    val shouldTick = appInForeground &&
        myClockRunning &&
        !muted &&
        !suppressForResolutionFeedback
    val openRound = activeMatch?.openRound()

    DisposableEffect(Unit) {
        onDispose { MatchClockSoundController.sync(false) }
    }

    LaunchedEffect(
        shouldTick,
        appInForeground,
        activeMatch?.id,
        activeMatch?.status,
        openRound?.roundNumber,
        openRound?.player1Submitted,
        openRound?.player2Submitted,
    ) {
        MatchClockSoundController.sync(shouldTick)
    }
}
