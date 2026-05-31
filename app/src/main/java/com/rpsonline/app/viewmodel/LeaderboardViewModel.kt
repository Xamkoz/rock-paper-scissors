package com.rpsonline.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rpsonline.app.data.model.LeaderboardEntry
import com.google.firebase.firestore.DocumentSnapshot
import com.rpsonline.app.data.repository.AuthRepository
import com.rpsonline.app.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LeaderboardUiState(
    val isLoading: Boolean = true,
    val isAppending: Boolean = false,
    val entries: List<LeaderboardEntry> = emptyList(),
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

    private var nextCursor: DocumentSnapshot? = null
    private var hasMoreFromFirestore: Boolean = true

    private companion object {
        private const val PAGE_SIZE = 25L
    }

    private fun resetPagination() {
        nextCursor = null
        hasMoreFromFirestore = true
    }

    private fun appendEntries(existing: List<LeaderboardEntry>, newEntries: List<LeaderboardEntry>): List<LeaderboardEntry> =
        (existing + newEntries).distinctBy { it.uid }

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
