package com.rpsonline.app.ui.leaderboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rpsonline.app.R
import com.rpsonline.app.data.model.LeaderboardEntry
import com.rpsonline.app.data.model.UserProfile
import com.rpsonline.app.ui.components.HomeOutlinedButton
import com.rpsonline.app.ui.components.LocalOnlineUids
import com.rpsonline.app.ui.components.ProfileSummaryCard
import com.rpsonline.app.ui.components.ProvideOnlinePresencePolling
import com.rpsonline.app.ui.components.RpsLoadingColumn
import com.rpsonline.app.ui.components.rememberAllOnlineUids
import com.rpsonline.app.ui.components.rpsScreenPadding
import com.rpsonline.app.ui.theme.isRpsDarkTheme
import com.rpsonline.app.viewmodel.LeaderboardViewModel

private const val LeaderboardEntryContentType = 0

@Composable
private fun LeaderboardPresenceScope(
    onlineOnlyFilter: Boolean,
    entryUids: List<String>,
    onlineListUids: Set<String>,
    onOnlineMembershipChanged: (Set<String>) -> Unit,
    content: @Composable () -> Unit,
) {
    if (onlineOnlyFilter) {
        val allOnlineUids = rememberAllOnlineUids()
        LaunchedEffect(allOnlineUids) {
            onOnlineMembershipChanged(allOnlineUids)
        }
        CompositionLocalProvider(LocalOnlineUids provides onlineListUids) {
            content()
        }
    } else {
        ProvideOnlinePresencePolling(uids = entryUids) {
            content()
        }
    }
}

@Composable
fun LeaderboardScreen(
    onHome: () -> Unit,
    onPlayerProfile: (userId: String) -> Unit,
    viewModel: LeaderboardViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var onlineOnlyFilter by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }

    LaunchedEffect(onlineOnlyFilter) {
        listState.scrollToItem(0)
        if (!onlineOnlyFilter) {
            viewModel.onOnlineFilterDisabled()
        }
    }

    LaunchedEffect(
        listState.firstVisibleItemIndex,
        listState.layoutInfo.totalItemsCount,
        uiState.hasMore,
        uiState.isLoading,
        uiState.isAppending,
        uiState.entries.size,
        onlineOnlyFilter,
    ) {
        if (onlineOnlyFilter) return@LaunchedEffect
        if (!uiState.hasMore || uiState.isLoading || uiState.isAppending) return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        if (layoutInfo.totalItemsCount == 0) return@LaunchedEffect
        val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisibleIndex >= layoutInfo.totalItemsCount - 2) {
            viewModel.loadMore()
        }
    }

    Column(
        modifier = Modifier.rpsScreenPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.leaderboard),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.leaderboard_online_only),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Checkbox(
                    checked = onlineOnlyFilter,
                    onCheckedChange = { onlineOnlyFilter = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.colorScheme.outline,
                        checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        when {
            uiState.isLoading && !onlineOnlyFilter -> {
                RpsLoadingColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
            onlineOnlyFilter && uiState.isLoadingOnline && uiState.onlineEntries.isEmpty() -> {
                RpsLoadingColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
            uiState.error != null && (!onlineOnlyFilter || uiState.onlineEntries.isEmpty()) -> {
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
                LeaderboardPresenceScope(
                    onlineOnlyFilter = onlineOnlyFilter,
                    entryUids = uiState.entries.map { it.uid },
                    onlineListUids = remember(uiState.onlineEntries) {
                        uiState.onlineEntries.map { it.uid }.toSet()
                    },
                    onOnlineMembershipChanged = viewModel::syncOnlineEntries,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val displayedEntries = if (onlineOnlyFilter) {
                            uiState.onlineEntries
                        } else {
                            uiState.entries
                        }
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            state = listState,
                        ) {
                            itemsIndexed(
                                items = displayedEntries,
                                key = { _, entry -> entry.uid },
                                contentType = { _, _ -> LeaderboardEntryContentType },
                            ) { index, entry ->
                                LeaderboardListItem(
                                    rank = index + 1,
                                    entry = entry,
                                    isCurrentUser = entry.uid == uiState.currentUserId,
                                    onClick = { onPlayerProfile(entry.uid) },
                                )
                            }

                            item {
                                if (uiState.isAppending && !onlineOnlyFilter) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HomeOutlinedButton(onClick = onHome, label = stringResource(R.string.back_to_home))
    }
}

@Composable
private fun LeaderboardListItem(
    rank: Int,
    entry: LeaderboardEntry,
    isCurrentUser: Boolean,
    onClick: () -> Unit,
) {
    val nameLine = buildString {
        append("#$rank ${entry.displayName}")
        if (isCurrentUser) append(" · You")
    }
    val darkTheme = isRpsDarkTheme()
    val youColor = MaterialTheme.colorScheme.primary
    val otherStripeColor = MaterialTheme.colorScheme.outlineVariant
    val medalStripe = leaderboardPodiumStripeColor(rank, darkTheme)
    ProfileSummaryCard(
        displayName = nameLine,
        profile = entry.toUserProfile(),
        playerUid = entry.uid,
        nameColor = if (isCurrentUser) {
            null
        } else {
            leaderboardPodiumRankLabelColor(rank, darkTheme)
        },
        emphasized = isCurrentUser,
        accentStripeTop = when {
            isCurrentUser && medalStripe != null -> medalStripe
            isCurrentUser -> youColor
            medalStripe != null -> medalStripe
            else -> otherStripeColor
        },
        accentStripeBottom = when {
            isCurrentUser -> youColor
            medalStripe != null -> medalStripe
            else -> otherStripeColor
        },
        onClick = onClick,
    )
}

private fun LeaderboardEntry.toUserProfile(): UserProfile =
    UserProfile(
        uid = uid,
        displayName = displayName,
        elo = elo,
        wins = wins,
        losses = losses,
        draws = draws,
        roundsWon = roundsWon,
        roundsLost = roundsLost,
        roundsDraw = roundsDraw,
        throwsRock = throwsRock,
        throwsPaper = throwsPaper,
        throwsScissors = throwsScissors,
    )
