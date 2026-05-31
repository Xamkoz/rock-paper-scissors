package com.rpsonline.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.model.UserProfile
import com.rpsonline.app.data.preferences.MatchModePreferences
import com.rpsonline.app.data.repository.AuthRepository
import com.rpsonline.app.data.repository.MatchRepository
import com.rpsonline.app.data.repository.MatchSessionMonitor
import com.rpsonline.app.data.repository.MatchmakingFunctions
import com.rpsonline.app.data.repository.PresenceRepository
import com.rpsonline.app.data.repository.UserProfileSync
import com.rpsonline.app.data.repository.UserRepository
import com.rpsonline.app.domain.MatchMode
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
    val queueElapsedSeconds: Long = 0,
    val matchmakingError: String? = null,
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

    companion object {
        private const val MATCH_ASSIGNMENT_GRACE_MS = 30_000L
        private const val MATCHMAKING_WATCHDOG_MS = 45_000L
        private const val JOIN_QUEUE_WATCHDOG_MS = 15_000L
        private const val JOIN_QUEUE_TIMEOUT_MS = 25_000L
        private const val PROFILE_READY_TIMEOUT_MS = 6_000L
        private const val PRE_GAME_READY_TIMEOUT_MESSAGE =
            "Opponent did not ready in time. Tap Find Match to try again."
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
                    stopQueueTimer()
                    awaitingMatchFromQueue = false
                    MatchSessionMonitor.consumeGameNavigation()
                    MatchSessionMonitor.setMatchmakingInProgress(false)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            profile = null,
                            activeMatchId = null,
                            isJoiningQueue = false,
                            isInQueue = false,
                            queueElapsedSeconds = 0,
                            matchmakingError = null,
                            isSigningOut = false,
                            error = null,
                        )
                    }
                } else {
                    viewModelScope.launch {
                        runCatching {
                            presenceRepository.touchPresence(
                                user.uid,
                                forceAuthRefresh = true,
                                awaitServerAck = true,
                                includeOnlineCount = true,
                            )
                        }
                    }
                    if (_uiState.value.profile == null) {
                        _uiState.update {
                            it.copy(profile = authRepository.fallbackProfile(user), isLoading = false)
                        }
                    }
                    profileJob = viewModelScope.launch {
                        userRepository.observeUserProfile(user.uid).collect { profile ->
                            if (profile != null) {
                                _uiState.update { it.copy(profile = profile, isLoading = false, error = null) }
                            }
                        }
                    }
                    refresh(user)
                    observeActiveMatch()
                    observeQueue()
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
        MatchSessionMonitor.setMatchmakingInProgress(true)
        queuedMatchModes = matchModes
        awaitingMatchFromQueue = true
        awaitingMatchStartedAtMs = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                isJoiningQueue = true,
                isInQueue = false,
                queueElapsedSeconds = 0,
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
            try {
                withTimeout(JOIN_QUEUE_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        if (!isFirebaseAvailableForQueueAction("join the queue", generation)) return@withContext
                        val profile = awaitUserProfileReady()
                        if (generation != matchmakingGeneration) return@withContext
                        val joinResult = matchRepository.joinQueue(matchModes, profile)
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
                                    queueElapsedSeconds = 0,
                                    matchmakingError = null,
                                )
                            }
                            return@withContext
                        }
                        val joinedAtMs = joinResult.clientJoinedAtMs ?: System.currentTimeMillis()
                        enterConfirmedQueue(joinedAtMs)
                    }
                }
                if (generation == matchmakingGeneration && _uiState.value.isInQueue) {
                    launch {
                        val serverJoinedAtMs = matchRepository.awaitQueueJoinedAtFromServer(timeoutMs = 30_000)
                        if (generation != matchmakingGeneration) return@launch
                        if (serverJoinedAtMs != null && _uiState.value.isInQueue) {
                            enterConfirmedQueue(serverJoinedAtMs)
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
                    !_uiState.value.isInQueue
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
                queueElapsedSeconds = 0,
                matchmakingError = message,
            )
        }
    }

    private fun cleanupMatchmakingSession() {
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
            MatchSessionMonitor.queueJoinedAtMs.collect { joinedAtMs ->
                if (joinedAtMs == null) {
                    val joinInFlight = _uiState.value.isJoiningQueue ||
                        MatchSessionMonitor.isQueueEntryPending()
                    if (joinInFlight && MatchSessionMonitor.isMatchmakingInProgress()) {
                        return@collect
                    }
                    if (_uiState.value.isInQueue && MatchSessionMonitor.isMatchmakingInProgress()) {
                        viewModelScope.launch {
                            val serverJoinedAtMs = runCatching {
                                matchRepository.getQueueJoinedAtMs()
                            }.getOrNull()
                            if (serverJoinedAtMs != null) {
                                enterConfirmedQueue(serverJoinedAtMs)
                                return@launch
                            }
                            if (!_uiState.value.isInQueue || !MatchSessionMonitor.isMatchmakingInProgress()) {
                                return@launch
                            }
                            if (matchmakingJob?.isActive == true) {
                                return@launch
                            }
                            val profile = _uiState.value.profile ?: return@launch
                            val modes = queuedMatchModes ?: _uiState.value.selectedMatchModes
                            if (modes.isEmpty()) return@launch
                            runCatching {
                                matchRepository.joinQueue(modes, profile)
                            }.onSuccess { result ->
                                if (result.immediateMatchId != null) {
                                    _uiState.update {
                                        it.copy(
                                            isJoiningQueue = false,
                                            isInQueue = false,
                                            queueElapsedSeconds = 0,
                                        )
                                    }
                                    return@onSuccess
                                }
                                enterConfirmedQueue(
                                    result.clientJoinedAtMs ?: System.currentTimeMillis(),
                                )
                            }.onFailure {
                                failMatchmaking(
                                    generation = matchmakingGeneration,
                                    message = "Lost connection to the matchmaking queue. Tap Find Match to try again.",
                                )
                            }
                        }
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
                            queueElapsedSeconds = 0,
                        )
                    }
                } else {
                    syncConfirmedQueueUi()
                }
            }
        }
    }

    private fun enterConfirmedQueue(joinedAtMs: Long) {
        MatchSessionMonitor.confirmQueueJoinedAt(joinedAtMs)
        syncConfirmedQueueUi()
    }

    private fun syncConfirmedQueueUi() {
        val joinedAtMs = MatchSessionMonitor.queueJoinedAtMs.value ?: return
        val elapsed = ((System.currentTimeMillis() - joinedAtMs) / 1_000).coerceAtLeast(0)
        awaitingMatchFromQueue = true
        awaitingMatchStartedAtMs = System.currentTimeMillis()
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

    /** Single ticker; always reads [MatchSessionMonitor.queueJoinedAtMs] so resume cannot spawn competing timers. */
    private fun ensureQueueElapsedTicker() {
        if (queueTimerJob?.isActive == true) return
        queueTimerJob = viewModelScope.launch {
            while (isActive) {
                val joinedAtMs = MatchSessionMonitor.queueJoinedAtMs.value ?: break
                val elapsed = ((System.currentTimeMillis() - joinedAtMs) / 1_000).coerceAtLeast(0)
                _uiState.update { it.copy(queueElapsedSeconds = elapsed) }
                delay(1_000)
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
                if (match == null || uid == null || !match.isParticipant(uid)) {
                    stopPreGameReadyLoop()
                    _uiState.update { it.copy(preGameSync = null, activeMatchId = null) }
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
                        _uiState.update {
                            it.copy(
                                isJoiningQueue = false,
                                isInQueue = false,
                                queueElapsedSeconds = 0,
                                matchmakingError = null,
                                activeMatchId = null,
                                preGameSync = PreGameSyncUiState(
                                    matchId = match.id,
                                    myDisplayName = match.myName(uid),
                                    opponentUid = match.opponentId(uid),
                                    opponentDisplayName = match.opponentName(uid),
                                    myReady = myReady,
                                    opponentReady = opponentReady,
                                    readyDeadlineAtMs = match.effectiveReadyDeadlineAtMs(),
                                ),
                            )
                        }
                        ensurePreGameReadyLoop(match.id)
                    }
                    MatchStatus.ACTIVE -> {
                        val shouldAutoNavigate = !MatchSessionMonitor.isAutoGameNavigationSuppressed(match.id) &&
                            (inMatchmakingFlow || _uiState.value.preGameSync?.matchId == match.id)
                        if (shouldAutoNavigate) {
                            beginAutoGameNavigation(match.id)
                            viewModelScope.launch {
                                navigateToActiveMatchWhenServerReady(match.id)
                            }
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
                    _uiState.update { state ->
                        state.copy(
                            preGameSync = PreGameSyncUiState(
                                matchId = matchId,
                                myDisplayName = serverMatch.myName(uid),
                                opponentUid = serverMatch.opponentId(uid),
                                opponentDisplayName = serverMatch.opponentName(uid),
                                myReady = serverMatch.isPlayerReady(uid),
                                opponentReady = serverMatch.isOpponentReady(uid),
                                readyDeadlineAtMs = serverMatch.effectiveReadyDeadlineAtMs(),
                            ),
                            matchmakingError = if (
                                state.matchmakingError?.startsWith("Could not confirm ready") == true
                            ) {
                                null
                            } else {
                                state.matchmakingError
                            },
                        )
                    }
                    if (serverMatch.status == MatchStatus.ACTIVE) {
                        beginAutoGameNavigation(matchId)
                        navigateToActiveMatchWhenServerReady(matchId)
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
                queueElapsedSeconds = 0,
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
        if (MatchSessionMonitor.isAutoGameNavigationSuppressed(matchId)) return
        stopPreGameReadyLoop()
        awaitingMatchFromQueue = false
        awaitingMatchStartedAtMs = null
        stopQueueTimer()
        MatchSessionMonitor.setMatchmakingInProgress(true)
        MatchSessionMonitor.requestGameNavigation(matchId)
        _uiState.update {
            it.copy(
                isJoiningQueue = false,
                isInQueue = false,
                queueElapsedSeconds = 0,
                matchmakingError = null,
                preGameSync = null,
                activeMatchId = null,
            )
        }
    }

    private suspend fun navigateToActiveMatchWhenServerReady(matchId: String) {
        if (MatchSessionMonitor.isAutoGameNavigationSuppressed(matchId)) return
        val serverMatch = matchRepository.getMatchFromServer(matchId) ?: return
        if (serverMatch.status != MatchStatus.ACTIVE) return
        MatchSessionMonitor.ingestAuthoritativeMatch(serverMatch)
    }

    private fun stopPreGameReadyLoop() {
        preGameReadyJob?.cancel()
        preGameReadyJob = null
        preGameReadyMatchId = null
    }

    fun onHomeVisible(context: Context) {
        loadMatchModePreferences(context)
    }

    fun reconcileQueueOnResume(context: Context) {
        loadMatchModePreferences(context)
        _uiState.value.preGameSync?.matchId?.let { ensurePreGameReadyLoop(it) }
        viewModelScope.launch {
            val reconcilingQueue = _uiState.value.isInQueue ||
                _uiState.value.isJoiningQueue ||
                MatchSessionMonitor.isMatchmakingInProgress()
            if (reconcilingQueue) {
                MatchSessionMonitor.setMatchmakingInProgress(true)
            }
            runCatching {
                MatchSessionMonitor.refreshOnResume(forceServerSync = reconcilingQueue)
            }
            val uid = authRepository.currentUserId ?: return@launch
            runCatching { presenceRepository.touchPresence(uid, awaitServerAck = true, includeOnlineCount = true) }
            userRepository.getUserProfile(uid)?.let { profile ->
                _uiState.update { it.copy(profile = profile) }
            }

            val serverJoinedAtMs = MatchSessionMonitor.queueJoinedAtMs.value
                ?.takeIf { MatchSessionMonitor.hasQueueEntry.value }
            val localJoinedAtMs = MatchSessionMonitor.queueJoinedAtMs.value

            if (serverJoinedAtMs != null) {
                MatchSessionMonitor.setMatchmakingInProgress(true)
                enterConfirmedQueue(serverJoinedAtMs)
                return@launch
            }

            val waitingForMatch = _uiState.value.isJoiningQueue ||
                _uiState.value.isInQueue ||
                MatchSessionMonitor.isMatchmakingInProgress() ||
                localJoinedAtMs != null

            if (waitingForMatch) {
                MatchSessionMonitor.setMatchmakingInProgress(true)
                MatchSessionMonitor.signalQueueDocLost()
                if (_uiState.value.isJoiningQueue && matchmakingJob?.isActive != true) {
                    failMatchmaking(
                        generation = matchmakingGeneration,
                        message = "Matchmaking was interrupted. Tap Find Match to try again.",
                    )
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
        awaitingMatchFromQueue = false
        awaitingMatchStartedAtMs = null
        stopQueueTimer()
        MatchSessionMonitor.setMatchmakingInProgress(false)
        MatchSessionMonitor.clearQueueState()
        _uiState.update {
            it.copy(
                isJoiningQueue = false,
                isInQueue = false,
                queueElapsedSeconds = 0,
                matchmakingError = null,
            )
        }
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
        stopQueueTimer()
        super.onCleared()
    }

    private suspend fun isFirebaseAvailableForQueueAction(action: String, generation: Int): Boolean {
        if (authRepository.isFirebaseAvailable()) return true
        failMatchmaking(
            generation = generation,
            message = "Cannot $action right now. Firebase is unavailable.",
        )
        return false
    }
}
