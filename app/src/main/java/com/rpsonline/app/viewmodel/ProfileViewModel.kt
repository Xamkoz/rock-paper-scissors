package com.rpsonline.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rpsonline.app.data.model.MatchHistoryEntry
import com.rpsonline.app.data.model.UserProfile
import com.rpsonline.app.data.repository.AuthRepository
import com.rpsonline.app.data.repository.MatchRepository
import com.rpsonline.app.data.repository.UserRepository
import com.rpsonline.app.domain.enrichMatchHistoryWithOpponentElos
import com.rpsonline.app.domain.DailyEloDelta
import com.rpsonline.app.domain.ProfileMatchQueries
import com.rpsonline.app.domain.weeklyEloDailyDeltas
import com.rpsonline.app.domain.weeklyChartWindowStartMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val isLoading: Boolean = true,
    val isMatchHistoryLoading: Boolean = true,
    val isWeeklyChartLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val profile: UserProfile? = null,
    val matchHistory: List<MatchHistoryEntry> = emptyList(),
    val weeklyEloChart: List<DailyEloDelta> = emptyList(),
    val hasMoreMatches: Boolean = true,
    val isOwnProfile: Boolean = false,
    val viewerDisplayName: String? = null,
    val error: String? = null,
    val matchHistoryError: String? = null,
)

class ProfileViewModel(
    private val authRepository: AuthRepository = AuthRepository(),
    private val userRepository: UserRepository = UserRepository(),
    private val matchRepository: MatchRepository = MatchRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var profileJob: Job? = null
    private var loadMoreJob: Job? = null
    private var loadedUserId: String? = null
    private var cachedViewerId: String? = null

    fun load(userId: String) {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        profileJob?.cancel()
        val isOwnProfile = userId == authRepository.currentUserId
        profileJob = viewModelScope.launch {
            userRepository.observeUserProfile(userId).collect { profile ->
                if (profile != null) {
                    _uiState.update { it.copy(profile = profile, error = null) }
                }
            }
        }
        loadJob = viewModelScope.launch {
            val switchingPlayer = loadedUserId != null && loadedUserId != userId
            val showFullScreenLoading = _uiState.value.profile == null || switchingPlayer
            if (switchingPlayer) {
                _uiState.update {
                    ProfileUiState(
                        isLoading = true,
                        isMatchHistoryLoading = true,
                        isWeeklyChartLoading = true,
                        isOwnProfile = isOwnProfile,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = showFullScreenLoading,
                        isMatchHistoryLoading = true,
                        isWeeklyChartLoading = true,
                        isOwnProfile = isOwnProfile,
                        matchHistoryError = null,
                        error = null,
                    )
                }
            }
            try {
                val viewerId = authRepository.currentUserId
                    ?: throw IllegalStateException("Not signed in")
                val profile = userRepository.getUserProfile(userId)
                    ?: throw IllegalStateException("Player not found")
                val viewerProfile = if (isOwnProfile) {
                    profile
                } else {
                    userRepository.getUserProfile(viewerId)
                }
                val viewerDisplayName = if (isOwnProfile) {
                    profile.displayName
                } else {
                    viewerProfile?.displayName ?: authRepository.currentUser?.displayName
                }
                val historyPerspectiveUserId = if (isOwnProfile) userId else viewerId
                val historyPerspectiveElo = if (isOwnProfile) profile.elo else (viewerProfile?.elo ?: 1000)
                cachedViewerId = viewerId
                val sinceMs = weeklyChartWindowStartMs()
                val matchPool = ProfileMatchQueries.fetchMatchPool(
                    matchRepository = matchRepository,
                    isOwnProfile = isOwnProfile,
                    viewerId = viewerId,
                    profileUserId = userId,
                    limit = PROFILE_MATCH_POOL_SIZE,
                )
                val weeklyMatches = if (isOwnProfile) {
                    ProfileMatchQueries.fetchOwnWeeklyMatchPool(
                        matchRepository = matchRepository,
                        viewerId = viewerId,
                        sinceMs = sinceMs,
                        limit = PROFILE_MATCH_POOL_SIZE,
                    )
                } else {
                    ProfileMatchQueries.fetchSharedWeeklyMatchPool(
                        matchRepository = matchRepository,
                        viewerId = viewerId,
                        profileUserId = userId,
                        sinceMs = sinceMs,
                        limit = PROFILE_MATCH_POOL_SIZE,
                    )
                }
                val weeklyEloChart = weeklyEloDailyDeltas(
                    enrichMatchHistoryWithOpponentElos(
                        viewerId = historyPerspectiveUserId,
                        myCurrentElo = historyPerspectiveElo,
                        matches = weeklyMatches,
                    ),
                )
                val historyMatches = matchPool.take(MATCH_HISTORY_PAGE_SIZE)
                // For other-player profiles, show shared data from the signed-in viewer perspective.
                val history = enrichMatchHistoryWithOpponentElos(
                    viewerId = historyPerspectiveUserId,
                    myCurrentElo = historyPerspectiveElo,
                    matches = historyMatches,
                )
                loadedUserId = userId
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isMatchHistoryLoading = false,
                        isWeeklyChartLoading = false,
                        isLoadingMore = false,
                        profile = profile,
                        matchHistory = history,
                        weeklyEloChart = weeklyEloChart,
                        hasMoreMatches = historyMatches.size >= MATCH_HISTORY_PAGE_SIZE,
                        isOwnProfile = isOwnProfile,
                        viewerDisplayName = viewerDisplayName,
                        matchHistoryError = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isMatchHistoryLoading = false,
                        isWeeklyChartLoading = false,
                        isLoadingMore = false,
                        error = e.message ?: "Failed to load profile",
                    )
                }
            }
        }
    }

    fun loadMoreMatchHistory() {
        val userId = loadedUserId ?: return
        val currentViewerId = cachedViewerId ?: return
        val state = _uiState.value
        if (
            state.isMatchHistoryLoading ||
            state.isLoadingMore ||
            !state.hasMoreMatches ||
            state.profile == null
        ) {
            return
        }

        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, matchHistoryError = null) }
            try {
                val profile = state.profile ?: return@launch
                val nextLimit = state.matchHistory.size + MATCH_HISTORY_PAGE_SIZE
                val matches = ProfileMatchQueries.fetchMatchPool(
                    matchRepository = matchRepository,
                    isOwnProfile = state.isOwnProfile,
                    viewerId = currentViewerId,
                    profileUserId = userId,
                    limit = nextLimit,
                )
                val historyPerspectiveUserId = if (state.isOwnProfile) userId else currentViewerId
                val historyPerspectiveElo = if (state.isOwnProfile) {
                    profile.elo
                } else {
                    userRepository.getUserProfile(currentViewerId)?.elo ?: 1000
                }
                val history = enrichMatchHistoryWithOpponentElos(
                    viewerId = historyPerspectiveUserId,
                    myCurrentElo = historyPerspectiveElo,
                    matches = matches,
                )
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        matchHistory = history,
                        hasMoreMatches = matches.size >= nextLimit,
                        matchHistoryError = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        matchHistoryError = e.message ?: "Couldn't load more matches",
                    )
                }
            }
        }
    }

    override fun onCleared() {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        profileJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MATCH_HISTORY_PAGE_SIZE = 10

        /**
         * Per-side Firestore cap when loading profile matches. The weekly chart only
         * includes the last seven days from this pool; very active players may have
         * incomplete chart data beyond this count.
         */
        const val PROFILE_MATCH_POOL_SIZE = ProfileMatchQueries.MATCH_POOL_SIZE
    }
}
