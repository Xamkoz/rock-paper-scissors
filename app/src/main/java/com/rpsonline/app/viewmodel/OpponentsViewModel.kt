package com.rpsonline.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rpsonline.app.data.repository.AuthRepository
import com.rpsonline.app.data.repository.MatchRepository
import com.rpsonline.app.domain.ProfileMatchQueries
import com.rpsonline.app.domain.WeeklyOpponentRow
import com.rpsonline.app.domain.weeklyChartWindowStartMs
import com.rpsonline.app.domain.weeklyOpponentsFromMatchList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OpponentsUiState(
    val isLoading: Boolean = true,
    val opponents: List<WeeklyOpponentRow> = emptyList(),
    val error: String? = null,
)

class OpponentsViewModel(
    private val authRepository: AuthRepository = AuthRepository(),
    private val matchRepository: MatchRepository = MatchRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(OpponentsUiState())
    val uiState: StateFlow<OpponentsUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val showBlockingLoader = _uiState.value.opponents.isEmpty()
            if (showBlockingLoader) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            try {
                val viewerId = authRepository.currentUserId
                    ?: throw IllegalStateException("Not signed in")
                val sinceMs = weeklyChartWindowStartMs()
                val weeklyMatches = ProfileMatchQueries.fetchOwnWeeklyMatchPool(
                    matchRepository = matchRepository,
                    viewerId = viewerId,
                    sinceMs = sinceMs,
                    limit = ProfileMatchQueries.OPPONENTS_MATCH_POOL_SIZE,
                )
                val opponents = weeklyOpponentsFromMatchList(
                    matches = weeklyMatches,
                    viewerId = viewerId,
                    sinceMs = sinceMs,
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        opponents = opponents,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load opponents",
                    )
                }
            }
        }
    }
}
