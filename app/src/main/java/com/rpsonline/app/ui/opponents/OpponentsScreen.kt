package com.rpsonline.app.ui.opponents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rpsonline.app.R
import com.rpsonline.app.domain.WeeklyOpponentRow
import com.rpsonline.app.ui.components.HomeOutlinedButton
import com.rpsonline.app.ui.components.LocalOnlineUids
import com.rpsonline.app.ui.components.OnlineOnlyFilterControl
import com.rpsonline.app.data.preferences.OnlineFilterScreen
import com.rpsonline.app.ui.components.rememberPersistedOnlineOnlyFilter
import com.rpsonline.app.ui.components.rememberOnlineUidsPollSnapshot
import com.rpsonline.app.ui.components.RpsCard
import com.rpsonline.app.ui.components.RpsLoadingColumn
import com.rpsonline.app.ui.components.formatEloDelta
import com.rpsonline.app.ui.components.formatEloDeltaOneDecimal
import com.rpsonline.app.ui.components.eloDeltaColor
import com.rpsonline.app.ui.components.onlinePresenceRowStyle
import com.rpsonline.app.ui.components.rpsScreenPadding
import com.rpsonline.app.viewmodel.OpponentsViewModel

@Composable
fun OpponentsScreen(
    onHome: () -> Unit,
    onPlayerProfile: (userId: String) -> Unit,
    viewModel: OpponentsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val (onlineOnlyFilter, setOnlineOnlyFilter) = rememberPersistedOnlineOnlyFilter(
        OnlineFilterScreen.OPPONENTS,
    )
    val opponentUids = remember(uiState.opponents) { uiState.opponents.map { it.opponentUid } }
    val presence = rememberOnlineUidsPollSnapshot(opponentUids)
    val displayedOpponents = remember(uiState.opponents, onlineOnlyFilter, presence.onlineUids) {
        if (onlineOnlyFilter) {
            uiState.opponents.filter { it.opponentUid in presence.onlineUids }
        } else {
            uiState.opponents
        }
    }
    val showInitialLoad = uiState.isLoading && uiState.opponents.isEmpty()
    val showOnlineFilterInitialLoad = onlineOnlyFilter &&
        uiState.opponents.isNotEmpty() &&
        !presence.hasPolled

    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }

    Column(modifier = Modifier.rpsScreenPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.my_opponents),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            OnlineOnlyFilterControl(
                checked = onlineOnlyFilter,
                onCheckedChange = setOnlineOnlyFilter,
            )
        }
        Text(
            text = stringResource(R.string.my_opponents_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        when {
            uiState.error != null -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = uiState.error!!,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            uiState.opponents.isEmpty() && !uiState.isLoading -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.no_opponents_this_week),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                Box(modifier = Modifier.weight(1f)) {
                    if (displayedOpponents.isEmpty() && onlineOnlyFilter && presence.hasPolled) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.no_online_opponents_this_week),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        CompositionLocalProvider(LocalOnlineUids provides presence.onlineUids) {
                            val maxGain = remember(displayedOpponents) {
                                displayedOpponents.maxOfOrNull { it.weeklyEloDelta.coerceAtLeast(0) }
                                    ?.coerceAtLeast(1) ?: 1
                            }
                            val maxLoss = remember(displayedOpponents) {
                                displayedOpponents.maxOfOrNull { (-it.weeklyEloDelta).coerceAtLeast(0) }
                                    ?.coerceAtLeast(1) ?: 1
                            }
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                items(
                                    items = displayedOpponents,
                                    key = { it.opponentUid },
                                ) { opponent ->
                                    OpponentListItem(
                                        opponent = opponent,
                                        maxGain = maxGain,
                                        maxLoss = maxLoss,
                                        onClick = { onPlayerProfile(opponent.opponentUid) },
                                    )
                                }
                            }
                        }
                    }
                    if (showInitialLoad || showOnlineFilterInitialLoad) {
                        RpsLoadingColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HomeOutlinedButton(onClick = onHome, label = stringResource(R.string.back_to_home))
    }
}

@Composable
private fun OpponentListItem(
    opponent: WeeklyOpponentRow,
    maxGain: Int,
    maxLoss: Int,
    onClick: () -> Unit,
) {
    val rowStyle = onlinePresenceRowStyle(uid = opponent.opponentUid)
    val nameColor = MaterialTheme.colorScheme.onSurface
    RpsCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        containerColor = rowStyle.containerColor,
        borderColor = rowStyle.borderColor,
        borderWidth = rowStyle.borderWidth,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            rowStyle.accentStripeColor?.let { stripeColor ->
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(stripeColor),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                OpponentRowEloBars(
                    weeklyEloDelta = opponent.weeklyEloDelta,
                    maxGain = maxGain,
                    maxLoss = maxLoss,
                    modifier = Modifier.matchParentSize(),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = opponent.displayName,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(onClick = onClick),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = nameColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.elo_change_with_delta,
                                formatEloDelta(opponent.weeklyEloDelta),
                            ),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = eloDeltaColor(opponent.weeklyEloDelta),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    if (opponent.matchCount > 0) {
                        OpponentEloPerMatchSummaryLine(
                            avgEloDelta = opponent.avgMyEloDeltaPerMatch(),
                            matchCount = opponent.matchCount,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OpponentEloPerMatchSummaryLine(
    avgEloDelta: Double,
    matchCount: Int,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val matchCountColor = MaterialTheme.colorScheme.primary
    val textStyle = MaterialTheme.typography.bodyMedium
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${formatEloDeltaOneDecimal(avgEloDelta)} ",
            style = textStyle.copy(fontWeight = FontWeight.SemiBold),
            color = eloDeltaColor(avgEloDelta),
        )
        Text(
            text = stringResource(R.string.opponent_elo_avg_in),
            style = textStyle,
            color = muted,
        )
        Text(
            text = matchCount.toString(),
            style = textStyle.copy(fontWeight = FontWeight.SemiBold),
            color = matchCountColor,
        )
        Text(
            text = " ${pluralStringResource(R.plurals.opponent_match_count_label, matchCount)}",
            style = textStyle,
            color = muted,
        )
    }
}

@Composable
private fun OpponentRowEloBars(
    weeklyEloDelta: Int,
    maxGain: Int,
    maxLoss: Int,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val gainColor = scheme.primary.copy(alpha = 0.34f)
    val lossColor = scheme.error.copy(alpha = 0.34f)
    val gainFraction = if (weeklyEloDelta > 0 && maxGain > 0) {
        weeklyEloDelta.toFloat() / maxGain.toFloat()
    } else {
        0f
    }
    val lossFraction = if (weeklyEloDelta < 0 && maxLoss > 0) {
        abs(weeklyEloDelta).toFloat() / maxLoss.toFloat()
    } else {
        0f
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (lossFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(lossFraction.coerceIn(0f, 1f))
                        .background(lossColor),
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (gainFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(gainFraction.coerceIn(0f, 1f))
                        .background(gainColor),
                )
            }
        }
    }
}
