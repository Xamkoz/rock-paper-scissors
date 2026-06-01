package com.rpsonline.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rpsonline.app.R
import com.rpsonline.app.data.update.AppUpdateInfo
import com.rpsonline.app.ui.components.RpsCard

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeAppInfoFooter(
    versionName: String,
    updatesEnabled: Boolean,
    availableUpdate: AppUpdateInfo?,
    isCheckingForUpdate: Boolean,
    isDownloadingUpdate: Boolean,
    updateMessage: String?,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onVersionClick: () -> Unit = {},
    onVersionLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (versionName.isBlank() && !updatesEnabled) return

    val showUpdateAction = updatesEnabled && !isDownloadingUpdate
    val pendingUpdate = availableUpdate

    RpsCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = if (pendingUpdate != null) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.94f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.88f)
        },
        borderColor = if (pendingUpdate != null) {
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (versionName.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.version_label, versionName),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.combinedClickable(
                            onClick = onVersionClick,
                            onLongClick = onVersionLongClick,
                        ),
                    )
                }
                val statusText = when {
                    isDownloadingUpdate -> stringResource(R.string.downloading_update)
                    pendingUpdate != null -> stringResource(
                        R.string.version_available,
                        pendingUpdate.versionLabel,
                    )
                    !updateMessage.isNullOrBlank() -> updateMessage
                    updatesEnabled -> stringResource(R.string.installed_from_github)
                    else -> null
                }
                statusText?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pendingUpdate != null) {
                            MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            if (showUpdateAction) {
                when {
                    isCheckingForUpdate -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    pendingUpdate != null -> {
                        FilledTonalButton(
                            onClick = onInstallUpdate,
                            modifier = Modifier.padding(start = 12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.SystemUpdate,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.update))
                            }
                        }
                    }
                    else -> {
                        TextButton(
                            onClick = onCheckForUpdate,
                            modifier = Modifier.padding(start = 4.dp),
                        ) {
                            Text(stringResource(R.string.check))
                        }
                    }
                }
            }
        }
    }
}
