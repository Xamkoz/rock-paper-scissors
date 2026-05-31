package com.rpsonline.app.ui.opponents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rpsonline.app.R
import com.rpsonline.app.domain.WeeklyOpponentRow
import com.rpsonline.app.ui.components.HomeOutlinedButton
import com.rpsonline.app.ui.components.PlayerDisplayNameText
import com.rpsonline.app.ui.components.ProvideOnlinePresence
import com.rpsonline.app.ui.components.RpsCard
import com.rpsonline.app.ui.components.RpsLoadingColumn
import com.rpsonline.app.ui.components.formatEloDelta
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

    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }

    ProvideOnlinePresence(uids = uiState.opponents.map { it.opponentUid }) {
        Column(modifier = Modifier.rpsScreenPadding()) {
            Text(
                text = stringResource(R.string.my_opponents),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.my_opponents_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            when {
                uiState.isLoading -> {
                    RpsLoadingColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
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
                uiState.opponents.isEmpty() -> {
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
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = uiState.opponents,
                            key = { it.opponentUid },
                        ) { opponent ->
                            OpponentListItem(
                                opponent = opponent,
                                onClick = { onPlayerProfile(opponent.opponentUid) },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HomeOutlinedButton(onClick = onHome, label = stringResource(R.string.back_to_home))
        }
    }
}

@Composable
private fun OpponentListItem(
    opponent: WeeklyOpponentRow,
    onClick: () -> Unit,
) {
    val rowStyle = onlinePresenceRowStyle(uid = opponent.opponentUid)
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
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PlayerDisplayNameText(
                    name = opponent.displayName,
                    uid = opponent.opponentUid,
                    style = MaterialTheme.typography.titleMedium,
                    defaultColor = rowStyle.nameColor,
                    onClick = onClick,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatEloDelta(opponent.weeklyEloDelta),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = when {
                        opponent.weeklyEloDelta > 0 -> MaterialTheme.colorScheme.primary
                        opponent.weeklyEloDelta < 0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}
