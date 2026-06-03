package com.rpsonline.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.model.MatchHistoryEntry
import com.rpsonline.app.data.model.UserProfile
import com.rpsonline.app.data.preferences.HighlightedMatchCache
import com.rpsonline.app.data.preferences.HighlightedMatchSession
import com.rpsonline.app.data.preferences.MatchModePreferences
import com.rpsonline.app.data.preferences.SoundPreferences
import com.rpsonline.app.data.repository.AuthRepository
import com.rpsonline.app.data.repository.HighlightedMatchFunctions
import com.rpsonline.app.data.repository.MatchRepository
import com.rpsonline.app.data.monitoring.NetworkDataActivityTracker
import com.rpsonline.app.data.repository.connectivityFailureUserMessage
import com.rpsonline.app.data.repository.isQuotaExceededError
import com.rpsonline.app.data.repository.quotaExceededUserMessage
import com.rpsonline.app.data.repository.userFacingFirebaseError
import com.rpsonline.app.data.repository.MatchSessionMonitor
import com.rpsonline.app.data.repository.shouldAutoNavigateToLiveMatch
import com.rpsonline.app.data.repository.shouldClearStaleQueueUiOnResume
import com.rpsonline.app.data.repository.shouldReconcileQueueSessionOnResume
import com.rpsonline.app.platform.MatchmakingBackgroundCoordinator
import com.rpsonline.app.data.repository.MatchmakingFunctions
import com.rpsonline.app.data.repository.PresenceRepository
import com.rpsonline.app.data.repository.UserProfileSync
import com.rpsonline.app.data.repository.UserRepository
import com.rpsonline.app.domain.MatchMode
import com.rpsonline.app.domain.enrichMatchHistoryWithOpponentElos
import com.rpsonline.app.domain.weeklyChartWindowStartMs
import com.rpsonline.app.ui.segment.SevenSegmentColonBlink
import com.rpsonline.app.ui.util.ClockTickPlayer
import com.rpsonline.app.ui.util.playReadyFeedback
import com.rpsonline.app.ui.util.queueElapsedSecondsFromAnchor
import com.rpsonline.app.ui.util.triggerMatchFoundFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PreGameSyncUiState(
    val matchId: String,
    val myDisplayName: String,
    val opponentUid: String,
    val opponentDisplayName: String,
    val myReady: Boolean,
    val opponentReady: Boolean,
    val readyDeadlineAtMs: Long,
)

data class HomeUiState(
    val isLoading: Boolean = true,
    val profile: UserProfile? = null,
    val selectedMatchModes: Set<MatchMode> = MatchMode.DEFAULT_SELECTION,
    val activeMatchId: String? = null,
    val preGameSync: PreGameSyncUiState? = null,
    val isJoiningQueue: Boolean = false,
    val isInQueue: Boolean = false,
    /** Null until Firestore `joinedAt` is available for the queue doc. */
    val queueElapsedSeconds: Long? = null,
    val matchmakingError: String? = null,
    val highlightedMatch: MatchHistoryEntry? = null,
    val isHighlightedMatchDismissed: Boolean = false,
    val error: String? = null,
    val isSigningOut: Boolean = false,
)

class HomeViewModel(
    private val authRepository: AuthRepository = AuthRepository(),
    private val userRepository: UserRepository = UserRepository(),
    private val presenceRepository: PresenceRepository = PresenceRepository(),
    private val matchRepository: MatchRepository = MatchRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var profileJob: Job? = null
    private var activeMatchJob: Job? = null
    private var queueObserveJob: Job? = null
    private var queueTimerJob: Job? = null
    private var refreshJob: Job? = null
    private var matchmakingJob: Job? = null
    private var leaveQueueJob: Job? = null
    private var awaitingMatchFromQueue = false
    private var awaitingMatchStartedAtMs: Long? = null
    private var matchmakingGeneration = 0
    private var queuedMatchModes: Set<MatchMode>? = null
    private var preGameReadyJob: Job? = null
    private var preGameReadyMatchId: String? = null
    private var gameNavigationVerifyJob: Job? = null
    private var highlightedMatchJob: Job? = null
    private var lastProfileStatsFingerprint: Int? = null
    private var appContext: Context? = null
    private var preGameReadyFeedbackMatchId: String? = null
    private var lastPreGameMyReady = false
    private var lastPreGameOpponentReady = false

    companion object {
        private const val MATCH_ASSIGNMENT_GRACE_MS = 30_000L
        private const val MATCHMAKING_WATCHDOG_MS = 45_000L
        private const val JOIN_QUEUE_WATCHDOG_MS = 15_000L
        private const val JOIN_QUEUE_TIMEOUT_MS = 25_000L
        private const val JOIN_QUEUE_QUOTA_RETRY_ATTEMPTS = 3
        private const val JOIN_QUEUE_QUOTA_RETRY_DELAY_MS = 1_500L
        private const val PROFILE_READY_TIMEOUT_MS = 6_000L
        private const val PRE_GAME_READY_TIMEOUT_MESSAGE =
            "Opponent did not ready in time. Tap Find Match to try again."
        private const val PRE_GAME_READY_TICK_GAP_MS = 120L
    }

    val navigateToGameMatchId: StateFlow<String?> = MatchSessionMonitor.pendingGameNavigationMatchId

    init {
        viewModelScope.launch {
            authRepository.authStateFlow().collect { user ->
                profileJob?.cancel()
                profileJob = null
                if (user == null) {
                    refreshJob?.cancel()
                    refreshJob = null
                    activeMatchJob?.cancel()
                    activeMatchJob = null
                    queueObserveJob?.cancel()
                    queueObserveJob = null
                    highlightedMatchJob?.cancel()
                    highlightedMatchJob = null
                    HighlightedMatchSession.clear()
                    lastProfileStatsFingerprint = null
                    appContext = null
                    stopQueueTimer()
                    awaitingMatchFromQueue = false
                    MatchSessionMonitor.consumeGameNavigation()
                    MatchSessionMonitor.setMatchmakingInProgress(false)
                    MatchSessionMonitor.onQueueRecoveryFailed = null
                    MatchSessionMonitor.onMatchSessionEnded = null
                    MatchSessionMonitor.setRecoveryMatchModes(null)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            profile = null,
                            activeMatchId = null,
                            isJoiningQueue = false,
                            isInQueue = false,
                            queueElapsedSeconds = null,
                            matchmakingError = null,
                            highlightedMatch = null,
                            isHighlightedMatchDismissed = false,
                            isSigningOut = false,
                            error = null,
                        )
                    }
                } else {
                    val highlightedDismissedForSession = HighlightedMatchSession.dismissed
                    if (_uiState.value.profile == null) {
                        _uiState.update {
                            it.copy(
                                profile = authRepository.fallbackProfile(user),
                                isLoading = false,
                                isHighlightedMatchDismissed = highlightedDismissedForSession,
                            )
                        }
                    } else if (highlightedDismissedForSession && !_uiState.value.isHighlightedMatchDismissed) {
                        _uiState.update { it.copy(isHighlightedMatchDismissed = true) }
                    }
                    profileJob = viewModelScope.launch {
                        userRepository.observeUserProfile(user.uid).collect { profile ->
                            if (profile != null) {
                                val fingerprint = profile.postMatchStatsFingerprint()
                                val statsChanged = lastProfileStatsFingerprint != null &&
                                    lastProfileStatsFingerprint != fingerprint
                                lastProfileStatsFingerprint = fingerprint
                                _uiState.update { it.copy(profile = profile, isLoading = false, error = null) }
                                if (
                                    !HighlightedMatchSession.dismissed &&
                                    (statsChanged || _uiState.value.highlightedMatch == null)
                                ) {
                                    loadHighlightedMatch(user.uid, profile.elo)
                                }
                            }
                        }
                    }
                    if (!HighlightedMatchSession.dismissed) {
                        loadHighlightedMatch(user.uid, _uiState.value.profile?.elo ?: 1000)
                    }
                    refresh(user)
                    observeActiveMatch()
                    observeQueue()
                    MatchSessionMonitor.onQueueRecoveryFailed = { message ->
                        failMatchmaking(matchmakingGeneration, message)
                    }
                    MatchSessionMonitor.onMatchSessionEnded = {
                        resetQueueUiAfterMatchFinished()
                    }
                }
            }
        }
    }

    fun startMatchmakingWithSavedPreferences(context: Context) {
        loadMatchModePreferences(context)
        startMatchmaking(context, _uiState.value.selectedMatchModes)
    }

    fun startMatchmaking(context: Context, matchModes: Set<MatchMode>) {
        if (
            _uiState.value.isJoiningQueue ||
            _uiState.value.isInQueue ||
            _uiState.value.activeMatchId != null
        ) {
            return
        }
        matchmakingJob?.cancel()
        val generation = ++matchmakingGeneration
        refreshJob?.cancel()
        MatchSessionMonitor.discardTerminalActiveMatchIfPresent()
        NetworkDataActivityTracker.beginQueueJoinBurstSuppression()
        MatchSessionMonitor.setMatchmakingInProgress(true)
        queuedMatchModes = matchModes
        MatchSessionMonitor.setRecoveryMatchModes(matchModes)
        awaitingMatchFromQueue = true
        awaitingMatchStartedAtMs = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                isJoiningQueue = true,
                isInQueue = false,
                queueElapsedSeconds = null,
                matchmakingError = null,
            )
        }
        matchmakingJob = viewModelScope.launch {
            leaveQueueJob?.let { priorLeave ->
                withTimeoutOrNull(5_000) { priorLeave.join() }
            }
            runCatching { MatchSessionMonitor.awaitSessionBootstrap() }
            val watchdog = launch {
                delay(MATCHMAKING_WATCHDOG_MS)
                if (generation != matchmakingGeneration) return@launch
                if (!_uiState.value.isJoiningQueue && !_uiState.value.isInQueue) return@launch
                failMatchmaking(
                    generation = generation,
                    message = "Matchmaking timed out. Check your connection and try again.",
                )
            }
            val joinWatchdog = launch {
                delay(JOIN_QUEUE_WATCHDOG_MS)
                if (generation != matchmakingGeneration) return@launch
                if (!_uiState.value.isJoiningQueue) return@launch
                failMatchmaking(
                    generation = generation,
                    message = "Could not join the matchmaking queue in time. Check your connection and try again.",
                )
            }
            var queueJoinPendingServerConfirm = false
            try {
                withTimeout(JOIN_QUEUE_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        if (!isFirebaseAvailableForQueueAction("join the queue", generation)) return@withContext
                        val profile = awaitUserProfileReady()
                        if (generation != matchmakingGeneration) return@withContext
                        val joinResult = joinQueueWithQuotaRetry(matchModes, profile)
                        if (generation != matchmakingGeneration) {
                            if (joinResult.immediateMatchId == null) {
                                authRepository.currentUserId?.let { matchRepository.leaveQueueBestEffort(it) }
                            }
                            return@withContext
                        }
                        if (joinResult.immediateMatchId != null) {
                            val immediateMatch = runCatching {
                                matchRepository.getMatchFromServer(joinResult.immediateMatchId)
                            }.getOrNull()
                            if (
                                immediateMatch == null ||
                                immediateMatch.status == MatchStatus.ABANDONED ||
                                (
                                    immediateMatch.status == MatchStatus.LOBBY &&
                                        immediateMatch.isReadyDeadlineExpired()
                                    )
                            ) {
                                runCatching {
                                    matchRepository.confirmMatchReady(joinResult.immediateMatchId)
                                }
                                failMatchmaking(
                                    generation = generation,
                                    message = PRE_GAME_READY_TIMEOUT_MESSAGE,
                                )
                                return@withContext
                            }
                            awaitingMatchFromQueue = false
                            awaitingMatchStartedAtMs = null
                            stopQueueTimer()
                            MatchSessionMonitor.setMatchmakingInProgress(true)
                            _uiState.update {
                                it.copy(
                                    isJoiningQueue = false,
                                    isInQueue = false,
                                    queueElapsedSeconds = null,
                                    matchmakingError = null,
                                )
                            }
                            return@withContext
                        }
                        joinResult.serverJoinedAtMs?.let { serverJoinedAtMs ->
                            withContext(Dispatchers.Main) {
                                enterConfirmedQueue(serverJoinedAtMs)
                            }
                        }
                        queueJoinPendingServerConfirm = joinResult.serverJoinedAtMs == null
                    }
                }
                if (generation == matchmakingGeneration && queueJoinPendingServerConfirm) {
                    launch {
                        val serverJoinedAtMs =
                            matchRepository.awaitQueueJoinedAtFromServer(timeoutMs = 15_000)
                        if (generation != matchmakingGeneration) return@launch
                        if (serverJoinedAtMs != null) {
                            enterConfirmedQueue(serverJoinedAtMs)
                        } else if (
                            _uiState.value.isJoiningQueue &&
                            !_uiState.value.isInQueue
                        ) {
                            failMatchmaking(
                                generation = generation,
                                message = "Could not confirm queue join on the server. Check your connection and try again.",
                            )
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                if (generation != matchmakingGeneration) return@launch
                failMatchmaking(
                    generation = generation,
                    message = "Could not join the matchmaking queue in time. Check your connection and try again.",
                )
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Exception) {
                if (generation != matchmakingGeneration) return@launch
                val message = when {
                    MatchmakingFunctions.toJoinErrorMessage(e) != null ->
                        MatchmakingFunctions.toJoinErrorMessage(e)!!
                    isQuotaExceededError(e) -> quotaExceededUserMessage()
                    userFacingFirebaseError(e, fallback = "").isNotBlank() ->
                        userFacingFirebaseError(e, fallback = "")
                    e.message?.contains("profile", ignoreCase = true) == true -> e.message!!
                    e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true ->
                        "Could not write to Firestore (permission denied). In Firebase Console set App Check to Monitoring for Firestore and Auth, then try again."
                    e.cause?.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true ->
                        "Could not write to Firestore (permission denied). In Firebase Console set App Check to Monitoring for Firestore and Auth, then try again."
                    e.message?.contains("Timed out", ignoreCase = true) == true ||
                        e.message?.contains("server", ignoreCase = true) == true ->
                        "Could not join the matchmaking queue in time. Check your connection and try again."
                    !e.message.isNullOrBlank() -> e.message!!
                    else -> "Matchmaking failed. Check your connection and try again."
                }
                failMatchmaking(generation, message)
            } finally {
                watchdog.cancel()
                joinWatchdog.cancel()
                if (
                    generation == matchmakingGeneration &&
                    _uiState.value.isJoiningQueue &&
                    !_uiState.value.isInQueue &&
                    !queueJoinPendingServerConfirm
                ) {
                    failMatchmaking(
                        generation = generation,
                        message = "Could not join the matchmaking queue. Check your connection and try again.",
                    )
                }
            }
        }
    }

    private fun failMatchmaking(generation: Int, message: String) {
        if (generation != matchmakingGeneration) return
        cleanupMatchmakingSession()
        _uiState.update {
            it.copy(
                isJoiningQueue = false,
                isInQueue = false,
                queueElapsedSeconds = null,
                matchmakingError = message,
            )
        }
    }

    private fun cleanupMatchmakingSession() {
        NetworkDataActivityTracker.endQueueJoinBurstSuppression()
        MatchSessionMonitor.setMatchmakingInProgress(false)
        awaitingMatchFromQueue = false
        awaitingMatchStartedAtMs = null
        queuedMatchModes = null
        preGameReadyJob?.cancel()
        preGameReadyJob = null
        stopQueueTimer()
        MatchSessionMonitor.clearQueueState()
        authRepository.currentUserId?.let { uid ->
            matchRepository.leaveQueueBestEffort(uid)
        }
    }
    private suspend fun awaitUserProfileReady(): UserProfile {
        val user = authRepository.currentUser ?: error("Not signed in")
        authRepository.queueReadyProfile(user.uid)?.let { return it }
        _uiState.value.profile?.takeIf { it.uid == user.uid }?.let { cached ->
            UserProfileSync.rememberQueueReady(user.uid, cached)
            return cached
        }
        return withTimeout(PROFILE_READY_TIMEOUT_MS) {
            authRepository.ensureUserProfile(
                uid = user.uid,
                displayName = user.displayName,
                photoUrl = user.photoUrl?.toString(),
            )
        }
    }

    fun leaveQueue() {
        val userId = authRepository.currentUserId
        resetMatchmakingLocalState()
        clearQueueUiState()
        leaveQueueJob?.cancel()
        leaveQueueJob = if (userId != null) {
            viewModelScope.launch {
                withTimeoutOrNull(5_000) {
                    runCatching { matchRepository.leaveQueueForUser(userId) }
                }
            }
        } else {
            null
        }
    }

    fun consumeNavigateToGameMatch() {
        MatchSessionMonitor.consumeGameNavigation()
        MatchSessionMonitor.setMatchmakingInProgress(false)
    }

    private fun observeQueue() {
        MatchSessionMonitor.ensureStarted()
        queueObserveJob?.cancel()
        queueObserveJob = viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                MatchSessionMonitor.hasQueueEntry,
                MatchSessionMonitor.queueJoinedAtMs,
                MatchSessionMonitor.queueTimerAnchorMs,
            ) { hasEntry, joinedAtMs, timerAnchorMs -> Triple(hasEntry, joinedAtMs, timerAnchorMs) }
                .collect { (hasEntry, joinedAtMs, timerAnchorMs) ->
                val elapsedAnchorMs = timerAnchorMs ?: joinedAtMs
                if (elapsedAnchorMs != null && !hasEntry && MatchSessionMonitor.isMatchmakingInProgress()) {
                    syncConfirmedQueueUi()
                    MatchSessionMonitor.requestQueueRecovery()
                    return@collect
                }
                if (elapsedAnchorMs == null) {
                    val joinInFlight = _uiState.value.isJoiningQueue ||
                        MatchSessionMonitor.isQueueEntryPending()
                    if (joinInFlight && MatchSessionMonitor.isMatchmakingInProgress()) {
                        return@collect
                    }
                    if (MatchSessionMonitor.isMatchmakingInProgress()) {
                        MatchSessionMonitor.requestQueueRecovery()
                        return@collect
                    }
                    stopQueueTimer()
                    // Queue doc may disappear slightly before activeMatch arrives; keep a short handoff window.
                    val withinAssignmentGrace = awaitingMatchFromQueue &&
                        ((awaitingMatchStartedAtMs?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE) <= MATCH_ASSIGNMENT_GRACE_MS)
                    if (!withinAssignmentGrace) {
                        awaitingMatchFromQueue = false
                        awaitingMatchStartedAtMs = null
                    }
                    _uiState.update {
                        it.copy(
                            isJoiningQueue = false,
                            isInQueue = false,
                            queueElapsedSeconds = null,
                        )
                    }
                } else {
                    syncConfirmedQueueUi()
                }
            }
        }
    }

    /** Applies server `joinedAt` from the queue doc (listener or SERVER fetch). */
    private fun enterConfirmedQueue(serverJoinedAtMs: Long) {
        MatchSessionMonitor.confirmQueueJoinedAt(serverJoinedAtMs)
        syncConfirmedQueueUi()
    }

    private fun syncConfirmedQueueUi() {
        val joinedAtMs = MatchSessionMonitor.queueElapsedAnchorMs() ?: return
        val elapsed = queueElapsedSecondsFromAnchor(joinedAtMs)
        awaitingMatchFromQueue = true
        _uiState.update {
            it.copy(
                isJoiningQueue = false,
                isInQueue = true,
                queueElapsedSeconds = elapsed,
                matchmakingError = null,
            )
        }
        ensureQueueElapsedTicker()
    }

    /** Single ticker driven by [MatchSessionMonitor.queueElapsedAnchorMs] (Firestore `joinedAt`). */
    private fun ensureQueueElapsedTicker() {
        queueTimerJob?.cancel()
        queueTimerJob = viewModelScope.launch {
            while (isActive) {
                if (!_uiState.value.isInQueue) {
                    delay(250)
                    continue
                }
                val anchorMs = MatchSessionMonitor.queueElapsedAnchorMs()
                if (anchorMs == null) {
                    delay(250)
                    continue
                }
                val nowMs = System.currentTimeMillis()
                val elapsed = queueElapsedSecondsFromAnchor(anchorMs, nowMs)
                _uiState.update { it.copy(queueElapsedSeconds = elapsed) }
                delay(
                    SevenSegmentColonBlink.delayMsUntilNextSecondBoundary(anchorMs, nowMs)
                        .coerceAtLeast(1L),
                )
            }
        }
    }

    private fun stopQueueTimer() {
        queueTimerJob?.cancel()
        queueTimerJob = null
    }

    private fun observeActiveMatch() {
        MatchSessionMonitor.ensureStarted()
        activeMatchJob?.cancel()
        activeMatchJob = viewModelScope.launch {
            combine(
                MatchSessionMonitor.activeMatch,
                MatchSessionMonitor.matchmakingInProgress,
                MatchSessionMonitor.matchLaunchUiNudge,
            ) { match, _, _ -> match }
                .collect { match ->
                val uid = authRepository.currentUserId
                if (match == null) {
                    stopPreGameReadyLoop()
                    if (!MatchSessionMonitor.hasPendingGameNavigation()) {
                        _uiState.update { it.copy(preGameSync = null, activeMatchId = null) }
                    }
                    return@collect
                }
                if (uid == null || !match.isParticipant(uid)) {
                    stopPreGameReadyLoop()
                    if (MatchSessionMonitor.hasPendingGameNavigation()) {
                        abortFalseMatchFoundAssignment()
                    } else {
                        _uiState.update { it.copy(preGameSync = null, activeMatchId = null) }
                    }
                    return@collect
                }

                val inMatchmakingFlow = MatchSessionMonitor.isMatchmakingInProgress() ||
                    awaitingMatchFromQueue ||
                    _uiState.value.isInQueue ||
                    _uiState.value.isJoiningQueue ||
                    _uiState.value.preGameSync != null

                when (match.status) {
                    MatchStatus.LOBBY -> {
                        val shouldSync = inMatchmakingFlow ||
                            _uiState.value.preGameSync?.matchId == match.id
                        if (!shouldSync) {
                            val lobbyUid = authRepository.currentUserId
                            val resumingQueueOrJoin = _uiState.value.isInQueue ||
                                _uiState.value.isJoiningQueue ||
                                MatchSessionMonitor.hasQueueEntry.value
                            if (
                                lobbyUid != null &&
                                shouldAutoNavigateToLiveMatch(
                                    match = match,
                                    userId = lobbyUid,
                                    fromCache = false,
                                    matchmakingInProgress = inMatchmakingFlow,
                                    autoNavigationSuppressed =
                                        MatchSessionMonitor.isAutoGameNavigationSuppressed(match.id),
                                    resumingFromQueueOrJoin = resumingQueueOrJoin,
                                )
                            ) {
                                beginAutoGameNavigation(match.id)
                                return@collect
                            }
                            if (match.status == MatchStatus.LOBBY && match.isReadyDeadlineExpired()) {
                                viewModelScope.launch(Dispatchers.IO) {
                                    runCatching { matchRepository.confirmMatchReady(match.id) }
                                }
                            }
                            _uiState.update { it.copy(preGameSync = null, activeMatchId = null) }
                            return@collect
                        }
                        awaitingMatchFromQueue = false
                        awaitingMatchStartedAtMs = null
                        stopQueueTimer()
                        val myReady = match.isPlayerReady(uid)
                        val opponentReady = match.isOpponentReady(uid)
                        val preGameSync = PreGameSyncUiState(
                            matchId = match.id,
                            myDisplayName = match.myName(uid),
                            opponentUid = match.opponentId(uid),
                            opponentDisplayName = match.opponentName(uid),
                            myReady = myReady,
                            opponentReady = opponentReady,
                            readyDeadlineAtMs = match.effectiveReadyDeadlineAtMs(),
                        )
                        _uiState.update {
                            it.copy(
                                isJoiningQueue = false,
                                isInQueue = false,
                                queueElapsedSeconds = null,
                                matchmakingError = null,
                                activeMatchId = null,
                                preGameSync = preGameSync,
                            )
                        }
                        appContext?.let { triggerMatchFoundFeedback(it, match.id) }
                        playPreGameReadyFeedbackIfNeeded(preGameSync)
                        ensurePreGameReadyLoop(match.id)
                    }
                    MatchStatus.ACTIVE -> {
                        val uid = authRepository.currentUserId
                        val resumingQueueOrJoin = _uiState.value.isInQueue ||
                            _uiState.value.isJoiningQueue ||
                            MatchSessionMonitor.hasQueueEntry.value
                        val shouldAutoNavigate = uid != null &&
                            shouldAutoNavigateToLiveMatch(
                                match = match,
                                userId = uid,
                                fromCache = false,
                                matchmakingInProgress = inMatchmakingFlow,
                                autoNavigationSuppressed =
                                    MatchSessionMonitor.isAutoGameNavigationSuppressed(match.id),
                                resumingFromQueueOrJoin = resumingQueueOrJoin,
                            )
                        if (shouldAutoNavigate) {
                            beginAutoGameNavigation(match.id)
                            return@collect
                        }
                        stopPreGameReadyLoop()
                        _uiState.update {
                            it.copy(preGameSync = null, activeMatchId = match.id)
                        }
                    }
                    MatchStatus.ABANDONED -> {
                        if (_uiState.value.preGameSync?.matchId == match.id) {
                            failPreGameSync(PRE_GAME_READY_TIMEOUT_MESSAGE)
                        } else {
                            stopPreGameReadyLoop()
                            _uiState.update { it.copy(preGameSync = null, activeMatchId = null) }
                        }
                    }
                    else -> {
                        stopPreGameReadyLoop()
                        _uiState.update { it.copy(preGameSync = null, activeMatchId = null) }
                    }
                }
            }
        }
    }


    private fun ensurePreGameReadyLoop(matchId: String) {
        if (preGameReadyJob?.isActive == true && preGameReadyMatchId == matchId) return
        stopPreGameReadyLoop()
        preGameReadyMatchId = matchId
        MatchSessionMonitor.setMatchmakingInProgress(true)
        preGameReadyJob = viewModelScope.launch {
            while (isActive) {
                val uid = authRepository.currentUserId ?: break
                val sync = _uiState.value.preGameSync
                if (sync == null || sync.matchId != matchId) break

                if (sync.isReadyDeadlineExpired()) {
                    runCatching { matchRepository.confirmMatchReady(matchId) }
                    val afterTimeout = runCatching {
                        matchRepository.getMatchFromServer(matchId)
                    }.getOrNull()
                    if (afterTimeout == null ||
                        afterTimeout.status == MatchStatus.ABANDONED ||
                        afterTimeout.isReadyDeadlineExpired()
                    ) {
                        failPreGameSync(PRE_GAME_READY_TIMEOUT_MESSAGE)
                        break
                    }
                }

                runCatching { matchRepository.confirmMatchReady(matchId) }
                    .onFailure {
                        _uiState.update {
                            it.copy(matchmakingError = "Could not confirm ready. Retrying…")
                        }
                    }

                val serverMatch = runCatching {
                    matchRepository.getMatchFromServer(matchId)
                }.getOrNull()

                if (serverMatch != null && serverMatch.isParticipant(uid)) {
                    MatchSessionMonitor.ingestAuthoritativeMatch(serverMatch)
                    if (serverMatch.status == MatchStatus.ABANDONED) {
                        failPreGameSync(PRE_GAME_READY_TIMEOUT_MESSAGE)
                        break
                    }
                    val updatedSync = PreGameSyncUiState(
                        matchId = matchId,
                        myDisplayName = serverMatch.myName(uid),
                        opponentUid = serverMatch.opponentId(uid),
                        opponentDisplayName = serverMatch.opponentName(uid),
                        myReady = serverMatch.isPlayerReady(uid),
                        opponentReady = serverMatch.isOpponentReady(uid),
                        readyDeadlineAtMs = serverMatch.effectiveReadyDeadlineAtMs(),
                    )
                    _uiState.update { state ->
                        state.copy(
                            preGameSync = updatedSync,
                            matchmakingError = if (
                                state.matchmakingError?.startsWith("Could not confirm ready") == true
                            ) {
                                null
                            } else {
                                state.matchmakingError
                            },
                        )
                    }
                    playPreGameReadyFeedbackIfNeeded(updatedSync)
                    if (serverMatch.status == MatchStatus.ACTIVE) {
                        beginAutoGameNavigation(matchId)
                        break
                    }
                    if (serverMatch.isReadyDeadlineExpired() && !serverMatch.isOpponentReady(uid)) {
                        runCatching { matchRepository.confirmMatchReady(matchId) }
                        val after = runCatching {
                            matchRepository.getMatchFromServer(matchId)
                        }.getOrNull()
                        if (after == null || after.status == MatchStatus.ABANDONED) {
                            failPreGameSync(PRE_GAME_READY_TIMEOUT_MESSAGE)
                            break
                        }
                    }
                }

                delay(1_000)
            }
        }
    }

    private fun PreGameSyncUiState.isReadyDeadlineExpired(): Boolean {
        if (readyDeadlineAtMs <= 0L) return false
        return System.currentTimeMillis() > readyDeadlineAtMs
    }

    private fun failPreGameSync(message: String) {
        val matchId = _uiState.value.preGameSync?.matchId
        stopPreGameReadyLoop()
        MatchSessionMonitor.setMatchmakingInProgress(false)
        awaitingMatchFromQueue = false
        awaitingMatchStartedAtMs = null
        _uiState.update {
            it.copy(
                preGameSync = null,
                activeMatchId = null,
                isJoiningQueue = false,
                isInQueue = false,
                queueElapsedSeconds = null,
                matchmakingError = message,
            )
        }
        matchId?.let { abandonedMatchId ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { matchRepository.confirmMatchReady(abandonedMatchId) }
                runCatching { MatchSessionMonitor.refreshOnResume(forceServerSync = true) }
            }
        }
    }

    private fun beginAutoGameNavigation(matchId: String) {
        MatchSessionMonitor.clearAutoGameNavigationSuppression(matchId)
        if (MatchSessionMonitor.isAutoGameNavigationSuppressed(matchId)) return
        stopPreGameReadyLoop()
        awaitingMatchFromQueue = false
        awaitingMatchStartedAtMs = null
        stopQueueTimer()
        MatchSessionMonitor.setMatchmakingInProgress(true)
        _uiState.update {
            it.copy(
                isJoiningQueue = false,
                isInQueue = false,
                queueElapsedSeconds = null,
                matchmakingError = null,
                preGameSync = null,
                activeMatchId = null,
            )
        }
        gameNavigationVerifyJob?.cancel()
        gameNavigationVerifyJob = viewModelScope.launch(Dispatchers.IO) {
            confirmActiveMatchOnServer(matchId)
        }
    }

    /**
     * Only queue game navigation after the server reports a live ACTIVE match.
     * Cached listener snapshots can briefly show ACTIVE for an old or non-existent match.
     */
    private suspend fun confirmActiveMatchOnServer(matchId: String) {
        if (MatchSessionMonitor.isAutoGameNavigationSuppressed(matchId)) return
        val uid = authRepository.currentUserId ?: run {
            abortFalseMatchFoundAssignment()
            return
        }
        val serverMatch = matchRepository.getMatchFromServer(matchId)
        if (serverMatch == null || !serverMatch.isParticipant(uid)) {
            abortFalseMatchFoundAssignment()
            return
        }
        MatchSessionMonitor.ingestAuthoritativeMatch(serverMatch)
        when (serverMatch.status) {
            MatchStatus.ACTIVE, MatchStatus.LOBBY ->
                if (serverMatch.isLiveForReconnect()) {
                    MatchSessionMonitor.requestGameNavigation(matchId)
                } else {
                    abortFalseMatchFoundAssignment()
                }
            else -> abortFalseMatchFoundAssignment()
        }
    }

    private fun abortFalseMatchFoundAssignment() {
        gameNavigationVerifyJob?.cancel()
        gameNavigationVerifyJob = null
        MatchSessionMonitor.consumeGameNavigation()
        val joinedAtMs = MatchSessionMonitor.queueJoinedAtMs.value
        if (
            MatchSessionMonitor.isMatchmakingInProgress() &&
            (MatchSessionMonitor.hasQueueEntry.value || joinedAtMs != null)
        ) {
            joinedAtMs?.let { enterConfirmedQueue(it) }
                ?: _uiState.update {
                    it.copy(
                        isJoiningQueue = true,
                        isInQueue = false,
                        queueElapsedSeconds = null,
                        matchmakingError = null,
                    )
                }
            return
        }
        MatchSessionMonitor.setMatchmakingInProgress(false)
        _uiState.update {
            it.copy(
                preGameSync = null,
                activeMatchId = null,
                isJoiningQueue = false,
                isInQueue = false,
                queueElapsedSeconds = null,
            )
        }
    }

    private fun stopPreGameReadyLoop() {
        preGameReadyJob?.cancel()
        preGameReadyJob = null
        preGameReadyMatchId = null
        clearPreGameReadyFeedbackTracking()
    }

    private fun clearPreGameReadyFeedbackTracking() {
        preGameReadyFeedbackMatchId = null
        lastPreGameMyReady = false
        lastPreGameOpponentReady = false
    }

    private fun playPreGameReadyFeedbackIfNeeded(sync: PreGameSyncUiState) {
        val context = appContext ?: return
        if (sync.matchId != preGameReadyFeedbackMatchId) {
            preGameReadyFeedbackMatchId = sync.matchId
            lastPreGameMyReady = false
            lastPreGameOpponentReady = false
        }
        val myBecameReady = !lastPreGameMyReady && sync.myReady
        val opponentBecameReady = !lastPreGameOpponentReady && sync.opponentReady
        if (!myBecameReady && !opponentBecameReady) {
            lastPreGameMyReady = sync.myReady
            lastPreGameOpponentReady = sync.opponentReady
            return
        }
        lastPreGameMyReady = sync.myReady
        lastPreGameOpponentReady = sync.opponentReady
        val mode = SoundPreferences(context).getMode()
        viewModelScope.launch {
            val tickPlayer = ClockTickPlayer(context)
            try {
                if (myBecameReady) {
                    playReadyFeedback(context, tickPlayer, mode)
                }
                if (opponentBecameReady) {
                    if (myBecameReady) delay(PRE_GAME_READY_TICK_GAP_MS)
                    playReadyFeedback(context, tickPlayer, mode)
                }
            } finally {
                tickPlayer.release()
            }
        }
    }

    fun dismissHighlightedMatch() {
        HighlightedMatchSession.dismiss()
        _uiState.update { it.copy(isHighlightedMatchDismissed = true) }
    }

    fun onHomeVisible(context: Context) {
        appContext = context.applicationContext
        loadMatchModePreferences(context)
        if (HighlightedMatchSession.dismissed) {
            _uiState.update { it.copy(isHighlightedMatchDismissed = true) }
            return
        }
        authRepository.currentUserId?.let { uid ->
            _uiState.value.profile?.elo?.let { elo ->
                loadHighlightedMatch(uid, elo)
            }
        }
    }

    private fun loadHighlightedMatch(userId: String, currentElo: Int) {
        if (HighlightedMatchSession.dismissed) return
        highlightedMatchJob?.cancel()
        highlightedMatchJob = viewModelScope.launch {
            if (HighlightedMatchSession.dismissed) return@launch
            try {
                val windowStartMs = weeklyChartWindowStartMs()
                val cacheContext = appContext
                if (cacheContext != null) {
                    val cached = HighlightedMatchCache(cacheContext).read(userId, windowStartMs)
                    if (cached != null) {
                        if (authRepository.currentUserId == userId && !HighlightedMatchSession.dismissed) {
                            _uiState.update { it.copy(highlightedMatch = cached.entry) }
                        }
                        return@launch
                    }
                }
                val matchId = HighlightedMatchFunctions.getHighlightedMatchId(windowStartMs)
                val highlighted = if (matchId != null) {
                    val match = matchRepository.getMatch(matchId)
                        ?: matchRepository.getMatchFromServer(matchId)
                    if (match != null) {
                        enrichMatchHistoryWithOpponentElos(
                            viewerId = userId,
                            myCurrentElo = currentElo,
                            matches = listOf(match),
                        ).firstOrNull()
                    } else {
                        null
                    }
                } else {
                    null
                }
                cacheContext?.let { context ->
                    HighlightedMatchCache(context).write(userId, windowStartMs, highlighted)
                }
                if (authRepository.currentUserId == userId && !HighlightedMatchSession.dismissed) {
                    _uiState.update { it.copy(highlightedMatch = highlighted) }
                }
            } catch (_: Exception) {
                // Keep the previous highlight or hide the section on failure.
            }
        }
    }

    fun reconcileQueueOnResume(context: Context) {
        appContext = context.applicationContext
        loadMatchModePreferences(context)
        _uiState.value.preGameSync?.matchId?.let { ensurePreGameReadyLoop(it) }
        viewModelScope.launch {
            if (
                shouldClearStaleQueueUiOnResume(
                    monitorMatchmaking = MatchSessionMonitor.isMatchmakingInProgress(),
                    hasQueueEntry = MatchSessionMonitor.hasQueueEntry.value,
                    queueJoinedAtMs = MatchSessionMonitor.queueJoinedAtMs.value,
                    queueAnchorMs = MatchSessionMonitor.queueElapsedAnchorMs(),
                    isInQueue = _uiState.value.isInQueue,
                    isJoiningQueue = _uiState.value.isJoiningQueue,
                )
            ) {
                clearQueueUiState()
                return@launch
            }
            val reconcilingQueue = shouldReconcileQueueSessionOnResume(
                isInQueue = _uiState.value.isInQueue,
                isJoiningQueue = _uiState.value.isJoiningQueue,
                monitorMatchmaking = MatchSessionMonitor.isMatchmakingInProgress(),
                hasQueueEntry = MatchSessionMonitor.hasQueueEntry.value,
                queueJoinedAtMs = MatchSessionMonitor.queueJoinedAtMs.value,
                queueAnchorMs = MatchSessionMonitor.queueElapsedAnchorMs(),
            )
            if (reconcilingQueue) {
                MatchSessionMonitor.setMatchmakingInProgress(true)
            }
            runCatching {
                val forceServerSync = reconcilingQueue && !MatchSessionMonitor.hasQueueEntry.value
                MatchSessionMonitor.refreshOnResume(forceServerSync = forceServerSync)
            }
            val uid = authRepository.currentUserId ?: return@launch
            if (_uiState.value.profile == null) {
                userRepository.getUserProfile(uid)?.let { profile ->
                    _uiState.update { it.copy(profile = profile) }
                    if (!HighlightedMatchSession.dismissed) {
                        loadHighlightedMatch(uid, profile.elo)
                    }
                }
            }

            val serverJoinedAtMs = MatchSessionMonitor.queueJoinedAtMs.value
                ?.takeIf { MatchSessionMonitor.hasQueueEntry.value }

            if (serverJoinedAtMs != null) {
                MatchSessionMonitor.setMatchmakingInProgress(true)
                enterConfirmedQueue(serverJoinedAtMs)
                return@launch
            }

            if (reconcilingQueue) {
                MatchSessionMonitor.setMatchmakingInProgress(true)
                if (_uiState.value.isJoiningQueue && matchmakingJob?.isActive != true) {
                    failMatchmaking(
                        generation = matchmakingGeneration,
                        message = "Matchmaking was interrupted. Tap Find Match to try again.",
                    )
                } else if (
                    !_uiState.value.isJoiningQueue &&
                    !MatchSessionMonitor.hasQueueEntry.value
                ) {
                    MatchSessionMonitor.requestQueueRecovery()
                }
                return@launch
            }

            if (matchmakingJob?.isActive == true) {
                return@launch
            }

            val appearsQueuedLocally = _uiState.value.isJoiningQueue ||
                _uiState.value.isInQueue ||
                MatchSessionMonitor.isMatchmakingInProgress()

            if (!appearsQueuedLocally || MatchSessionMonitor.hasQueueEntry.value) {
                return@launch
            }

            matchmakingGeneration++
            matchmakingJob?.cancel()
            matchmakingJob = null
            authRepository.currentUserId?.let { matchRepository.leaveQueueBestEffort(it) }
            clearQueueUiState()
        }
        refresh()
    }

    private fun clearQueueUiState() {
        NetworkDataActivityTracker.endQueueJoinBurstSuppression()
        awaitingMatchFromQueue = false
        awaitingMatchStartedAtMs = null
        stopQueueTimer()
        MatchSessionMonitor.setMatchmakingInProgress(false)
        MatchSessionMonitor.clearQueueState()
        _uiState.update {
            it.copy(
                isJoiningQueue = false,
                isInQueue = false,
                queueElapsedSeconds = null,
                matchmakingError = null,
            )
        }
    }

    private fun resetQueueUiAfterMatchFinished() {
        awaitingMatchFromQueue = false
        awaitingMatchStartedAtMs = null
        stopQueueTimer()
        gameNavigationVerifyJob?.cancel()
        gameNavigationVerifyJob = null
        MatchSessionMonitor.setMatchmakingInProgress(false)
        MatchSessionMonitor.clearQueueState()
        _uiState.update {
            it.copy(
                isJoiningQueue = false,
                isInQueue = false,
                queueElapsedSeconds = null,
                preGameSync = null,
                activeMatchId = null,
            )
        }
        appContext?.let { MatchmakingBackgroundCoordinator.sync(it) }
    }

    fun loadMatchModePreferences(context: Context) {
        val modes = MatchModePreferences(context).get()
        _uiState.update { it.copy(selectedMatchModes = modes) }
    }

    fun toggleMatchMode(context: Context, mode: MatchMode) {
        if (_uiState.value.isJoiningQueue || _uiState.value.isInQueue) return
        val current = _uiState.value.selectedMatchModes
        val updated = MatchMode.toggleInSelection(current, mode)
        MatchModePreferences(context).set(updated)
        _uiState.update { it.copy(selectedMatchModes = updated) }
    }

    fun refresh() {
        if (_uiState.value.isSigningOut) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val user = authRepository.currentUser ?: return@launch
            refresh(user)
        }
    }

    private suspend fun refresh(user: FirebaseUser) {
        if (_uiState.value.isSigningOut || authRepository.currentUser == null) return
        val hadProfile = _uiState.value.profile != null
        if (!hadProfile) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }
        try {
            withTimeout(10_000) {
                if (MatchSessionMonitor.isMatchmakingInProgress()) {
                    authRepository.waitUntilQueueReadyProfile(user.uid, timeoutMs = 8_000)
                } else if (
                    !authRepository.waitUntilQueueReadyProfile(user.uid, timeoutMs = 4_000)
                ) {
                    authRepository.ensureUserProfile(
                        uid = user.uid,
                        displayName = user.displayName,
                        photoUrl = user.photoUrl?.toString(),
                    )
                }
            }
            _uiState.update { it.copy(isLoading = false, error = null) }
        } catch (e: TimeoutCancellationException) {
            if (_uiState.value.isSigningOut || authRepository.currentUser == null) return
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = if (it.profile == null) {
                        "No internet connection. Connect to sync your profile."
                    } else {
                        null
                    },
                )
            }
        } catch (e: Exception) {
            if (_uiState.value.isSigningOut || authRepository.currentUser == null) return
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = e.message ?: if (!hadProfile) "Could not load profile." else null,
                )
            }
        }
    }

    fun signOut(context: Context) {
        val uid = authRepository.currentUserId
        HighlightedMatchSession.clear()
        appContext?.let { HighlightedMatchCache(it).clear() }
        refreshJob?.cancel()
        resetMatchmakingLocalState()
        MatchSessionMonitor.consumeGameNavigation()
        MatchSessionMonitor.setMatchmakingInProgress(false)
        _uiState.update {
            it.copy(isSigningOut = true, error = null, matchmakingError = null)
        }
        viewModelScope.launch {
            if (uid != null) {
                withTimeoutOrNull(5_000) {
                    runCatching { matchRepository.leaveQueueForUser(uid) }
                }
                runCatching { presenceRepository.clearPresence(uid) }
            }
            authRepository.signOut(context)
        }
    }

    private fun resetMatchmakingLocalState() {
        matchmakingJob?.cancel()
        matchmakingJob = null
        matchmakingGeneration++
        cleanupMatchmakingSession()
    }

    override fun onCleared() {
        profileJob?.cancel()
        refreshJob?.cancel()
        matchmakingJob?.cancel()
        leaveQueueJob?.cancel()
        activeMatchJob?.cancel()
        queueObserveJob?.cancel()
        highlightedMatchJob?.cancel()
        stopQueueTimer()
        super.onCleared()
    }

    private suspend fun isFirebaseAvailableForQueueAction(action: String, generation: Int): Boolean {
        if (authRepository.isFirebaseAvailable()) return true
        failMatchmaking(
            generation = generation,
            message = connectivityFailureUserMessage(),
        )
        return false
    }

    private suspend fun joinQueueWithQuotaRetry(
        matchModes: Set<MatchMode>,
        profile: UserProfile,
    ): MatchRepository.JoinQueueResult {
        var lastError: Exception? = null
        repeat(JOIN_QUEUE_QUOTA_RETRY_ATTEMPTS) { attempt ->
            try {
                return matchRepository.joinQueue(matchModes, profile)
            } catch (e: Exception) {
                lastError = e
                if (!isQuotaExceededError(e) || attempt >= JOIN_QUEUE_QUOTA_RETRY_ATTEMPTS - 1) {
                    throw e
                }
                delay(JOIN_QUEUE_QUOTA_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("Could not join matchmaking queue")
    }
}
