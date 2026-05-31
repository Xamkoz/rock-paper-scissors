package com.rpsonline.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rpsonline.app.data.model.LeaderboardEntry
import com.google.firebase.firestore.DocumentSnapshot
import com.rpsonline.app.data.repository.AuthRepository
import com.rpsonline.app.data.repository.UserRepository
import com.rpsonline.app.domain.DisplayNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LeaderboardUiState(
    val isLoading: Boolean = true,
    val isLoadingOnline: Boolean = false,
    val isAppending: Boolean = false,
    val entries: List<LeaderboardEntry> = emptyList(),
    val onlineEntries: List<LeaderboardEntry> = emptyList(),
    val currentUserId: String? = null,
    val error: String? = null,
    val hasMore: Boolean = true,
)

class LeaderboardViewModel(
    private val userRepository: UserRepository = UserRepository(),
    private val authRepository: AuthRepository = AuthRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        LeaderboardUiState(currentUserId = authRepository.currentUserId),
    )
    val uiState: StateFlow<LeaderboardUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var appendJob: Job? = null
    private var onlineLoadJob: Job? = null

    private var nextCursor: DocumentSnapshot? = null
    private var hasMoreFromFirestore: Boolean = true
    private var cachedOnlineUids: Set<String> = emptySet()
    private val onlineEntryCache = mutableMapOf<String, LeaderboardEntry>()
    private var pendingOnlineUids: Set<String>? = null

    private companion object {
        private const val PAGE_SIZE = 25L
        private const val ONLINE_SYNC_DEBOUNCE_MS = 500L
    }

    private fun resetPagination() {
        nextCursor = null
        hasMoreFromFirestore = true
    }

    private fun appendEntries(existing: List<LeaderboardEntry>, newEntries: List<LeaderboardEntry>): List<LeaderboardEntry> =
        (existing + newEntries).distinctBy { it.uid }

    private fun sortedOnlineEntries(uids: Set<String>): List<LeaderboardEntry> =
        uids
            .map { uid -> onlineEntryCache[uid] ?: placeholderOnlineEntry(uid) }
            .sortedWith(
                compareByDescending<LeaderboardEntry> { it.elo }
                    .thenBy { it.displayName.lowercase() },
            )

    private fun placeholderOnlineEntry(uid: String): LeaderboardEntry =
        LeaderboardEntry(
            uid = uid,
            displayName = DisplayNames.resolve(null, uid),
            elo = 1000,
        )

    private fun missingOnlineCacheUids(uids: Set<String>): Set<String> =
        uids.filter { it !in onlineEntryCache }.toSet()

    fun onOnlineFilterDisabled() {
        onlineLoadJob?.cancel()
        pendingOnlineUids = null
        cachedOnlineUids = emptySet()
        onlineEntryCache.clear()
        _uiState.update { it.copy(onlineEntries = emptyList(), isLoadingOnline = false) }
    }

    fun syncOnlineEntries(onlineUids: Set<String>) {
        val uids = onlineUids.filter { it.isNotBlank() }.toSet()
        if (uids.isEmpty()) return

        val missing = missingOnlineCacheUids(uids)
        if (uids == cachedOnlineUids && missing.isEmpty() && _uiState.value.onlineEntries.isNotEmpty()) {
            return
        }

        pendingOnlineUids = uids
        onlineLoadJob?.cancel()
        onlineLoadJob = viewModelScope.launch {
            val debounceMs = if (_uiState.value.onlineEntries.isEmpty()) 0L else ONLINE_SYNC_DEBOUNCE_MS
            delay(debounceMs)

            val latestUids = pendingOnlineUids ?: return@launch
            if (latestUids.isEmpty()) return@launch

            val latestMissing = missingOnlineCacheUids(latestUids)
            if (
                latestUids == cachedOnlineUids &&
                latestMissing.isEmpty() &&
                _uiState.value.onlineEntries.isNotEmpty()
            ) {
                return@launch
            }

            val removed = onlineEntryCache.keys - latestUids
            if (removed.isNotEmpty()) {
                removed.forEach { onlineEntryCache.remove(it) }
            }

            if (latestMissing.isNotEmpty() && _uiState.value.onlineEntries.isEmpty()) {
                _uiState.update { it.copy(isLoadingOnline = true, error = null) }
            }

            try {
                if (latestMissing.isNotEmpty()) {
                    userRepository.getLeaderboardEntriesForUids(latestMissing).forEach { entry ->
                        onlineEntryCache[entry.uid] = entry
                    }
                }
                val entries = sortedOnlineEntries(latestUids)
                if (entries.isEmpty() && latestUids.isNotEmpty()) {
                    cachedOnlineUids = emptySet()
                    _uiState.update { it.copy(isLoadingOnline = false) }
                    return@launch
                }
                cachedOnlineUids = latestUids
                _uiState.update { state ->
                    if (state.onlineEntries == entries && !state.isLoadingOnline) {
                        state
                    } else {
                        state.copy(
                            onlineEntries = entries,
                            isLoadingOnline = false,
                            currentUserId = authRepository.currentUserId,
                            error = null,
                        )
                    }
                }
            } catch (e: Exception) {
                cachedOnlineUids = emptySet()
                _uiState.update {
                    it.copy(
                        isLoadingOnline = false,
                        error = e.message,
                    )
                }
            }
        }
    }

    fun load() {
        loadJob?.cancel()
        appendJob?.cancel()

        val hasCachedEntries = _uiState.value.entries.isNotEmpty()

        loadJob = viewModelScope.launch {
            if (!hasCachedEntries) {
                resetPagination()
                _uiState.update {
                    it.copy(
                        isLoading = true,
                        isAppending = false,
                        error = null,
                        hasMore = true,
                    )
                }
                userRepository.getLeaderboardPageFromCache(PAGE_SIZE)?.let { cached ->
                    nextCursor = cached.nextCursor
                    hasMoreFromFirestore = cached.hasMoreFromFirestore
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            entries = cached.entries,
                            currentUserId = authRepository.currentUserId,
                            hasMore = cached.hasMore,
                        )
                    }
                }
            } else {
                _uiState.update { it.copy(error = null) }
            }

            try {
                val userId = authRepository.currentUserId
                val page = userRepository.getLeaderboardPage(
                    pageSize = PAGE_SIZE,
                    startAfter = null,
                )
                if (hasCachedEntries) {
                    val tail = _uiState.value.entries
                        .drop(page.entries.size)
                        .filter { tailEntry -> page.entries.none { it.uid == tailEntry.uid } }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            entries = appendEntries(page.entries, tail),
                            currentUserId = userId,
                        )
                    }
                } else {
                    nextCursor = page.nextCursor
                    hasMoreFromFirestore = page.hasMoreFromFirestore
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            entries = page.entries,
                            currentUserId = userId,
                            hasMore = page.hasMore,
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isAppending = false,
                        error = e.message,
                    )
                }
            }
        }
    }

    fun loadMore() {
        if (_uiState.value.isLoading || _uiState.value.isAppending || !_uiState.value.hasMore) return

        appendJob?.cancel()
        appendJob = viewModelScope.launch {
            _uiState.update { it.copy(isAppending = true, error = null) }
            try {
                if (!hasMoreFromFirestore) {
                    _uiState.update { it.copy(isAppending = false, hasMore = false) }
                    return@launch
                }

                val page = userRepository.getLeaderboardPage(
                    pageSize = PAGE_SIZE,
                    startAfter = nextCursor,
                )
                nextCursor = page.nextCursor
                hasMoreFromFirestore = page.hasMoreFromFirestore

                _uiState.update {
                    it.copy(
                        isAppending = false,
                        entries = appendEntries(it.entries, page.entries),
                        hasMore = page.hasMore,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isAppending = false, error = e.message) }
            }
        }
    }
}
