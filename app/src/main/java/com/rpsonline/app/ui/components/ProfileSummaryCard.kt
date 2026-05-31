package com.rpsonline.app.ui.components

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rpsonline.app.R
import com.rpsonline.app.data.model.UserProfile
import com.rpsonline.app.domain.DisplayNames
import com.rpsonline.app.ui.leaderboard.ThrowDistributionRadialChart

private val SummaryRowHorizontalPadding = 10.dp
private val SummaryRowVerticalPadding = 8.dp
private val SummaryStatsLinesGap = 1.dp
private val ProfileCardAccentWidth = 8.dp

/** Profile summary title for the signed-in user, e.g. "Playername (you)". */
@Composable
fun ownProfileDisplayName(displayName: String?): String {
    val base = displayName?.takeIf { it.isNotBlank() } ?: DisplayNames.DEFAULT
    return stringResource(R.string.profile_title_own, base)
}

@Composable
fun ProfileSummaryCard(
    displayName: String,
    profile: UserProfile?,
    modifier: Modifier = Modifier,
    eloOverride: Int? = null,
    nameColor: Color? = null,
    playerUid: String? = null,
    onClick: (() -> Unit)? = null,
    emphasized: Boolean = false,
    accentStripeTop: Color? = null,
    accentStripeBottom: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val youColor = scheme.primary
    val otherStripeColor = scheme.outlineVariant
    val rowStyle = onlinePresenceRowStyle(
        uid = playerUid,
        emphasized = emphasized,
    )
    val containerColor = rowStyle.containerColor
    val borderColor = when {
        emphasized -> youColor.copy(alpha = 0.82f)
        rowStyle.isOnline -> rowStyle.borderColor
        onClick != null -> scheme.outline.copy(alpha = 0.55f)
        else -> scheme.outline.copy(alpha = 0.55f)
    }
    val borderWidth = when {
        emphasized || rowStyle.isOnline -> 2.dp
        onClick != null -> 2.dp
        else -> 1.dp
    }
    val stripeTop = accentStripeTop ?: when {
        emphasized -> youColor
        rowStyle.isOnline -> rowStyle.accentStripeColor ?: youColor
        onClick != null -> otherStripeColor
        else -> null
    }
    val stripeBottom = accentStripeBottom ?: accentStripeTop ?: when {
        emphasized -> youColor
        rowStyle.isOnline -> rowStyle.accentStripeColor ?: youColor
        onClick != null -> otherStripeColor
        else -> null
    }
    val resolvedNameColor = when {
        emphasized -> youColor
        rowStyle.isOnline -> rowStyle.nameColor
        nameColor != null -> nameColor
        else -> scheme.onSurface
    }
    val contentDescription = if (onClick != null) {
        "$displayName. ${stringResource(R.string.profile)}"
    } else {
        displayName
    }

    RpsCard(
        modifier = modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = contentDescription },
        onClick = onClick,
        containerColor = containerColor,
        borderColor = borderColor,
        borderWidth = borderWidth,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (stripeTop != null && stripeBottom != null) {
                ProfileSummaryAccentStripe(
                    topColor = stripeTop,
                    bottomColor = stripeBottom,
                )
            }
            PlayerSummaryContent(
                nameLine = displayName,
                nameColor = resolvedNameColor,
                wins = profile?.wins ?: 0,
                losses = profile?.losses ?: 0,
                draws = profile?.draws ?: 0,
                roundsWon = profile?.roundsWon ?: 0,
                roundsLost = profile?.roundsLost ?: 0,
                roundsDraw = profile?.roundsDraw ?: 0,
                throwsRock = profile?.throwsRock ?: 0,
                throwsPaper = profile?.throwsPaper ?: 0,
                throwsScissors = profile?.throwsScissors ?: 0,
                elo = eloOverride ?: profile?.elo ?: 1000,
            )
        }
    }
}

@Composable
private fun ProfileSummaryAccentStripe(
    topColor: Color,
    bottomColor: Color,
) {
    if (topColor == bottomColor) {
        Box(
            modifier = Modifier
                .width(ProfileCardAccentWidth)
                .fillMaxHeight()
                .background(topColor),
        )
        return
    }
    Column(
        modifier = Modifier
            .width(ProfileCardAccentWidth)
            .fillMaxHeight(),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(topColor),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(bottomColor),
        )
    }
}

@Composable
fun PlayerSummaryContent(
    nameLine: String,
    nameColor: Color,
    wins: Int,
    losses: Int,
    draws: Int,
    roundsWon: Int,
    roundsLost: Int,
    roundsDraw: Int,
    throwsRock: Int,
    throwsPaper: Int,
    throwsScissors: Int,
    elo: Int,
    modifier: Modifier = Modifier,
    nameTextStyle: TextStyle = MaterialTheme.typography.titleMedium,
    statTextStyle: TextStyle = MaterialTheme.typography.bodySmall,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = SummaryRowHorizontalPadding,
                vertical = SummaryRowVerticalPadding,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = nameLine,
                style = nameTextStyle,
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            WinLossStatLine(
                wins = wins,
                losses = losses,
                draws = draws,
                textStyle = statTextStyle,
            )
            Spacer(modifier = Modifier.height(SummaryStatsLinesGap))
            RoundWinRateLine(
                wins = roundsWon,
                losses = roundsLost,
                draws = roundsDraw,
                textStyle = statTextStyle,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThrowDistributionRadialChart(
                rock = throwsRock,
                paper = throwsPaper,
                scissors = throwsScissors,
                size = 56.dp,
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                EloRatingText(
                    elo = elo,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .widthIn(min = 40.dp)
                        .offset(y = (-1).dp),
                )
                Text(
                    text = "ELO",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.offset(y = (-1).dp),
                )
            }
        }
    }
}
