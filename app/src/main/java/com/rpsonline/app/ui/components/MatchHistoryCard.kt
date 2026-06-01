package com.rpsonline.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpsonline.app.R
import com.rpsonline.app.data.model.MatchHistoryEntry

@Composable
fun MatchHistoryCard(
    entry: MatchHistoryEntry,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    onDismiss: (() -> Unit)? = null,
    onMyProfile: ((String) -> Unit)? = null,
    onOpponentProfile: ((String) -> Unit)? = null,
    contentVerticalPadding: Dp = 8.dp,
    contentTopPadding: Dp? = null,
    compactHeader: Boolean = false,
    matchHeaderTopPadding: Dp = 0.dp,
    titleStyle: TextStyle = MaterialTheme.typography.titleSmall,
) {
    val headerSpacing = if (compactHeader) 2.dp else 4.dp
    RpsCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 10.dp,
                    end = 10.dp,
                    top = contentTopPadding ?: contentVerticalPadding,
                    bottom = contentVerticalPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(headerSpacing),
        ) {
            if (title != null || subtitle != null || onDismiss != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = if (compactHeader) Alignment.Top else Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (title != null) {
                            Text(
                                text = title,
                                style = titleStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            )
                        }
                    }
                    if (onDismiss != null) {
                        CompositionLocalProvider(
                            LocalMinimumInteractiveComponentSize provides 0.dp,
                        ) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = if (compactHeader) {
                                    Modifier.size(28.dp)
                                } else {
                                    Modifier
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.dismiss_highlighted_match),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = if (compactHeader) Modifier.size(18.dp) else Modifier,
                                )
                            }
                        }
                    }
                }
            }
            MatchHistoryCardHeader(
                entry = entry,
                lastActivityAt = entry.lastActivityAt,
                modifier = Modifier.padding(top = matchHeaderTopPadding),
                onMyProfile = onMyProfile,
                onOpponentProfile = onOpponentProfile,
            )
            if (entry.recaps.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MatchRecapCard(
                    recaps = entry.recaps,
                    title = null,
                    embedded = true,
                )
            }
        }
    }
}
