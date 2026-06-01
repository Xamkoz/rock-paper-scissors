package com.rpsonline.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rpsonline.app.R
import com.rpsonline.app.data.update.AppUpdateInfo
import com.rpsonline.app.ui.components.RpsCard
import com.rpsonline.app.ui.components.RpsCompactOutlinedActionButton
import com.rpsonline.app.ui.components.RpsOutlinedBorderWidth
import com.rpsonline.app.ui.components.RpsOutlinedSurfaceStyle

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
    val contentColor = RpsOutlinedSurfaceStyle.contentColor()

    RpsCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = RpsOutlinedSurfaceStyle.containerColor(),
        borderColor = RpsOutlinedSurfaceStyle.borderColor(),
        borderWidth = RpsOutlinedBorderWidth,
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
                        color = contentColor,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            if (showUpdateAction) {
                when {
                    isCheckingForUpdate -> {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .size(24.dp),
                            strokeWidth = 2.dp,
                            color = contentColor,
                        )
                    }
                    pendingUpdate != null -> {
                        RpsCompactOutlinedActionButton(
                            onClick = onInstallUpdate,
                            text = stringResource(R.string.update),
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    else -> {
                        RpsCompactOutlinedActionButton(
                            onClick = onCheckForUpdate,
                            text = stringResource(R.string.check),
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
