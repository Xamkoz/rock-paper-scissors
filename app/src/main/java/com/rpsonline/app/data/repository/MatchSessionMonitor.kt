package com.rpsonline.app.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import com.rpsonline.app.data.monitoring.NetworkDataActivityTracker
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.model.UserProfile
import com.rpsonline.app.domain.MatchMode
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

    private var pendingLaunchMatchId: String? = null
    private var enqueueNavigationJob: Job? = null
    private var autoGameNavigationSuppressedMatchId: String? = null

    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var userListener: ListenerRegistration? = null
    private var matchListener: ListenerRegistration? = null
    private var queueListener: ListenerRegistration? = null
    private var listeningMatchId: String? = null
    private var attachedUid: String? = null
    private var lastServerSyncAtMs = 0L
    private var lastQueueServerVerifyAtMs = 0L
    private var lastQueueRecoveryRequestAtMs = 0L
    private var queueRecoveryJob: Job? = null
    private var pendingRecoveryMatchModes: Set<MatchMode>? = null
    private val authRepository = AuthRepository()

    /** Invoked on the main thread when background queue recovery cannot re-join. */
    @Volatile
    var onQueueRecoveryFailed: ((message: String) -> Unit)? = null

    /** Min gap between server queue existence checks (avoids hammering Firestore). */
    private const val QUEUE_SERVER_VERIFY_MIN_INTERVAL_MS = 60_000L
    private const val QUEUE_RECOVERY_REQUEST_MIN_INTERVAL_MS = 15_000L
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
        reattachListeners(uid)
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

    fun isMatchmakingInProgress(): Boolean = _matchmakingInProgress.value

    /** True while the client should keep queue heartbeats (listener doc or confirmed join). */
    fun shouldSendQueueHeartbeats(): Boolean =
        _hasQueueEntry.value ||
            (_matchmakingInProgress.value && queueElapsedAnchorMs() != null)

    fun hasPendingGameNavigation(): Boolean = _pendingGameNavigationMatchId.value != null

    fun requestGameNavigation(matchId: String) {
        if (isAutoGameNavigationSuppressed(matchId)) return
        _pendingGameNavigationMatchId.value = matchId
    }

    fun isAutoGameNavigationSuppressed(matchId: String): Boolean =
        autoGameNavigationSuppressedMatchId == matchId

    /** Match id from a notification or background launch intent, if navigation is still allowed. */
    fun pendingGameLaunchMatchId(): String? {
        val matchId = _pendingGameNavigationMatchId.value ?: pendingLaunchMatchId ?: return null
        return matchId.takeUnless { isAutoGameNavigationSuppressed(it) }
    }

    fun clearAutoGameNavigationSuppression(matchId: String) {
        if (autoGameNavigationSuppressedMatchId == matchId) {
            autoGameNavigationSuppressedMatchId = null
        }
    }

    /** User left an active game via back; stay on home until they tap reconnect. */
    fun suppressAutoGameNavigation(matchId: String) {
        autoGameNavigationSuppressedMatchId = matchId
        pendingLaunchMatchId = null
        enqueueNavigationJob?.cancel()
        enqueueNavigationJob = null
        setMatchmakingInProgress(false)
        consumeGameNavigation()
    }

    fun noteMatchLaunchIntent(matchId: String) {
        autoGameNavigationSuppressedMatchId = null
        pendingLaunchMatchId = matchId
        _matchmakingInProgress.value = true
        _matchLaunchUiNudge.value += 1
    }

    fun enqueueGameNavigationWhenReady(matchId: String) {
        if (isAutoGameNavigationSuppressed(matchId)) return
        pendingLaunchMatchId = matchId
        enqueueNavigationJob?.cancel()
        enqueueNavigationJob = sessionScope.launch {
            repeat(40) {
                if (isAutoGameNavigationSuppressed(matchId)) return@launch
                val match = _activeMatch.value
                val uid = auth.currentUser?.uid
                if (
                    match?.id == matchId &&
                    uid != null &&
                    match.isParticipant(uid) &&
                    (match.status == MatchStatus.ACTIVE || match.status == MatchStatus.LOBBY)
                ) {
                    requestGameNavigation(matchId)
                    return@launch
                }
                delay(250)
            }
        }
    }

    fun consumeGameNavigation() {
        _pendingGameNavigationMatchId.value = null
        pendingLaunchMatchId = null
        enqueueNavigationJob?.cancel()
        enqueueNavigationJob = null
    }

    /** Match ended; clear notification-launch navigation so back from result stays on home. */
    fun onMatchFinished(matchId: String) {
        pendingLaunchMatchId = null
        enqueueNavigationJob?.cancel()
        enqueueNavigationJob = null
        consumeGameNavigation()
        setMatchmakingInProgress(false)
        autoGameNavigationSuppressedMatchId = matchId
        val finished = _activeMatch.value?.takeIf { it.id == matchId }
        if (finished != null) {
            matchRepository.invalidateConcludedMatchCacheForParticipants(
                finished.player1,
                finished.player2,
            )
        } else {
            sessionScope.launch {
                val snap = runCatching {
                    firestore.collection("matches").document(matchId).get().await()
                }.getOrNull()
                if (snap != null && snap.exists()) {
                    val match = snap.toMatch(matchId)
                    matchRepository.invalidateConcludedMatchCacheForParticipants(
                        match.player1,
                        match.player2,
                    )
                }
            }
        }
    }

    /** Called when queue entry is confirmed (server or client join timestamp). */
    fun confirmQueueJoinedAt(joinedAtMs: Long) {
        if (!_matchmakingInProgress.value) return
        _hasQueueEntry.value = true
        _matchmakingInProgress.value = true
        mergeQueueJoinedAtMs(joinedAtMs)
        ensureQueueTimerAnchor(joinedAtMs)
        notifySessionStateChanged()
    }

    private fun mergeQueueJoinedAtMs(candidateMs: Long) {
        _queueJoinedAtMs.value = mergeQueueJoinedAtMs(_queueJoinedAtMs.value, candidateMs)
        ensureQueueTimerAnchor(candidateMs)
    }

    private fun ensureQueueTimerAnchor(candidateMs: Long) {
        if (candidateMs <= 0L || _queueTimerAnchorMs.value != null) return
        _queueTimerAnchorMs.value = candidateMs
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
        clearQueueTimerAnchor()
        _hasQueueEntry.value = false
        _matchmakingInProgress.value = false
        _pendingGameNavigationMatchId.value = null
        pendingLaunchMatchId = null
        autoGameNavigationSuppressedMatchId = null
        enqueueNavigationJob?.cancel()
        enqueueNavigationJob = null
    }

    private fun reattachListeners(uid: String) {
        attachedUid = uid
        clearFirestoreListeners()
        attachListeners(uid)
    }

    private suspend fun syncFromServer(uid: String) {
        val userSnap = runCatching {
            firestore.collection("users").document(uid).get(Source.SERVER).await()
        }.getOrNull() ?: return

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
                    listeningMatchId = null
                    if (finalizedMatchId.isNullOrBlank()) {
                        _activeMatch.value = null
                    } else {
                        publishFinalMatchSnapshot(finalizedMatchId)
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
        NetworkDataActivityTracker.bump()
        _hasQueueEntry.value = true
        resolveQueueJoinedAtMs(snapshot!!)?.let { mergeQueueJoinedAtMs(it) }
        if (snapshot.metadata.hasPendingWrites()) {
            return
        }
        if (fromCache) {
            maybeVerifyQueueOnServerThrottled()
        }
    }

    private fun maybeVerifyQueueOnServerThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastQueueServerVerifyAtMs < QUEUE_SERVER_VERIFY_MIN_INTERVAL_MS) return
        lastQueueServerVerifyAtMs = now
        sessionScope.launch {
            verifyQueueOnServer()
        }
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
        if (!_matchmakingInProgress.value) return
        if (isQueueEntryPending()) return
        if (shouldSendQueueHeartbeats()) {
            verifyQueueOnServer()
            return
        }
        val serverJoinedAtMs = runCatching { matchRepository.getQueueJoinedAtMs() }.getOrNull()
        if (serverJoinedAtMs != null) {
            confirmQueueJoinedAt(serverJoinedAtMs)
            return
        }
        val modes = pendingRecoveryMatchModes ?: return
        val user = auth.currentUser ?: return
        val profile = resolveRecoveryProfile(user.uid, user) ?: return
        runCatching {
            matchRepository.joinQueue(modes, profile)
        }.onSuccess { result ->
            result.immediateMatchId?.let { matchId ->
                enqueueGameNavigationWhenReady(matchId)
                return@onSuccess
            }
            confirmQueueJoinedAt(result.clientJoinedAtMs ?: System.currentTimeMillis())
        }.onFailure {
            onQueueRecoveryFailed?.invoke(QUEUE_RECOVERY_FAILURE_MESSAGE)
        }
    }

    private fun resolveRecoveryProfile(uid: String, user: FirebaseUser): UserProfile? {
        authRepository.queueReadyProfile(uid)?.let { return it }
        return authRepository.fallbackProfile(user)
    }

    private fun resolveQueueJoinedAtMs(snapshot: DocumentSnapshot): Long? {
        return snapshot.getTimestamp("joinedAt")?.toDate()?.time
            ?: snapshot.getLong("clientJoinedAt")?.takeIf { it > 0L }
    }

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
                    NetworkDataActivityTracker.bump()
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
        NetworkDataActivityTracker.bump()
        val uid = auth.currentUser?.uid ?: return
        if (match.isParticipant(uid) &&
            (match.status == MatchStatus.LOBBY || match.status == MatchStatus.ACTIVE)
        ) {
            notifyActiveMatchPublished(match)
        }
        if (
            match.status == MatchStatus.ACTIVE &&
            match.isParticipant(uid) &&
            _matchmakingInProgress.value &&
            !isAutoGameNavigationSuppressed(match.id) &&
            !fromCache
        ) {
            requestGameNavigation(match.id)
            pendingLaunchMatchId = null
        } else if (
            match.isParticipant(uid) &&
            (match.status == MatchStatus.COMPLETED || match.status == MatchStatus.ABANDONED) &&
            (pendingLaunchMatchId == match.id || _pendingGameNavigationMatchId.value == match.id)
        ) {
            onMatchFinished(match.id)
        }
    }

    /** Avoid cached LOBBY snapshots overwriting a live ACTIVE match after background resume. */
    private fun shouldReplaceActiveMatch(
        incoming: Match,
        current: Match?,
        fromCache: Boolean,
    ): Boolean {
        if (current == null || current.id != incoming.id) return true
        if (current.status == MatchStatus.ACTIVE && incoming.status == MatchStatus.LOBBY) return false
        if (current.status == MatchStatus.LOBBY && incoming.status == MatchStatus.ACTIVE) return true
        if (fromCache && current.status == MatchStatus.ACTIVE) return false
        if (incoming.lastActivityAt > current.lastActivityAt) return true
        if (incoming.status != current.status) {
            return statusRank(incoming.status) > statusRank(current.status)
        }
        return !fromCache && incoming.lastActivityAt >= current.lastActivityAt
    }

    private fun statusRank(status: MatchStatus): Int = when (status) {
        MatchStatus.LOBBY -> 1
        MatchStatus.ACTIVE -> 2
        MatchStatus.COMPLETED -> 3
        MatchStatus.ABANDONED -> 3
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
            requestQueueRecovery()
        }
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

    private fun notifySessionStateChanged() {
        onSessionStateChanged?.invoke()
    }

    private fun notifyActiveMatchPublished(match: Match) {
        onActiveMatchPublished?.invoke(match)
    }
}
