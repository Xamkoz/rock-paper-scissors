package com.rpsonline.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rpsonline.app.R
import com.rpsonline.app.data.update.ReleaseChangelog
import com.rpsonline.app.ui.components.RpsCard

internal enum class HomeVersionWidgetStatus {
    Checking,
    UpdateAvailable,
    UpToDate,
}

internal fun resolveHomeVersionWidgetStatus(
    updatesEnabled: Boolean,
    isCheckingForUpdate: Boolean,
    isDownloadingUpdate: Boolean,
    hasAvailableUpdate: Boolean,
): HomeVersionWidgetStatus = when {
    !updatesEnabled -> HomeVersionWidgetStatus.UpToDate
    isCheckingForUpdate || isDownloadingUpdate -> HomeVersionWidgetStatus.Checking
    hasAvailableUpdate -> HomeVersionWidgetStatus.UpdateAvailable
    else -> HomeVersionWidgetStatus.UpToDate
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeVersionMiniWidget(
    versionName: String,
    updatesEnabled: Boolean,
    isCheckingForUpdate: Boolean,
    isDownloadingUpdate: Boolean,
    hasAvailableUpdate: Boolean,
    onLongClickCopyApk: () -> Unit,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (versionName.isBlank()) return

    val scheme = MaterialTheme.colorScheme
    val displayTag = ReleaseChangelog.tagForInstalledVersion(versionName.trim())
    val status = resolveHomeVersionWidgetStatus(
        updatesEnabled = updatesEnabled,
        isCheckingForUpdate = isCheckingForUpdate,
        isDownloadingUpdate = isDownloadingUpdate,
        hasAvailableUpdate = hasAvailableUpdate,
    )
    val statusDescription = stringResource(
        when (status) {
            HomeVersionWidgetStatus.Checking -> R.string.version_status_checking
            HomeVersionWidgetStatus.UpdateAvailable -> R.string.version_status_update_available
            HomeVersionWidgetStatus.UpToDate -> R.string.version_status_up_to_date
        },
    )
    val containerColor = when (status) {
        HomeVersionWidgetStatus.UpdateAvailable ->
            scheme.tertiaryContainer.copy(alpha = 0.94f)
        else -> scheme.surfaceContainerLow.copy(alpha = 0.88f)
    }
    val borderColor = when (status) {
        HomeVersionWidgetStatus.UpdateAvailable -> scheme.tertiary.copy(alpha = 0.6f)
        else -> scheme.outline.copy(alpha = 0.55f)
    }

    RpsCard(
        modifier = modifier
            .height(HomeHeaderChipHeight)
            .semantics {
                contentDescription = "$statusDescription, $displayTag"
            }
            .combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = onLongClickCopyApk,
            ),
        containerColor = containerColor,
        borderColor = borderColor,
        borderWidth = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            HomeVersionStatusIcon(status = status)
            Text(
                text = displayTag,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
            )
        }
    }
}

@Composable
private fun HomeVersionStatusIcon(status: HomeVersionWidgetStatus) {
    val scheme = MaterialTheme.colorScheme
    when (status) {
        HomeVersionWidgetStatus.Checking -> {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = scheme.primary,
            )
        }
        HomeVersionWidgetStatus.UpdateAvailable -> {
            Icon(
                imageVector = Icons.Outlined.SystemUpdate,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = scheme.tertiary,
            )
        }
        HomeVersionWidgetStatus.UpToDate -> {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = scheme.primary,
            )
        }
    }
}
