package com.rpsonline.app.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.platform.computeShouldSyncFromServerOnResume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /** Queue doc exists locally; heartbeats run while this is true. */
    private val _hasQueueEntry = MutableStateFlow(false)
    val hasQueueEntry: StateFlow<Boolean> = _hasQueueEntry.asStateFlow()

    fun isQueueEntryPending(): Boolean = _hasQueueEntry.value && _queueJoinedAtMs.value == null

    /** Set while the user is joining or waiting in queue; drives auto-navigation to game. */
    private val _matchmakingInProgress = MutableStateFlow(false)
    val matchmakingInProgress: StateFlow<Boolean> = _matchmakingInProgress.asStateFlow()

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

    fun ensureStarted() {
        if (authListener != null) return
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            attachForUser(firebaseAuth.currentUser?.uid)
        }
        authListener = listener
        auth.addAuthStateListener(listener)
        attachForUser(auth.currentUser?.uid)
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
    }

    fun isMatchmakingInProgress(): Boolean = _matchmakingInProgress.value

    fun requestGameNavigation(matchId: String) {
        if (isAutoGameNavigationSuppressed(matchId)) return
        _pendingGameNavigationMatchId.value = matchId
    }

    fun isAutoGameNavigationSuppressed(matchId: String): Boolean =
        autoGameNavigationSuppressedMatchId == matchId

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
                    match.status == MatchStatus.ACTIVE &&
                    match.isParticipant(uid)
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
    }

    /** Called when queue entry is confirmed (server or client join timestamp). */
    fun confirmQueueJoinedAt(joinedAtMs: Long) {
        if (!_matchmakingInProgress.value) return
        _hasQueueEntry.value = true
        _matchmakingInProgress.value = true
        mergeQueueJoinedAtMs(joinedAtMs)
        notifySessionStateChanged()
    }

    private fun mergeQueueJoinedAtMs(candidateMs: Long) {
        _queueJoinedAtMs.value = mergeQueueJoinedAtMs(_queueJoinedAtMs.value, candidateMs)
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
            _queueJoinedAtMs.value = null
            if (!_matchmakingInProgress.value) {
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
        if (error != null || snapshot == null || !snapshot.exists()) {
            _hasQueueEntry.value = false
            _queueJoinedAtMs.value = null
            return
        }
        if (!_matchmakingInProgress.value) {
            _hasQueueEntry.value = false
            _queueJoinedAtMs.value = null
            return
        }
        _hasQueueEntry.value = true
        resolveQueueJoinedAtMs(snapshot)?.let { mergeQueueJoinedAtMs(it) }
        if (snapshot.metadata.hasPendingWrites()) {
            return
        }
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
            (!fromCache || pendingLaunchMatchId == match.id)
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
        _queueJoinedAtMs.value = null
        notifySessionStateChanged()
    }

    /** Local fallback when the user leaves matchmaking or auth/session resets. */
    fun clearQueueState(endMatchmaking: Boolean = true) {
        _hasQueueEntry.value = false
        _queueJoinedAtMs.value = null
        if (endMatchmaking) {
            _matchmakingInProgress.value = false
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
