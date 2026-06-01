package com.rpsonline.app.ui.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rpsonline.app.data.model.MatchHistoryEntry
import com.rpsonline.app.R
import com.rpsonline.app.domain.DisplayNames
import com.rpsonline.app.ui.components.HomeOutlinedButton
import com.rpsonline.app.ui.components.MatchHistoryCard
import com.rpsonline.app.ui.components.MatchHistoryLoadingSection
import com.rpsonline.app.ui.components.ProfileSummaryCard
import com.rpsonline.app.ui.components.ProvideOnlinePresence
import com.rpsonline.app.ui.components.RpsLoadingColumn
import com.rpsonline.app.ui.components.rpsScreenPadding
import com.rpsonline.app.viewmodel.ProfileViewModel

@Composable
fun ProfileScreen(
    userId: String,
    onHome: () -> Unit,
    onPlayerProfile: (String) -> Unit = {},
    viewModel: ProfileViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LifecycleResumeEffect(userId) {
        viewModel.load(userId)
        onPauseOrDispose { }
    }

    LaunchedEffect(
        listState.firstVisibleItemIndex,
        listState.layoutInfo.totalItemsCount,
        uiState.hasMoreMatches,
        uiState.isMatchHistoryLoading,
        uiState.isLoadingMore,
    ) {
        if (!uiState.hasMoreMatches || uiState.isMatchHistoryLoading || uiState.isLoadingMore) return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        if (layoutInfo.totalItemsCount == 0) return@LaunchedEffect
        val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisibleIndex >= layoutInfo.totalItemsCount - 2) {
            viewModel.loadMoreMatchHistory()
        }
    }

    val presenceUids = buildSet {
        add(userId)
        uiState.matchHistory.forEach { entry ->
            if (entry.myUid.isNotBlank()) add(entry.myUid)
            if (entry.opponentUid.isNotBlank()) add(entry.opponentUid)
        }
    }

    ProvideOnlinePresence(uids = presenceUids) {
        Column(modifier = Modifier.rpsScreenPadding()) {
        Text(
            text = profileTitle(
                displayName = uiState.profile?.displayName,
                isOwnProfile = uiState.isOwnProfile,
            ),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(12.dp))

        when {
            uiState.isLoading && uiState.profile == null -> {
                RpsLoadingColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    message = stringResource(R.string.loading_profile),
                )
            }
            uiState.error != null -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(text = uiState.error!!, color = MaterialTheme.colorScheme.error)
                }
            }
            else -> {
                val profile = uiState.profile
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        ProfileSummaryCard(
                            displayName = profile?.displayName ?: DisplayNames.DEFAULT,
                            profile = profile,
                            playerUid = userId,
                            emphasized = uiState.isOwnProfile,
                            onClick = if (uiState.isOwnProfile) null else ({ onPlayerProfile(userId) }),
                        )
                    }
                    item {
                        val sharedMatchupLabel = if (!uiState.isOwnProfile) {
                            val viewerName = uiState.viewerDisplayName
                            val opponentName = profile?.displayName
                            if (!viewerName.isNullOrBlank() && !opponentName.isNullOrBlank()) {
                                "$viewerName vs $opponentName"
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                        WeeklyEloGainLossChart(
                            days = uiState.weeklyEloChart,
                            isLoading = uiState.isWeeklyChartLoading,
                            sharedMatchesOnly = !uiState.isOwnProfile,
                            sharedMatchupLabel = sharedMatchupLabel,
                        )
                    }
                    if (uiState.isMatchHistoryLoading && uiState.matchHistory.isEmpty()) {
                        item {
                            MatchHistoryLoadingSection()
                        }
                    } else if (uiState.matchHistory.isEmpty()) {
                        item {
                            Text(
                                text = if (uiState.isOwnProfile) {
                                    "No completed matches yet."
                                } else {
                                    "No shared matches yet."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(
                            items = uiState.matchHistory,
                            key = { it.matchId },
                        ) { entry ->
                            MatchHistoryCard(
                                entry = entry,
                                onMyProfile = onPlayerProfile,
                                onOpponentProfile = onPlayerProfile,
                            )
                        }
                        if (uiState.isLoadingMore) {
                            item(key = "loading_more_matches") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                        uiState.matchHistoryError?.let { message ->
                            item(key = "match_history_error") {
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HomeOutlinedButton(
            onClick = onHome,
            label = stringResource(R.string.back_to_home),
        )
        }
    }
}

@Composable
private fun profileTitle(displayName: String?, isOwnProfile: Boolean): String {
    val base = displayName ?: stringResource(R.string.profile)
    return if (isOwnProfile) {
        stringResource(R.string.profile_title_own, base)
    } else {
        base
    }
}
