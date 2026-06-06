package com.rpsonline.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.model.Move
import com.rpsonline.app.data.model.RoundResult
import com.rpsonline.app.data.repository.AuthRepository
import com.rpsonline.app.data.repository.GameFunctions
import com.rpsonline.app.data.repository.MatchRepository
import com.rpsonline.app.data.repository.MatchSessionMonitor
import com.rpsonline.app.data.monitoring.NetworkConnectionStatus
import com.rpsonline.app.data.repository.RoundTimeoutRequestDeduper
import com.rpsonline.app.data.repository.isQuotaExceededError
import com.rpsonline.app.data.repository.quotaExceededUserMessage
import com.rpsonline.app.data.repository.userFacingFirebaseError
import com.rpsonline.app.domain.GameRules
import com.rpsonline.app.domain.LiveEloPreview
import com.rpsonline.app.domain.liveEloPreview
import com.rpsonline.app.domain.resultEloPreview
import com.rpsonline.app.ui.game.computeRoundSecondsFromAnchor
import com.rpsonline.app.ui.game.roundElapsedAtSyncMs
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GameTimerUiState(
    val countdownSeconds: Int? = null,
    val myClockSeconds: Int? = null,
    val opponentClockSeconds: Int? = null,
)

data class GameUiState(
    val match: Match? = null,
    val userId: String? = null,
    val hasSubmittedMove: Boolean = false,
    /** True once the server match doc records our submission for this round. */
    val serverMoveSubmitted: Boolean = false,
    val opponentHasSubmitted: Boolean = false,
    val isSubmitting: Boolean = false,
    val pendingMove: Move? = null,
    val error: String? = null,
    /** True when pre-game ready deadline passed on the game screen (navigate back to home). */
    val lobbyWaitFailed: Boolean = false,
    val isResolvingTimeout: Boolean = false,
    val lockedMove: Move? = null,
    val eloPreview: LiveEloPreview? = null,
)

class GameViewModel(
    private val matchId: String,
    private val matchRepository: MatchRepository = MatchRepository(),
    private val authRepository: AuthRepository = AuthRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(GameUiState(userId = authRepository.currentUserId))
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()
    private val _timerUiState = MutableStateFlow(GameTimerUiState())
    val timerUiState: StateFlow<GameTimerUiState> = _timerUiState.asStateFlow()

    private var observeJob: Job? = null
    private var countdownJob: Job? = null
    private var clockJob: Job? = null
    private var roundCountdownRoundKey: String? = null
    private var roundCountdownElapsedAtSyncMs: Long = 0L
    private var roundCountdownSyncRealtimeMs: Long = 0L
    private var clockSyncRealtimeMs: Long = 0L
    private var clockSyncMyBaseMs: Long = 0L
    private var clockSyncOppBaseMs: Long = 0L
    private var clockSyncMyRunning: Boolean = false
    private var clockSyncOppRunning: Boolean = false
    private var lastClockFingerprint: String? = null
    private var timeoutRequestedForRound: Int? = null
    private var lockedMoveRound: Int? = null
    private var locallySubmittedRound: Int? = null
    private var lastObservedOpenRound: Int? = null
    private var submitGeneration = 0
    private var submitWatchdogJob: Job? = null
    private var activeSubmitJob: Job? = null
    private var matchSnapshotAtTimeoutRequest: String? = null
    private var resolvingRetryJob: Job? = null
    private var stuckRoundNudgeJob: Job? = null
    private var completionPollJob: Job? = null
    private var lobbyReadyJob: Job? = null
    private var lastAppliedMatchFingerprint: String? = null
    private var clocksPausedForNetwork = false
    init {
        MatchSessionMonitor.ensureStarted()
        MatchSessionMonitor.activeMatch.value
            ?.takeIf { it.id == matchId }
            ?.let { applyMatchSnapshot(it) }

        observeJob = viewModelScope.launch {
            launch { bootstrapActiveMatch() }
            MatchSessionMonitor.activeMatch.collect { active ->
                val match = active?.takeIf { it.id == matchId } ?: return@collect
                applyMatchSnapshot(match)
            }
        }
    }

    private suspend fun bootstrapActiveMatch() {
        when (_uiState.value.match?.status) {
            MatchStatus.ACTIVE -> {
                viewModelScope.launch { runCatching { syncMatchFromServer() } }
                return
            }
            null -> syncMatchFromServer()
            else -> Unit
        }

        if (_uiState.value.match?.status == MatchStatus.LOBBY) {
            ensureLobbyReadyLoop()
            return
        }

        if (_uiState.value.match == null) {
            syncMatchFromServer()
        }
    }

    fun refreshOnResume() {
        recoverStuckSubmitUiIfNeeded()
        viewModelScope.launch {
            runCatching {
                MatchSessionMonitor.refreshOnResume(forceServerSync = true)
                syncMatchFromServer()
            }
        }
    }

    fun onNetworkStatusChanged(status: NetworkConnectionStatus) {
        val connected = status == NetworkConnectionStatus.Connected
        if (!connected) {
            if (clocksPausedForNetwork) return
            clocksPausedForNetwork = true
            clockJob?.cancel()
            countdownJob?.cancel()
            resolvingRetryJob?.cancel()
            resolvingRetryJob = null
            matchSnapshotAtTimeoutRequest = null
            recoverStuckSubmitUiIfNeeded()
            _uiState.update { state ->
                state.copy(
                    isResolvingTimeout = false,
                    isSubmitting = false,
                    error = state.error ?: CONNECTION_LOST_MESSAGE,
                )
            }
            return
        }
        if (!clocksPausedForNetwork) return
        clocksPausedForNetwork = false
        _uiState.update { state ->
            if (state.error == CONNECTION_LOST_MESSAGE) state.copy(error = null) else state
        }
        val match = _uiState.value.match
        val userId = authRepository.currentUserId
        if (match != null && userId != null) {
            syncMatchClocks(match, userId)
            syncCountdown(match)
        }
        refreshOnResume()
    }

    private suspend fun syncMatchFromServer() {
        val match = matchRepository.getMatchFromServer(matchId) ?: return
        applyMatchSnapshot(match, authoritative = true)
    }

    private fun ensureLobbyReadyLoop() {
        if (lobbyReadyJob?.isActive == true) return
        lobbyReadyJob = viewModelScope.launch {
            while (isActive) {
                val uid = authRepository.currentUserId ?: break
                val match = _uiState.value.match
                if (match?.status != MatchStatus.LOBBY) break

                if (match.isReadyDeadlineExpired()) {
                    runCatching { matchRepository.confirmMatchReady(matchId) }
                    val afterTimeout = runCatching {
                        matchRepository.getMatchFromServer(matchId)
                    }.getOrNull()
                    if (afterTimeout == null ||
                        afterTimeout.status == MatchStatus.ABANDONED ||
                        afterTimeout.isReadyDeadlineExpired()
                    ) {
                        failLobbyWait()
                        break
                    }
                }

                runCatching { matchRepository.confirmMatchReady(matchId) }

                val serverMatch = runCatching {
                    matchRepository.getMatchFromServer(matchId)
                }.getOrNull()

                if (serverMatch != null && serverMatch.isParticipant(uid)) {
                    applyMatchSnapshot(serverMatch, authoritative = true)
                    if (serverMatch.status == MatchStatus.ABANDONED) {
                        failLobbyWait()
                        break
                    }
                    if (serverMatch.status == MatchStatus.ACTIVE) break
                    if (serverMatch.isReadyDeadlineExpired() && !serverMatch.isOpponentReady(uid)) {
                        runCatching { matchRepository.confirmMatchReady(matchId) }
                        val after = runCatching {
                            matchRepository.getMatchFromServer(matchId)
                        }.getOrNull()
                        if (after == null || after.status == MatchStatus.ABANDONED) {
                            failLobbyWait()
                            break
                        }
                    }
                }

                delay(1_000)
            }
        }
    }

    private fun stopLobbyReadyLoop() {
        lobbyReadyJob?.cancel()
        lobbyReadyJob = null
    }

    private fun failLobbyWait() {
        stopLobbyReadyLoop()
        MatchSessionMonitor.setMatchmakingInProgress(false)
        _uiState.update {
            it.copy(
                lobbyWaitFailed = true,
                error = LOBBY_WAIT_TIMEOUT_MESSAGE,
            )
        }
        viewModelScope.launch {
            runCatching { matchRepository.confirmMatchReady(matchId) }
            runCatching { MatchSessionMonitor.refreshOnResume(forceServerSync = true) }
        }
    }

    private fun applyMatchSnapshot(match: Match?, authoritative: Boolean = false) {
        val wasLobby = _uiState.value.match?.status == MatchStatus.LOBBY
        if (match == null) {
            val current = _uiState.value.match
            if (current?.status == MatchStatus.COMPLETED || current?.status == MatchStatus.ABANDONED) {
                return
            }
        } else if (authoritative) {
            MatchSessionMonitor.ingestAuthoritativeMatch(match)
        }
        val userId = authRepository.currentUserId
        val openRound = match?.openRound()
        val openRoundNumber = openRound?.roundNumber
        val previousOpenRound = lastObservedOpenRound
        if (openRoundNumber != lastObservedOpenRound) {
            if (lastObservedOpenRound != null) {
                cancelInFlightSubmit(clearPendingUi = true)
                locallySubmittedRound = null
                lockedMoveRound = null
            }
            lastObservedOpenRound = openRoundNumber
        }

        val alreadySubmitted = when {
            match == null || userId == null || openRound == null -> false
            else -> openRound.hasSubmittedFor(userId, match.player1)
        }
        if (_uiState.value.error != null && !alreadySubmitted) {
            locallySubmittedRound = null
            lockedMoveRound = null
        }
        if (alreadySubmitted && openRoundNumber == locallySubmittedRound) {
            locallySubmittedRound = null
        }
        if (openRoundNumber == null) {
            locallySubmittedRound = null
            lockedMoveRound = null
        }
        val localSubmitPending = locallySubmittedRound != null &&
            match?.status == MatchStatus.ACTIVE &&
            openRoundNumber != null &&
            locallySubmittedRound == openRoundNumber
        val hasSubmittedMove = computeHasSubmittedMove(
            alreadySubmitted = alreadySubmitted,
            locallySubmittedRound = locallySubmittedRound,
            openRoundNumber = openRoundNumber,
            matchActive = match?.status == MatchStatus.ACTIVE,
        )
        val opponentHasSubmitted = when {
            match == null || userId == null || openRound == null -> false
            else -> openRound.opponentHasSubmittedFor(userId, match.player1)
        }

        val serverChoice = when {
            match == null || userId == null || openRound == null -> null
            userId == match.player1 -> openRound.player1Choice
            else -> openRound.player2Choice
        }
        val lockedMove = when {
            !hasSubmittedMove -> {
                lockedMoveRound = null
                null
            }
            alreadySubmitted && openRoundNumber != null && openRoundNumber != lockedMoveRound -> {
                lockedMoveRound = openRoundNumber
                Move.fromString(serverChoice) ?: _uiState.value.lockedMove ?: _uiState.value.pendingMove
            }
            alreadySubmitted -> Move.fromString(serverChoice) ?: _uiState.value.lockedMove ?: _uiState.value.pendingMove
            locallySubmittedRound == openRoundNumber ->
                _uiState.value.pendingMove ?: _uiState.value.lockedMove
            else -> null
        }

        val fingerprint = match?.let { matchFingerprint(it) }
        val timeoutFingerprint = match?.let { timeoutResolutionFingerprint(it) }
        val serverResponded = matchSnapshotAtTimeoutRequest != null &&
            timeoutFingerprint != null &&
            timeoutFingerprint != matchSnapshotAtTimeoutRequest
        val clearResolving = _uiState.value.isResolvingTimeout && serverResponded

        if (clearResolving) {
            matchSnapshotAtTimeoutRequest = null
            resolvingRetryJob?.cancel()
            resolvingRetryJob = null
        }

        val openRoundChanged = previousOpenRound != null && openRoundNumber != previousOpenRound
        val staleSubmitting = _uiState.value.isSubmitting && activeSubmitJob?.isActive != true
        val snapshotUnchanged = fingerprint != null && fingerprint == lastAppliedMatchFingerprint
        val terminalStatus = match?.status == MatchStatus.COMPLETED || match?.status == MatchStatus.ABANDONED
        if (snapshotUnchanged && !clearResolving && !openRoundChanged && !staleSubmitting && !terminalStatus) {
            return
        }
        if (fingerprint != null) {
            lastAppliedMatchFingerprint = fingerprint
        }
        val eloPreview = if (match != null && userId != null) {
            when (match.status) {
                MatchStatus.ACTIVE -> match.liveEloPreview(userId)
                MatchStatus.COMPLETED -> match.resultEloPreview(userId)
                else -> null
            }
        } else {
            null
        }

        _uiState.update {
            val canPickAgain = match?.status == MatchStatus.ACTIVE &&
                openRound != null &&
                !hasSubmittedMove &&
                !localSubmitPending &&
                !it.isResolvingTimeout
            val clearError = it.error != null &&
                (clearResolving || openRoundChanged || canPickAgain || alreadySubmitted)
            it.copy(
                match = match,
                userId = userId,
                hasSubmittedMove = hasSubmittedMove,
                serverMoveSubmitted = alreadySubmitted,
                opponentHasSubmitted = opponentHasSubmitted,
                lockedMove = lockedMove,
                // Server confirmed our choice — do not keep "Communicating to server" until write() returns.
                isSubmitting = when {
                    alreadySubmitted -> false
                    staleSubmitting -> false
                    else -> it.isSubmitting
                },
                pendingMove = when {
                    !hasSubmittedMove -> null
                    lockedMove != null -> null
                    else -> it.pendingMove
                },
                isResolvingTimeout = if (clearResolving) false else it.isResolvingTimeout,
                error = if (clearError) null else it.error,
                eloPreview = eloPreview,
            )
        }
        if (openRound?.roundNumber != timeoutRequestedForRound) {
            timeoutRequestedForRound = null
        }
        syncCountdown(match)
        syncMatchClocks(match, userId)
        scheduleStuckRoundNudge(match, userId)
        scheduleCompletionPoll(match)
        when (match?.status) {
            MatchStatus.LOBBY -> ensureLobbyReadyLoop()
            MatchStatus.ACTIVE -> stopLobbyReadyLoop()
            MatchStatus.ABANDONED -> {
                stopLobbyReadyLoop()
                if (wasLobby && !_uiState.value.lobbyWaitFailed) {
                    _uiState.update {
                        it.copy(
                            lobbyWaitFailed = true,
                            error = LOBBY_WAIT_TIMEOUT_MESSAGE,
                        )
                    }
                }
            }
            else -> stopLobbyReadyLoop()
        }
    }

    private fun cancelInFlightSubmit(clearPendingUi: Boolean = false) {
        submitGeneration++
        submitWatchdogJob?.cancel()
        submitWatchdogJob = null
        activeSubmitJob?.cancel()
        activeSubmitJob = null
        _uiState.update {
            it.copy(
                isSubmitting = false,
                pendingMove = if (clearPendingUi) null else it.pendingMove,
            )
        }
    }

    private fun isSubmitForOpenRound(roundNumber: Int): Boolean =
        _uiState.value.match?.openRound()?.roundNumber == roundNumber

    /** Poll when the match should finalize but the local snapshot is still ACTIVE. */
    private fun scheduleCompletionPoll(match: Match?) {
        completionPollJob?.cancel()
        if (match == null || match.status != MatchStatus.ACTIVE) return
        val winsToFinish = match.matchMode.winsToFinish
        val needsPoll = match.openRound() == null ||
            match.player1Wins >= winsToFinish ||
            match.player2Wins >= winsToFinish
        if (!needsPoll) return

        completionPollJob = viewModelScope.launch {
            repeat(COMPLETION_POLL_ATTEMPTS) {
                delay(COMPLETION_POLL_INTERVAL_MS)
                val current = _uiState.value.match ?: return@launch
                if (current.status != MatchStatus.ACTIVE) return@launch
                runCatching { syncMatchFromServer() }
                if (_uiState.value.match?.status != MatchStatus.ACTIVE) return@launch
            }
        }
    }

    /** When both players submitted but the server did not resolve, nudge timeout/recovery. */
    private fun scheduleStuckRoundNudge(match: Match?, userId: String?) {
        stuckRoundNudgeJob?.cancel()
        val openRound = match?.openRound() ?: return
        if (match.status != MatchStatus.ACTIVE || userId == null) return
        if (!openRound.hasSubmittedFor(userId, match.player1)) return
        if (!openRound.opponentHasSubmittedFor(userId, match.player1)) return

        val roundNumber = openRound.roundNumber
        stuckRoundNudgeJob = viewModelScope.launch {
            delay(2_000)
            val state = _uiState.value
            val currentOpen = state.match?.openRound() ?: return@launch
            if (currentOpen.roundNumber != roundNumber) return@launch
            if (!state.hasSubmittedMove || !state.opponentHasSubmitted) return@launch
            runCatching { syncMatchFromServer() }
            val afterSync = _uiState.value
            val stillOpen = afterSync.match?.openRound() ?: return@launch
            if (stillOpen.roundNumber != roundNumber) return@launch
            if (!afterSync.hasSubmittedMove || !afterSync.opponentHasSubmitted) return@launch
            requestTimeoutResolution(requireNotNull(afterSync.match), roundNumber)
        }
    }

    private fun matchFingerprint(match: Match): String {
        val open = match.openRound()
        return buildString {
            append(match.status)
            append('|')
            append(match.lastActivityAt)
            append('|')
            append(match.rounds.size)
            append('|')
            append(match.player1Wins)
            append('|')
            append(match.player2Wins)
            append('|')
            append(open?.roundNumber)
            append('|')
            append(open?.deadline)
            append('|')
            append(open?.player1Submitted)
            append('|')
            append(open?.player2Submitted)
            append('|')
            append(open?.resolvedAt)
            append('|')
            append(match.player1ClockMs)
            append('|')
            append(match.player2ClockMs)
            append('|')
            append(match.clocksUpdatedAt)
        }
    }

    private fun resyncClockAnchor(
        match: Match,
        userId: String,
        myRunning: Boolean,
        oppRunning: Boolean,
    ) {
        clockSyncRealtimeMs = SystemClock.elapsedRealtime()
        val timer = _timerUiState.value
        val (myBaseMs, myRunningSync) = reconcileClockBaseMs(
            running = myRunning,
            displayedSeconds = timer.myClockSeconds,
            serverMs = match.myClockMs(userId),
        )
        val (oppBaseMs, oppRunningSync) = reconcileClockBaseMs(
            running = oppRunning,
            displayedSeconds = timer.opponentClockSeconds,
            serverMs = match.opponentClockMs(userId),
        )
        clockSyncMyBaseMs = myBaseMs
        clockSyncOppBaseMs = oppBaseMs
        clockSyncMyRunning = myRunningSync
        clockSyncOppRunning = oppRunningSync
    }

    private fun syncMatchClocks(match: Match?, userId: String?) {
        if (clocksPausedForNetwork) return
        if (match == null || userId == null) {
            clockJob?.cancel()
            lastClockFingerprint = null
            updateTimerState { it.copy(myClockSeconds = null, opponentClockSeconds = null) }
            return
        }
        if (match.status == MatchStatus.COMPLETED || match.status == MatchStatus.ABANDONED) {
            clockJob?.cancel()
            clockSyncMyRunning = false
            clockSyncOppRunning = false
            val existing = _timerUiState.value
            if (existing.myClockSeconds != null && existing.opponentClockSeconds != null) {
                _uiState.update { current ->
                    if (current.opponentHasSubmitted) current else current.copy(opponentHasSubmitted = true)
                }
                return
            }
            val maxClockSeconds = (GameRules.MAX_CLOCK_MS / 1_000).toInt()
            val mySeconds = ((match.myClockMs(userId) + 999) / 1000).toInt().coerceIn(0, maxClockSeconds)
            val oppSeconds = ((match.opponentClockMs(userId) + 999) / 1000).toInt().coerceIn(0, maxClockSeconds)
            updateTimerState {
                it.copy(myClockSeconds = mySeconds, opponentClockSeconds = oppSeconds)
            }
            _uiState.update { current ->
                if (current.opponentHasSubmitted) current else current.copy(opponentHasSubmitted = true)
            }
            return
        }
        if (match.status == MatchStatus.ACTIVE && match.openRound() == null) {
            // Final round resolved; waiting for match doc to finalize — keep displayed clocks.
            clockJob?.cancel()
            return
        }
        if (match.status != MatchStatus.ACTIVE || match.openRound() == null) {
            clockJob?.cancel()
            lastClockFingerprint = null
            updateTimerState { it.copy(myClockSeconds = null, opponentClockSeconds = null) }
            return
        }

        val openRound = match.openRound()!!
        val serverSubmitted = openRound.hasSubmittedFor(userId, match.player1)
        val opponentSubmitted = openRound.opponentHasSubmittedFor(userId, match.player1)
        val fingerprint = buildString {
            append(match.clocksUpdatedAt)
            append('|')
            append(match.player1ClockMs)
            append('|')
            append(match.player2ClockMs)
            append('|')
            append(serverSubmitted)
            append('|')
            append(opponentSubmitted)
        }
        if (fingerprint != lastClockFingerprint) {
            lastClockFingerprint = fingerprint
            resyncClockAnchor(match, userId, myRunning = !serverSubmitted, oppRunning = !opponentSubmitted)
            val opponentSubmittedUi = opponentSubmitted || !clockSyncOppRunning
            _uiState.update { current ->
                if (current.opponentHasSubmitted == opponentSubmittedUi) current
                else current.copy(opponentHasSubmitted = opponentSubmittedUi)
            }
        }

        if (clockJob?.isActive == true) return

        clockJob?.cancel()
        clockJob = viewModelScope.launch {
            val maxClockSeconds = (GameRules.MAX_CLOCK_MS / 1_000).toInt()
            while (true) {
                if (clocksPausedForNetwork) break
                val state = _uiState.value
                val activeMatch = state.match ?: break
                if (activeMatch.status != MatchStatus.ACTIVE) break

                val elapsed = SystemClock.elapsedRealtime() - clockSyncRealtimeMs
                val myMs = if (clockSyncMyRunning) {
                    (clockSyncMyBaseMs - elapsed).coerceAtLeast(0L)
                } else {
                    clockSyncMyBaseMs
                }
                val oppMs = if (clockSyncOppRunning) {
                    (clockSyncOppBaseMs - elapsed).coerceAtLeast(0L)
                } else {
                    clockSyncOppBaseMs
                }

                val mySeconds = ((myMs + 999) / 1000).toInt().coerceIn(0, maxClockSeconds)
                val oppSeconds = ((oppMs + 999) / 1000).toInt().coerceIn(0, maxClockSeconds)
                val opponentSubmittedNow = activeMatch.openRound()?.let { open ->
                    open.opponentHasSubmittedFor(userId, activeMatch.player1)
                } == true
                val opponentSubmitted = opponentSubmittedNow || !clockSyncOppRunning
                updateTimerState { current ->
                    if (current.myClockSeconds == mySeconds && current.opponentClockSeconds == oppSeconds) {
                        current
                    } else {
                        current.copy(
                            myClockSeconds = mySeconds,
                            opponentClockSeconds = oppSeconds,
                        )
                    }
                }
                if (_uiState.value.opponentHasSubmitted != opponentSubmitted) {
                    _uiState.update { it.copy(opponentHasSubmitted = opponentSubmitted) }
                }

                if (clockSyncMyRunning && myMs <= 0L) {
                    clockSyncMyRunning = false
                    clockSyncMyBaseMs = 0L
                    val open = activeMatch.openRound() ?: break
                    maybeRequestTimeoutResolution(activeMatch, open.roundNumber)
                }

                if (clockSyncOppRunning && oppMs <= 0L) {
                    clockSyncOppRunning = false
                    clockSyncOppBaseMs = 0L
                    val open = activeMatch.openRound() ?: break
                    if (!opponentSubmittedNow) {
                        maybeRequestTimeoutResolution(activeMatch, open.roundNumber)
                    }
                }

                delay(1_000)
            }
        }
    }

    private fun updateTimerState(transform: (GameTimerUiState) -> GameTimerUiState) {
        _timerUiState.update { current ->
            val next = transform(current)
            if (next == current) current else next
        }
    }

    private fun resyncRoundCountdownAnchor(openRound: RoundResult) {
        val startMs = openRound.roundStartMs() ?: return
        roundCountdownElapsedAtSyncMs = roundElapsedAtSyncMs(startMs, System.currentTimeMillis())
        roundCountdownSyncRealtimeMs = SystemClock.elapsedRealtime()
    }

    private fun currentRoundSecondsRemaining(): Int =
        computeRoundSecondsFromAnchor(
            elapsedAtSyncMs = roundCountdownElapsedAtSyncMs,
            syncElapsedRealtimeMs = roundCountdownSyncRealtimeMs,
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        )

    private fun syncCountdown(match: Match?) {
        if (clocksPausedForNetwork) return
        if (match?.status == MatchStatus.COMPLETED || match?.status == MatchStatus.ABANDONED) {
            countdownJob?.cancel()
            roundCountdownRoundKey = null
            _uiState.update { it.copy(isResolvingTimeout = false) }
            return
        }
        if (match?.status == MatchStatus.ACTIVE && match.openRound() == null) {
            // Final round resolved; keep the last round timer visible until results.
            countdownJob?.cancel()
            return
        }
        val openRound = match?.openRound()
        if (match?.status != MatchStatus.ACTIVE || openRound == null || openRound.roundStartMs() == null) {
            countdownJob?.cancel()
            roundCountdownRoundKey = null
            updateTimerState { it.copy(countdownSeconds = null) }
            _uiState.update { current ->
                if (!current.isResolvingTimeout) current else current.copy(isResolvingTimeout = false)
            }
            return
        }
        val roundKey = "${openRound.roundNumber}:${openRound.roundStartMs()}"
        val needsRestart = roundKey != roundCountdownRoundKey || countdownJob?.isActive != true
        if (needsRestart) {
            roundCountdownRoundKey = roundKey
            resyncRoundCountdownAnchor(openRound)
            timeoutRequestedForRound = null
            countdownJob?.cancel()
            countdownJob = viewModelScope.launch {
                val roundNumber = openRound.roundNumber
                while (true) {
                    if (clocksPausedForNetwork) break
                    val currentOpen = _uiState.value.match?.openRound()
                    if (currentOpen?.roundNumber != roundNumber) break

                    val seconds = currentRoundSecondsRemaining()
                    updateTimerState { current ->
                        if (current.countdownSeconds == seconds) current
                        else current.copy(countdownSeconds = seconds)
                    }
                    if (seconds <= 0) {
                        maybeRequestTimeoutResolution(match, roundNumber)
                        delay(500)
                        continue
                    }
                    delay(1_000)
                }
            }
        } else {
            val seconds = currentRoundSecondsRemaining()
            updateTimerState { current ->
                if (current.countdownSeconds == seconds) current
                else current.copy(countdownSeconds = seconds)
            }
        }
    }

    /**
     * Request server resolution once per round. Used when either match clock expires or the
     * round deadline passes; the server checks match-clock expiry first.
     */
    private fun maybeRequestTimeoutResolution(match: Match, roundNumber: Int) {
        if (timeoutRequestedForRound == roundNumber) {
            val state = _uiState.value
            if (state.isResolvingTimeout) return
            val stillOnSameRound = state.match?.openRound()?.roundNumber == roundNumber
            if (!stillOnSameRound && state.error == null) return
        }
        requestTimeoutResolution(match, roundNumber)
    }

    private suspend fun pollMatchAfterTimeoutRequest(roundNumber: Int): Boolean {
        repeat(5) {
            if (!_uiState.value.isResolvingTimeout) return false
            runCatching { syncMatchFromServer() }
            val match = _uiState.value.match
            if (
                match?.status == MatchStatus.COMPLETED ||
                match?.status == MatchStatus.ABANDONED ||
                match?.openRound()?.roundNumber != roundNumber
            ) {
                matchSnapshotAtTimeoutRequest = null
                _uiState.update { it.copy(isResolvingTimeout = false, error = null) }
                return true
            }
            delay(800)
        }
        return false
    }

    private fun requestTimeoutResolution(match: Match, roundNumber: Int) {
        timeoutRequestedForRound = roundNumber
        matchSnapshotAtTimeoutRequest = timeoutResolutionFingerprint(match)
        _uiState.update { it.copy(isResolvingTimeout = true, error = null) }
        resolvingRetryJob?.cancel()
        resolvingRetryJob = viewModelScope.launch {
            repeat(6) { attempt ->
                if (!_uiState.value.isResolvingTimeout || matchSnapshotAtTimeoutRequest == null) {
                    return@launch
                }
                try {
                    if (!RoundTimeoutRequestDeduper.wasSent(matchId, roundNumber)) {
                        matchRepository.requestRoundTimeout(matchId, roundNumber)
                    }
                    if (pollMatchAfterTimeoutRequest(roundNumber)) {
                        return@launch
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isIgnorableFirestoreRace(e)) {
                        matchSnapshotAtTimeoutRequest = null
                        _uiState.update { it.copy(isResolvingTimeout = false, error = null) }
                        return@launch
                    }
                    if (isQuotaExceededError(e) && attempt < 5) {
                        delay(3_000)
                        return@repeat
                    }
                    _uiState.update {
                        it.copy(
                            isResolvingTimeout = false,
                            error = userFacingGameError(e, "Failed to resolve timeout"),
                        )
                    }
                    matchSnapshotAtTimeoutRequest = null
                    return@launch
                }
                if (attempt < 5) {
                    delay(3_000)
                }
            }
            if (_uiState.value.isResolvingTimeout) {
                _uiState.update {
                    it.copy(
                        isResolvingTimeout = false,
                        error = "Round resolution is taking longer than expected. It should update shortly.",
                    )
                }
                matchSnapshotAtTimeoutRequest = null
            }
        }
    }

    fun submitMove(move: Move) {
        recoverStuckSubmitUiIfNeeded()

        val state = _uiState.value
        val userId = state.userId
        val match = state.match
        val openRound = match?.openRound()
        if (
            state.isSubmitting ||
            match?.status != MatchStatus.ACTIVE ||
            openRound == null ||
            userId == null
        ) {
            return
        }
        if (openRound.hasSubmittedFor(userId, match.player1)) {
            if (!state.serverMoveSubmitted) {
                viewModelScope.launch { runCatching { syncMatchFromServer() } }
            }
            return
        }
        if (state.hasSubmittedMove || state.pendingMove != null) {
            return
        }

        val roundNumber = openRound.roundNumber
        val generation = ++submitGeneration
        locallySubmittedRound = roundNumber
        lockedMoveRound = roundNumber
        submitWatchdogJob?.cancel()
        _uiState.update {
            it.copy(
                isSubmitting = true,
                hasSubmittedMove = true,
                pendingMove = move,
                lockedMove = move,
                error = null,
            )
        }

        activeSubmitJob?.cancel()
        activeSubmitJob = viewModelScope.launch {
            val serverSyncJob = launch {
                while (generation == submitGeneration) {
                    delay(400)
                    runCatching { syncMatchFromServer() }
                    if (!_uiState.value.isSubmitting && _uiState.value.hasSubmittedMove) break
                }
            }
            submitWatchdogJob = launch {
                delay(SUBMIT_STUCK_WATCHDOG_MS)
                if (generation != submitGeneration) return@launch
                if (!isSubmitForOpenRound(roundNumber)) return@launch
                revertMoveIfServerMissing(
                    generation,
                    roundNumber,
                ) { "Move is taking too long to reach the server. Tap a move to try again." }
            }
            var submitError: String? = null
            var staleRoundSubmit = false
            var matchAlreadyFinished = false
            var skipSubmitConfirmation = false
            var moveNotRecordedVerificationFailed = false
            try {
                if (!isSubmitForOpenRound(roundNumber)) return@launch
                matchRepository.submitMove(matchId, move, roundNumber)
            } catch (e: TimeoutCancellationException) {
                skipSubmitConfirmation = true
                submitError = "Connection timed out. Check your network and tap a move to try again."
            } catch (e: CancellationException) {
                if (generation == submitGeneration) {
                    submitError = "Move was interrupted. Tap a move to try again."
                }
            } catch (e: Exception) {
                when {
                    GameFunctions.isStaleRoundSubmitError(e) -> {
                        staleRoundSubmit = true
                        runCatching { syncMatchFromServer() }
                    }
                    GameFunctions.isMatchNotActiveSubmitError(e) -> {
                        matchAlreadyFinished = true
                        runCatching { syncMatchFromServer() }
                    }
                    isIgnorableFirestoreRace(e) -> Unit
                    GameFunctions.isMoveNotRecordedVerificationError(e) -> {
                        moveNotRecordedVerificationFailed = true
                        runCatching { syncMatchFromServer() }
                    }
                    isQuotaExceededError(e) -> {
                        skipSubmitConfirmation = true
                        submitError = quotaExceededUserMessage()
                    }
                    else ->
                        submitError = GameFunctions.toSubmitErrorMessage(e)
                            ?: userFacingGameError(e, "Failed to submit move")
                }
            } finally {
                withContext(NonCancellable) {
                    submitWatchdogJob?.cancel()
                    serverSyncJob.cancel()
                    if (generation != submitGeneration) return@withContext
                    if (!isSubmitForOpenRound(roundNumber)) {
                        if (acknowledgeSubmitOnRound(roundNumber, move)) return@withContext
                        abandonStaleSubmitUi()
                        return@withContext
                    }
                    if (matchAlreadyFinished) {
                        val terminal = _uiState.value.match?.status == MatchStatus.COMPLETED ||
                            _uiState.value.match?.status == MatchStatus.ABANDONED
                        if (terminal) {
                            abandonStaleSubmitUi()
                        } else {
                            revertMoveIfServerMissing(
                                generation,
                                roundNumber,
                                skipServerConfirmation = true,
                            ) {
                                "Match ended. Loading results…"
                            }
                            scheduleCompletionPoll(_uiState.value.match)
                        }
                        return@withContext
                    }
                    if (staleRoundSubmit) {
                        runCatching { syncMatchFromServer() }
                        if (acknowledgeSubmitOnRound(roundNumber, move)) return@withContext
                        abandonStaleSubmitUi()
                        return@withContext
                    }
                    if (submitError == null && acknowledgeSubmitOnRound(roundNumber, move)) {
                        return@withContext
                    }
                    revertMoveIfServerMissing(
                        generation,
                        roundNumber,
                        confirmedMove = move,
                        skipServerConfirmation = skipSubmitConfirmation ||
                            (submitError != null && !moveNotRecordedVerificationFailed),
                    ) {
                        submitError ?: "Move did not reach the server. Tap a move to try again."
                    }
                }
            }
        }
    }

    private fun abandonStaleSubmitUi() {
        locallySubmittedRound = null
        lockedMoveRound = null
        val userId = authRepository.currentUserId
        val match = _uiState.value.match
        val openRound = match?.openRound()
        val serverSubmitted = userId != null && match != null && openRound != null &&
            openRound.hasSubmittedFor(userId, match.player1)
        _uiState.update {
            it.copy(
                isSubmitting = false,
                pendingMove = null,
                hasSubmittedMove = serverSubmitted,
                serverMoveSubmitted = serverSubmitted,
                lockedMove = if (serverSubmitted) it.lockedMove else null,
                error = if (serverSubmitted) it.error else null,
            )
        }
    }

    /** Clears stale local submit UI so a failed move can be retried. */
    private fun recoverStuckSubmitUiIfNeeded() {
        val state = _uiState.value
        if (state.serverMoveSubmitted) return
        val submitJobActive = state.isSubmitting && activeSubmitJob?.isActive == true
        if (submitJobActive) return

        val stuckLocalSubmit = state.hasSubmittedMove ||
            state.pendingMove != null ||
            locallySubmittedRound != null ||
            state.isSubmitting
        if (!stuckLocalSubmit && state.error == null) return

        cancelInFlightSubmit(clearPendingUi = true)
        locallySubmittedRound = null
        lockedMoveRound = null
        _uiState.update {
            it.copy(
                error = null,
                hasSubmittedMove = false,
                serverMoveSubmitted = false,
                isSubmitting = false,
                pendingMove = null,
                lockedMove = null,
            )
        }
    }

    private fun isMoveConfirmedLocally(roundNumber: Int): Boolean {
        val userId = authRepository.currentUserId ?: return false
        return _uiState.value.match?.hasSubmittedInRound(userId, roundNumber) == true
    }

    /**
     * Submit succeeded on [roundNumber] but the open round may have advanced (recap / fast resolve).
     * Sync once, then finalize or clear local state without a false failure message.
     */
    private suspend fun acknowledgeSubmitOnRound(roundNumber: Int, move: Move?): Boolean {
        if (!isMoveConfirmedLocally(roundNumber)) {
            runCatching { syncMatchFromServer() }
        }
        if (!isMoveConfirmedLocally(roundNumber)) return false
        if (isSubmitForOpenRound(roundNumber)) {
            finalizeConfirmedMove(roundNumber, move)
        } else {
            clearLocalSubmitForAdvancedRound()
        }
        return true
    }

    /** Move is on a completed round; refresh UI for the new open round without an error. */
    private fun clearLocalSubmitForAdvancedRound() {
        locallySubmittedRound = null
        lockedMoveRound = null
        val userId = authRepository.currentUserId
        val match = _uiState.value.match
        val openRound = match?.openRound()
        val serverSubmitted = userId != null && match != null && openRound != null &&
            openRound.hasSubmittedFor(userId, match.player1)
        _uiState.update {
            it.copy(
                isSubmitting = false,
                pendingMove = null,
                hasSubmittedMove = serverSubmitted,
                serverMoveSubmitted = serverSubmitted,
                lockedMove = if (serverSubmitted) it.lockedMove else null,
                error = null,
            )
        }
    }

    private fun finalizeConfirmedMove(roundNumber: Int, confirmedMove: Move?) {
        locallySubmittedRound = null
        lockedMoveRound = roundNumber
        _uiState.update {
            it.copy(
                hasSubmittedMove = true,
                serverMoveSubmitted = true,
                lockedMove = confirmedMove ?: it.lockedMove ?: it.pendingMove,
                isSubmitting = false,
                pendingMove = null,
                error = null,
            )
        }
    }

    private suspend fun confirmMoveOnServer(roundNumber: Int): Match? {
        val userId = authRepository.currentUserId ?: return null
        repeat(SUBMIT_CONFIRM_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(SUBMIT_CONFIRM_DELAY_MS)
            _uiState.value.match
                ?.takeIf { it.hasSubmittedInRound(userId, roundNumber) }
                ?.let { return it }
            val fromServer = runCatching { matchRepository.getMatchFromServer(matchId) }.getOrNull()
                ?: return@repeat
            if (fromServer.hasSubmittedInRound(userId, roundNumber)) {
                applyMatchSnapshot(fromServer, authoritative = true)
                return fromServer
            }
        }
        return null
    }

    private suspend fun revertMoveIfServerMissing(
        generation: Int,
        roundNumber: Int,
        confirmedMove: Move? = null,
        skipServerConfirmation: Boolean = false,
        errorMessage: () -> String,
    ) {
        if (generation != submitGeneration) return
        if (acknowledgeSubmitOnRound(roundNumber, confirmedMove)) return
        if (!isSubmitForOpenRound(roundNumber)) {
            abandonStaleSubmitUi()
            return
        }
        if (!skipServerConfirmation) {
            val confirmedMatch = confirmMoveOnServer(roundNumber)
            if (confirmedMatch != null && acknowledgeSubmitOnRound(roundNumber, confirmedMove)) {
                return
            }
        }
        if (acknowledgeSubmitOnRound(roundNumber, confirmedMove)) return
        locallySubmittedRound = null
        lockedMoveRound = null
        _uiState.update {
            it.copy(
                hasSubmittedMove = false,
                serverMoveSubmitted = false,
                lockedMove = null,
                isSubmitting = false,
                pendingMove = null,
                error = errorMessage(),
            )
        }
        syncMatchClocks(_uiState.value.match, authRepository.currentUserId)
    }

    override fun onCleared() {
        observeJob?.cancel()
        stopLobbyReadyLoop()
        countdownJob?.cancel()
        clockJob?.cancel()
        resolvingRetryJob?.cancel()
        stuckRoundNudgeJob?.cancel()
        completionPollJob?.cancel()
        cancelInFlightSubmit()
        super.onCleared()
    }

    companion object {
        private const val CONNECTION_LOST_MESSAGE =
            "Connection lost. Waiting to reconnect…"
        private const val LOBBY_WAIT_TIMEOUT_MESSAGE =
            "Opponent did not ready in time. Tap Find Match to try again."
        private const val SUBMIT_STUCK_WATCHDOG_MS = 22_000L
        private const val SUBMIT_CONFIRM_ATTEMPTS = 6
        private const val SUBMIT_CONFIRM_DELAY_MS = 400L
        private const val COMPLETION_POLL_INTERVAL_MS = 2_000L
        private const val COMPLETION_POLL_ATTEMPTS = 15

        fun factory(matchId: String): androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return GameViewModel(matchId) as T
                }
            }
    }
}

/** Round already advanced or listener cancelled — not worth showing to the player. */
private fun isIgnorableFirestoreRace(error: Throwable): Boolean {
    if (error is CancellationException) return true
    if (error is FirebaseFirestoreException &&
        error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
    ) {
        return true
    }
    val message = error.message.orEmpty()
    if (message.contains("PERMISSION_DENIED", ignoreCase = true)) return true
    if (message.contains("StandaloneCoroutine was cancelled", ignoreCase = true)) return true
    val cause = error.cause ?: return false
    if (cause === error) return false
    return isIgnorableFirestoreRace(cause)
}

private fun userFacingGameError(error: Throwable, fallback: String): String =
    userFacingFirebaseError(error, fallback)
