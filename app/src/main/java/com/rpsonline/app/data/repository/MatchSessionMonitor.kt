package com.rpsonline.app.data.repository

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.rpsonline.app.data.preferences.MatchModePreferences
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import com.rpsonline.app.data.monitoring.NetworkDataActivityKind
import com.rpsonline.app.data.monitoring.NetworkDataActivityTracker
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.model.UserProfile
import com.rpsonline.app.domain.MatchMode
import com.rpsonline.app.data.preferences.MatchmakingPreferences
import com.rpsonline.app.platform.AppForegroundTracker
import com.rpsonline.app.platform.computeShouldSyncFromServerOnResume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Single Firestore subscription for the signed-in user's active match and queue doc.
 * Shared by [MatchRepository], [HomeViewModel], and global UI effects in [RpsApp].
 */
object MatchSessionMonitor {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = appFirestore()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val matchRepository = MatchRepository()

    private val _activeMatch = MutableStateFlow<Match?>(null)
    val activeMatch: StateFlow<Match?> = _activeMatch.asStateFlow()

    private val _queueJoinedAtMs = MutableStateFlow<Long?>(null)
    val queueJoinedAtMs: StateFlow<Long?> = _queueJoinedAtMs.asStateFlow()

    /** Stable queue timer start; kept across transient queue-doc loss during background search. */
    private val _queueTimerAnchorMs = MutableStateFlow<Long?>(null)
    val queueTimerAnchorMs: StateFlow<Long?> = _queueTimerAnchorMs.asStateFlow()

    /** Elapsed-time anchor for queue UI; survives [signalQueueDocLost] until matchmaking ends. */
    fun queueElapsedAnchorMs(): Long? = _queueTimerAnchorMs.value ?: _queueJoinedAtMs.value

    /** Queue doc exists locally; heartbeats run while this is true. */
    private val _hasQueueEntry = MutableStateFlow(false)
    val hasQueueEntry: StateFlow<Boolean> = _hasQueueEntry.asStateFlow()

    fun isQueueEntryPending(): Boolean = _hasQueueEntry.value && _queueJoinedAtMs.value == null

    /** Set while the user is joining or waiting in queue; drives auto-navigation to game. */
    private val _matchmakingInProgress = MutableStateFlow(false)
    val matchmakingInProgress: StateFlow<Boolean> = _matchmakingInProgress.asStateFlow()

    private val _queueRecoveryRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val queueRecoveryRequests: SharedFlow<Unit> = _queueRecoveryRequests.asSharedFlow()

    /** Pending navigation to game; survives HomeViewModel / back-stack lifecycle. */
    private val _pendingGameNavigationMatchId = MutableStateFlow<String?>(null)
    val pendingGameNavigationMatchId: StateFlow<String?> = _pendingGameNavigationMatchId.asStateFlow()

    private val _matchLaunchUiNudge = MutableStateFlow(0)
    val matchLaunchUiNudge: StateFlow<Int> = _matchLaunchUiNudge.asStateFlow()

    /** Game route is showing this match (used to clear the match-found notification). */
    private val _visibleMatchScreenId = MutableStateFlow<String?>(null)
    val visibleMatchScreenId: StateFlow<String?> = _visibleMatchScreenId.asStateFlow()

    /** Set only from notification tap / explicit launch intent. */
    private var userLaunchMatchId: String? = null
    /** Poll target for [enqueueGameNavigationWhenReady]; not an explicit user launch. */
    private var pendingReadyNavigationMatchId: String? = null
    private var enqueueNavigationJob: Job? = null
    private var autoGameNavigationSuppressedMatchId: String? = null

    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var userListener: ListenerRegistration? = null
    private var matchListener: ListenerRegistration? = null
    private var queueListener: ListenerRegistration? = null
    private var listeningMatchId: String? = null
    private var attachedUid: String? = null
    private var lastServerSyncAtMs = 0L
    private var lastQueueRecoveryRequestAtMs = 0L
    private var lastQueueNetworkBumpJoinedAtMs: Long? = null
    private var queueRecoveryJob: Job? = null
    private var pendingRecoveryMatchModes: Set<MatchMode>? = null
    private val authRepository = AuthRepository()

    /** Invoked on the main thread when background queue recovery cannot re-join. */
    @Volatile
    var onQueueRecoveryFailed: ((message: String) -> Unit)? = null

    /** Invoked when a match session ends and local queue UI should reset. */
    @Volatile
    var onMatchSessionEnded: (() -> Unit)? = null

    private const val QUEUE_RECOVERY_REQUEST_MIN_INTERVAL_MS = 15_000L
    private const val CONCLUDED_CACHE_INVALIDATION_DELAY_MS = 4_000L
    private const val QUEUE_RECOVERY_FAILURE_MESSAGE =
        "Lost connection to the matchmaking queue. Tap Find Match to try again."

    fun ensureStarted() {
        ensureQueueRecoveryObserver()
        if (authListener != null) return
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            attachForUser(firebaseAuth.currentUser?.uid)
        }
        authListener = listener
        auth.addAuthStateListener(listener)
        attachForUser(auth.currentUser?.uid)
    }

    fun setRecoveryMatchModes(modes: Set<MatchMode>?) {
        pendingRecoveryMatchModes = modes
    }

    private fun ensureQueueRecoveryObserver() {
        if (queueRecoveryJob?.isActive == true) return
        queueRecoveryJob = sessionScope.launch {
            queueRecoveryRequests.collect {
                performQueueRecovery()
            }
        }
    }

    /**
     * Reconnects snapshot listeners and optionally pulls session docs from the server.
     * @return true when [syncFromServer] ran (queue/match state was server-refreshed).
     */
    suspend fun refreshOnResume(forceServerSync: Boolean = false): Boolean {
        ensureStarted()
        val uid = auth.currentUser?.uid ?: return false
        FirestoreConnectivity.restoreOnResume()
        ensureListenersAttached(uid)
        val nowMs = System.currentTimeMillis()
        if (!computeShouldSyncFromServerOnResume(nowMs, lastServerSyncAtMs, forceServerSync)) {
            return false
        }
        syncFromServer(uid)
        lastServerSyncAtMs = nowMs
        return true
    }

    fun setMatchmakingInProgress(active: Boolean) {
        _matchmakingInProgress.value = active
        if (!active) {
            clearQueueTimerAnchor()
        }
    }

    /** Drop a concluded match still held for the result screen before joining queue again. */
    fun discardTerminalActiveMatchIfPresent() {
        when (_activeMatch.value?.status) {
            MatchStatus.COMPLETED, MatchStatus.ABANDONED -> _activeMatch.value = null
            else -> Unit
        }
    }

    fun isMatchmakingInProgress(): Boolean = _matchmakingInProgress.value

    /** True while queue/{uid} exists locally and should receive heartbeats. */
    fun shouldSendQueueHeartbeats(): Boolean = _hasQueueEntry.value

    fun hasPendingGameNavigation(): Boolean = _pendingGameNavigationMatchId.value != null

    fun requestGameNavigation(matchId: String) {
        if (isAutoGameNavigationSuppressed(matchId)) return
        if (!shouldAllowPassiveGameJoin(matchId)) return
        _pendingGameNavigationMatchId.value = matchId
    }

    fun shouldAllowPassiveGameJoin(matchId: String): Boolean {
        val appContext = runCatching { FirebaseApp.getInstance().applicationContext }.getOrNull()
        val backgroundUsageEnabled = appContext
            ?.let { MatchmakingPreferences(it).isBackgroundUsageEnabled() }
            ?: false
        return shouldAllowPassiveMatchJoinWhenBackgrounded(
            backgroundUsageEnabled = backgroundUsageEnabled,
            appInForeground = AppForegroundTracker.isInForeground,
            explicitLaunchMatchId = userLaunchMatchId,
            matchId = matchId,
        )
    }

    fun isAutoGameNavigationSuppressed(matchId: String): Boolean =
        autoGameNavigationSuppressedMatchId == matchId

    /** Match id from a notification tap or launch intent; not set by passive session updates. */
    fun explicitLaunchMatchId(): String? = userLaunchMatchId

    /** Pending navigation target when allowed; blocked in background without explicit launch. */
    fun pendingGameLaunchMatchId(): String? {
        val matchId = _pendingGameNavigationMatchId.value ?: userLaunchMatchId ?: return null
        if (isAutoGameNavigationSuppressed(matchId)) return null
        if (!shouldAllowPassiveGameJoin(matchId)) return null
        return matchId
    }

    fun clearAutoGameNavigationSuppression(matchId: String) {
        if (autoGameNavigationSuppressedMatchId == matchId) {
            autoGameNavigationSuppressedMatchId = null
        }
    }

    /** User left an active game via back; stay on home until they tap reconnect. */
    fun suppressAutoGameNavigation(matchId: String) {
        autoGameNavigationSuppressedMatchId = matchId
        userLaunchMatchId = null
        pendingReadyNavigationMatchId = null
        enqueueNavigationJob?.cancel()
        enqueueNavigationJob = null
        setMatchmakingInProgress(false)
        consumeGameNavigation()
    }

    fun noteMatchLaunchIntent(matchId: String) {
        if (shouldDropPendingGameNavigation(matchId, _activeMatch.value)) {
            consumeGameNavigation()
            return
        }
        autoGameNavigationSuppressedMatchId = null
        userLaunchMatchId = matchId
        _matchLaunchUiNudge.value += 1
    }

    fun enqueueGameNavigationWhenReady(matchId: String) {
        if (isAutoGameNavigationSuppressed(matchId)) return
        pendingReadyNavigationMatchId = matchId
        enqueueNavigationJob?.cancel()
        enqueueNavigationJob = sessionScope.launch {
            repeat(40) {
                if (isAutoGameNavigationSuppressed(matchId)) return@launch
                val match = _activeMatch.value
                val uid = auth.currentUser?.uid
                if (match?.id == matchId && uid != null && match.isParticipant(uid)) {
                    when (match.status) {
                        MatchStatus.ACTIVE, MatchStatus.LOBBY -> {
                            requestGameNavigation(matchId)
                            return@launch
                        }
                        MatchStatus.COMPLETED, MatchStatus.ABANDONED -> {
                            consumeGameNavigation()
                            return@launch
                        }
                        else -> Unit
                    }
                }
                delay(250)
            }
            if (pendingReadyNavigationMatchId == matchId ||
                _pendingGameNavigationMatchId.value == matchId
            ) {
                consumeGameNavigation()
            }
        }
    }

    fun consumeGameNavigation() {
        _pendingGameNavigationMatchId.value = null
        userLaunchMatchId = null
        pendingReadyNavigationMatchId = null
        enqueueNavigationJob?.cancel()
        enqueueNavigationJob = null
    }

    /** Match ended; clear notification-launch navigation so back from result stays on home. */
    fun onMatchFinished(matchId: String) {
        userLaunchMatchId = null
        pendingReadyNavigationMatchId = null
        enqueueNavigationJob?.cancel()
        enqueueNavigationJob = null
        consumeGameNavigation()
        matchListener?.remove()
        matchListener = null
        if (listeningMatchId == matchId) {
            listeningMatchId = null
        }
        clearQueueState(endMatchmaking = true)
        auth.currentUser?.uid?.let { uid ->
            sessionScope.launch {
                matchRepository.leaveQueueBestEffort(uid)
            }
        }
        notifyMatchSessionEnded()
        val finished = _activeMatch.value?.takeIf { it.id == matchId }
        if (_activeMatch.value?.id == matchId) {
            _activeMatch.value = null
        }
        notifySessionStateChanged()
        scheduleConcludedMatchCacheInvalidation(finished, matchId)
    }

    /** Defer cache invalidation so an immediate re-queue is not blocked by a burst of reads. */
    private fun scheduleConcludedMatchCacheInvalidation(finished: Match?, matchId: String) {
        sessionScope.launch {
            delay(CONCLUDED_CACHE_INVALIDATION_DELAY_MS)
            val match = finished ?: runCatching {
                firestore.collection("matches").document(matchId).get().await()
            }.getOrNull()?.takeIf { it.exists() }?.toMatch(matchId)
            if (match != null) {
                matchRepository.invalidateConcludedMatchCacheForParticipants(
                    match.player1,
                    match.player2,
                )
            }
        }
    }

    /** Called when queue entry is confirmed (server or client join timestamp). */
    fun confirmQueueJoinedAt(joinedAtMs: Long) {
        if (!_matchmakingInProgress.value) return
        _hasQueueEntry.value = true
        _matchmakingInProgress.value = true
        val anchorMs = normalizeQueueAnchorMs(joinedAtMs)
        mergeQueueJoinedAtMs(anchorMs)
        ensureQueueTimerAnchor(anchorMs)
        notifySessionStateChanged()
    }

    private fun mergeQueueJoinedAtMs(candidateMs: Long) {
        val anchorMs = normalizeQueueAnchorMs(candidateMs)
        _queueJoinedAtMs.value = mergeQueueJoinedAtMs(_queueJoinedAtMs.value, anchorMs)
        ensureQueueTimerAnchor(anchorMs)
    }

    private fun ensureQueueTimerAnchor(candidateMs: Long) {
        val anchorMs = normalizeQueueAnchorMs(candidateMs)
        if (anchorMs <= 0L || _queueTimerAnchorMs.value != null) return
        _queueTimerAnchorMs.value = anchorMs
    }

    private fun clearQueueTimerAnchor() {
        _queueTimerAnchorMs.value = null
    }

    private fun attachForUser(uid: String?) {
        if (uid == null) {
            val leaving = attachedUid
            attachedUid = null
            clearFirestoreListeners()
            resetSessionUiState()
            if (leaving != null) {
                matchRepository.clearStaleSessionQueueBestEffort(leaving)
            }
            return
        }
        if (uid == attachedUid) return

        val previous = attachedUid
        attachedUid = uid
        lastServerSyncAtMs = 0L
        clearFirestoreListeners()
        resetSessionUiState()
        if (previous != null && previous != uid) {
            matchRepository.clearStaleSessionQueueBestEffort(previous)
        }
        val bootstrap = QueueWriteGate.startBootstrap()
        sessionScope.launch {
            try {
                matchRepository.clearStaleSessionQueue(uid)
            } finally {
                QueueWriteGate.finishBootstrap(bootstrap)
            }
        }
        attachListeners(uid)
    }

    /** Waits for post-auth queue cleanup so join/presence writes are not raced. */
    suspend fun awaitSessionBootstrap() {
        QueueWriteGate.awaitBootstrap()
    }

    private fun resetSessionUiState() {
        _activeMatch.value = null
        _queueJoinedAtMs.value = null
        lastQueueNetworkBumpJoinedAtMs = null
        clearQueueTimerAnchor()
        _hasQueueEntry.value = false
        _matchmakingInProgress.value = false
        _pendingGameNavigationMatchId.value = null
        userLaunchMatchId = null
        pendingReadyNavigationMatchId = null
        autoGameNavigationSuppressedMatchId = null
        enqueueNavigationJob?.cancel()
        enqueueNavigationJob = null
    }

    private fun ensureListenersAttached(uid: String) {
        if (attachedUid == uid && userListener != null && queueListener != null) return
        attachedUid = uid
        clearFirestoreListeners()
        attachListeners(uid)
    }

    private fun hasStableQueueListenerSession(): Boolean =
        _matchmakingInProgress.value &&
            queueListener != null &&
            (_hasQueueEntry.value || _queueJoinedAtMs.value != null)

    private suspend fun syncFromServer(uid: String) {
        if (hasStableQueueListenerSession() && _activeMatch.value == null) {
            if (hydrateActiveMatchFromServer()) return
            return
        }

        val userSnap = runCatching {
            firestore.collection("users").document(uid).get(Source.SERVER).await()
        }.getOrNull() ?: return

        if (!hasStableQueueListenerSession()) {
            val queueSnap = runCatching {
                firestore.collection("queue").document(uid).get(Source.SERVER).await()
            }.getOrNull()
            val queueExists = queueSnap != null && queueSnap.exists()
            if (queueExists) {
                if (_matchmakingInProgress.value) {
                    _hasQueueEntry.value = true
                    resolveQueueJoinedAtMs(queueSnap!!)?.let { mergeQueueJoinedAtMs(it) }
                }
            } else {
                _hasQueueEntry.value = false
                if (!_matchmakingInProgress.value) {
                    _queueJoinedAtMs.value = null
                    clearQueueTimerAnchor()
                    runCatching { matchRepository.clearStaleSessionQueue(uid) }
                }
            }
        }

        val matchId = userSnap.getString("activeMatchId")
        if (matchId.isNullOrBlank()) {
            _activeMatch.value = null
            return
        }

        val matchSnap = runCatching {
            firestore.collection("matches").document(matchId).get(Source.SERVER).await()
        }.getOrNull() ?: return
        if (matchSnap.exists()) {
            publishActiveMatch(matchSnap.toMatch(matchId), fromCache = false)
        }
    }

    private fun attachListeners(uid: String) {
        queueListener = firestore.collection("queue").document(uid)
            .addSnapshotListener { snapshot, error ->
                applyQueueSnapshot(snapshot, error)
            }

        userListener = firestore.collection("users").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    _activeMatch.value = null
                    return@addSnapshotListener
                }

                matchListener?.remove()
                matchListener = null

                val matchId = snapshot?.getString("activeMatchId")
                if (matchId.isNullOrBlank()) {
                    val finalizedMatchId = listeningMatchId ?: _activeMatch.value?.id
                    matchListener?.remove()
                    matchListener = null
                    listeningMatchId = null
                    if (
                        shouldClearActiveMatchOnUserDocClear(
                            finalizedMatchId = finalizedMatchId,
                            currentMatch = _activeMatch.value,
                            matchmakingInProgress = _matchmakingInProgress.value,
                            hasPendingGameNavigation = hasPendingGameNavigation(),
                        )
                    ) {
                        _activeMatch.value = null
                    } else if (!finalizedMatchId.isNullOrBlank()) {
                        publishFinalMatchSnapshot(finalizedMatchId)
                    } else {
                        _activeMatch.value = null
                    }
                    return@addSnapshotListener
                }

                attachMatchListener(matchId)
            }
    }

    /** Match ended server-side; user doc cleared first — fetch final snapshot for UI feedback. */
    private fun publishFinalMatchSnapshot(matchId: String) {
        sessionScope.launch {
            val snap = runCatching {
                firestore.collection("matches").document(matchId).get().await()
            }.getOrNull()
            if (snap != null && snap.exists()) {
                publishActiveMatch(snap.toMatch(matchId), fromCache = false)
            } else if (_activeMatch.value?.id == matchId) {
                _activeMatch.value = null
            }
        }
    }

    private fun applyQueueSnapshot(snapshot: DocumentSnapshot?, error: Exception?) {
        val matchmaking = _matchmakingInProgress.value
        if (!matchmaking) {
            _hasQueueEntry.value = false
            _queueJoinedAtMs.value = null
            clearQueueTimerAnchor()
            return
        }
        if (QueueSnapshotPolicy.shouldRetainSessionOnListenerError(matchmaking, error)) {
            return
        }
        val exists = snapshot?.exists() == true
        val fromCache = snapshot?.metadata?.isFromCache != false
        if (
            QueueSnapshotPolicy.shouldRetainSessionOnMissingDoc(
                matchmakingInProgress = matchmaking,
                exists = exists,
                fromCache = fromCache,
            )
        ) {
            return
        }
        if (QueueSnapshotPolicy.isAuthoritativeQueueMissing(exists, fromCache)) {
            signalQueueDocLost()
            return
        }
        val joinedAtMs = resolveQueueJoinedAtMs(snapshot!!)
        if (
            shouldBumpQueueNetworkActivity(
                joinedAtMs = joinedAtMs,
                fromCache = fromCache,
                hasPendingWrites = snapshot.metadata.hasPendingWrites(),
                lastBumpedJoinedAtMs = lastQueueNetworkBumpJoinedAtMs,
            )
        ) {
            NetworkDataActivityTracker.bump(NetworkDataActivityKind.Queue)
            lastQueueNetworkBumpJoinedAtMs = joinedAtMs
        }
        _hasQueueEntry.value = true
        joinedAtMs?.let { mergeQueueJoinedAtMs(it) }
    }

    /**
     * Confirms queue/{uid} exists on the server. Clears local session via [signalQueueDocLost]
     * when the doc is gone but the client still thinks it is queued.
     */
    suspend fun verifyQueueOnServer(): Boolean {
        if (auth.currentUser?.uid == null) return false
        if (!_matchmakingInProgress.value && !shouldSendQueueHeartbeats()) return true
        when (val exists = matchRepository.queueEntryExistsOnServer()) {
            true -> {
                if (!_hasQueueEntry.value && _matchmakingInProgress.value) {
                    _hasQueueEntry.value = true
                    notifySessionStateChanged()
                }
                return true
            }
            null -> return false
            false -> {
                if (shouldSendQueueHeartbeats() || _hasQueueEntry.value || _queueJoinedAtMs.value != null) {
                    signalQueueDocLost()
                }
                return false
            }
        }
    }

    /**
     * Clears local queue markers only when the server confirms the queue doc is gone.
     * Transient read failures are ignored so background search is not dropped on doze/network blips.
     */
    suspend fun signalQueueDocLostIfAbsentOnServer() {
        when (matchRepository.queueEntryExistsOnServer()) {
            false -> signalQueueDocLost()
            else -> Unit
        }
    }

    private suspend fun performQueueRecovery() {
        if (isQueueEntryPending()) return
        if (hydrateActiveMatchFromServer()) return
        val serverQueueExists = matchRepository.queueEntryExistsOnServer()
        if (
            shouldSkipQueueRecovery(
                hasQueueEntry = _hasQueueEntry.value,
                queueJoinedAtMs = _queueJoinedAtMs.value,
                serverQueueExists = serverQueueExists,
            )
        ) {
            return
        }
        if (serverQueueExists == false) {
            _hasQueueEntry.value = false
        }
        val step = resolveQueueRecoveryStep(
            matchmakingInProgress = _matchmakingInProgress.value,
            queueEntryPending = false,
            serverQueueExists = serverQueueExists,
        )
        when (step) {
            QueueRecoveryStep.SKIP, QueueRecoveryStep.RETRY_LATER -> return
            QueueRecoveryStep.SYNC -> {
                _hasQueueEntry.value = true
                notifySessionStateChanged()
                val joinedAtMs = _queueJoinedAtMs.value
                    ?: matchRepository.peekQueueJoinedAtMs()
                joinedAtMs?.let { confirmQueueJoinedAt(it) }
            }
            QueueRecoveryStep.REJOIN -> {
                _hasQueueEntry.value = false
                notifySessionStateChanged()
                rejoinQueueAfterDocLoss()
            }
        }
    }

    private suspend fun rejoinQueueAfterDocLoss() {
        val modes = resolveRecoveryMatchModes() ?: run {
            onQueueRecoveryFailed?.invoke(QUEUE_RECOVERY_FAILURE_MESSAGE)
            return
        }
        val user = auth.currentUser ?: return
        val profile = resolveRecoveryProfile(user.uid, user) ?: return
        runCatching {
            matchRepository.joinQueue(modes, profile)
        }.onSuccess { result ->
            result.immediateMatchId?.let { matchId ->
                enqueueGameNavigationWhenReady(matchId)
                return@onSuccess
            }
            result.serverJoinedAtMs?.let { confirmQueueJoinedAt(it) }
                ?: _queueJoinedAtMs.value?.let { confirmQueueJoinedAt(it) }
                ?: matchRepository.peekQueueJoinedAtMs()?.let { confirmQueueJoinedAt(it) }
        }.onFailure {
            onQueueRecoveryFailed?.invoke(QUEUE_RECOVERY_FAILURE_MESSAGE)
        }
    }

    private fun resolveRecoveryMatchModes(): Set<MatchMode>? {
        pendingRecoveryMatchModes?.let { return it }
        val context = runCatching { FirebaseApp.getInstance().applicationContext }.getOrNull() ?: return null
        return MatchModePreferences(context).get().also { pendingRecoveryMatchModes = it }
    }

    private fun resolveRecoveryProfile(uid: String, user: FirebaseUser): UserProfile? {
        authRepository.queueReadyProfile(uid)?.let { return it }
        return authRepository.fallbackProfile(user)
    }

    private fun resolveQueueJoinedAtMs(snapshot: DocumentSnapshot): Long? =
        snapshot.getTimestamp("joinedAt")?.toDate()?.time

    /** Server-polled or game-screen snapshot; wins over stale cached listener data. */
    fun ingestAuthoritativeMatch(match: Match) {
        publishActiveMatch(match, fromCache = false, authoritative = true)
    }

    private fun attachMatchListener(matchId: String) {
        listeningMatchId = matchId
        matchListener = firestore.collection("matches").document(matchId)
            .addSnapshotListener { matchSnapshot, matchError ->
                if (matchError != null) {
                    _activeMatch.value = null
                    return@addSnapshotListener
                }
                if (matchSnapshot != null) {
                    NetworkDataActivityTracker.bump(NetworkDataActivityKind.Match)
                }
                val fromCache = matchSnapshot?.metadata?.isFromCache ?: true
                publishActiveMatch(matchSnapshot?.toMatch(matchId), fromCache)
            }
    }

    private fun publishActiveMatch(
        match: Match?,
        fromCache: Boolean,
        authoritative: Boolean = false,
    ) {
        if (match == null) {
            _activeMatch.value = null
            return
        }
        val current = _activeMatch.value
        if (!authoritative && !shouldReplaceActiveMatch(incoming = match, current = current, fromCache = fromCache)) {
            return
        }
        _activeMatch.value = match
        NetworkDataActivityTracker.bump(NetworkDataActivityKind.Match)
        val uid = auth.currentUser?.uid ?: return
        if (match.isParticipant(uid)) {
            when (match.status) {
                MatchStatus.LOBBY, MatchStatus.ACTIVE -> {
                    clearQueueState(endMatchmaking = false)
                    setMatchmakingInProgress(true)
                    sessionScope.launch {
                        matchRepository.leaveQueueBestEffort(uid)
                    }
                    notifyActiveMatchPublished(match)
                }
                MatchStatus.COMPLETED, MatchStatus.ABANDONED -> {
                    if (
                        shouldEndMatchmakingOnTerminalMatch(
                            terminalMatchId = match.id,
                            trackedMatchId = current?.id,
                            listeningMatchId = listeningMatchId,
                            hasQueueEntry = _hasQueueEntry.value,
                            matchmakingInProgress = _matchmakingInProgress.value,
                        )
                    ) {
                        clearQueueState(endMatchmaking = true)
                        if (match.id == current?.id || match.id == listeningMatchId) {
                            sessionScope.launch {
                                matchRepository.leaveQueueBestEffort(uid)
                            }
                        }
                    }
                }
            }
        }
        val appContext = runCatching { FirebaseApp.getInstance().applicationContext }.getOrNull()
        val backgroundUsageEnabled = appContext
            ?.let { MatchmakingPreferences(it).isBackgroundUsageEnabled() }
            ?: false
        if (
            shouldAutoNavigateToLiveMatch(
                match = match,
                userId = uid,
                fromCache = fromCache,
                matchmakingInProgress = _matchmakingInProgress.value,
                autoNavigationSuppressed = isAutoGameNavigationSuppressed(match.id),
                resumingFromQueueOrJoin = _hasQueueEntry.value,
                backgroundUsageEnabled = backgroundUsageEnabled,
                appInForeground = AppForegroundTracker.isInForeground,
                explicitLaunchMatchId = userLaunchMatchId,
            )
        ) {
            requestGameNavigation(match.id)
            userLaunchMatchId = null
            pendingReadyNavigationMatchId = null
        } else if (
            match.isParticipant(uid) &&
            (match.status == MatchStatus.COMPLETED || match.status == MatchStatus.ABANDONED) &&
            (userLaunchMatchId == match.id || _pendingGameNavigationMatchId.value == match.id)
        ) {
            onMatchFinished(match.id)
        }
    }

    private fun clearFirestoreListeners() {
        queueListener?.remove()
        queueListener = null
        userListener?.remove()
        userListener = null
        matchListener?.remove()
        matchListener = null
        listeningMatchId = null
    }

    /**
     * Queue doc is gone or heartbeats failed; clear local queue markers but keep matchmaking
     * active so [HomeViewModel] can rejoin the server queue.
     */
    fun signalQueueDocLost() {
        _hasQueueEntry.value = false
        notifySessionStateChanged()
        if (_matchmakingInProgress.value) {
            sessionScope.launch {
                if (!hydrateActiveMatchFromServer()) {
                    requestQueueRecovery()
                }
            }
        }
    }

    /**
     * Pairing may have completed while the queue doc was removed; load [activeMatchId] from the server.
     */
    private suspend fun hydrateActiveMatchFromServer(): Boolean {
        if (_activeMatch.value != null) return true
        val matchId = matchRepository.fetchLiveActiveMatchIdFromServer() ?: return false
        val match = matchRepository.getMatchFromServer(matchId) ?: return false
        publishActiveMatch(match, fromCache = false, authoritative = true)
        return true
    }

    /** Re-join or re-sync queue markers after connectivity returns or heartbeats cleared local state. */
    fun requestQueueRecovery() {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastQueueRecoveryRequestAtMs < QUEUE_RECOVERY_REQUEST_MIN_INTERVAL_MS) return
        lastQueueRecoveryRequestAtMs = nowMs
        _queueRecoveryRequests.tryEmit(Unit)
    }

    /** Local fallback when the user leaves matchmaking or auth/session resets. */
    fun clearQueueState(endMatchmaking: Boolean = true) {
        _hasQueueEntry.value = false
        _queueJoinedAtMs.value = null
        lastQueueNetworkBumpJoinedAtMs = null
        clearQueueTimerAnchor()
        if (endMatchmaking) {
            _matchmakingInProgress.value = false
            pendingRecoveryMatchModes = null
        }
        notifySessionStateChanged()
    }

    /** Invoked after queue/match session markers change; used to stop background status UI. */
    @Volatile
    var onSessionStateChanged: (() -> Unit)? = null

    /** Invoked when [publishActiveMatch] commits a LOBBY/ACTIVE match (foreground launch). */
    @Volatile
    var onActiveMatchPublished: ((Match) -> Unit)? = null

    /** Re-emits UI state for collectors after a background launch intent. */
    fun nudgeMatchLaunchUi() {
        _matchLaunchUiNudge.value += 1
    }

    fun setVisibleMatchScreenId(matchId: String?) {
        if (_visibleMatchScreenId.value == matchId) return
        _visibleMatchScreenId.value = matchId
        notifySessionStateChanged()
    }

    private fun notifySessionStateChanged() {
        onSessionStateChanged?.invoke()
    }

    private fun notifyMatchSessionEnded() {
        onMatchSessionEnded?.invoke()
    }

    private fun notifyActiveMatchPublished(match: Match) {
        onActiveMatchPublished?.invoke(match)
    }
}
