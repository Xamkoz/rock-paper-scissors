package com.rpsonline.app.ui.home

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpsonline.app.R
import com.rpsonline.app.data.update.ReleaseChangelog
import com.rpsonline.app.ui.components.RpsOutlinedBorderWidth
import com.rpsonline.app.ui.components.RpsOutlinedSurfaceStyle

/** Widest expected version label so the chip does not resize on update. */
private const val HomeVersionChipWidthSample = "v0.8.3"

private const val HomeVersionChipScale = 0.8f

private val HomeVersionIconSize = 20.dp * HomeVersionChipScale
private val HomeVersionChipHorizontalPadding = 20.dp * HomeVersionChipScale

@Composable
private fun rememberHomeVersionLabelMinWidth(): Dp {
    val textStyle = MaterialTheme.typography.labelSmall
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(textStyle, textMeasurer, density) {
        val textWidthPx = textMeasurer.measure(
            text = HomeVersionChipWidthSample,
            style = textStyle,
            maxLines = 1,
        ).size.width
        with(density) { textWidthPx.toDp() }
    }
}

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
    val labelColor = when (status) {
        HomeVersionWidgetStatus.UpdateAvailable -> scheme.primary
        HomeVersionWidgetStatus.Checking -> scheme.onSurfaceVariant
        HomeVersionWidgetStatus.UpToDate -> RpsOutlinedSurfaceStyle.contentColor()
    }
    val iconTint = when (status) {
        HomeVersionWidgetStatus.UpdateAvailable -> scheme.primary
        else -> labelColor
    }
    val labelMinWidth = rememberHomeVersionLabelMinWidth()

    HomeHeaderChipColumn(
        onClick = { onClick?.invoke() },
        onLongClick = onLongClickCopyApk,
        containerColor = RpsOutlinedSurfaceStyle.containerColor(),
        borderColor = RpsOutlinedSurfaceStyle.borderColor(),
        borderWidth = RpsOutlinedBorderWidth,
        contentDescription = "$statusDescription, $displayTag",
        modifier = modifier,
        minWidth = labelMinWidth + HomeVersionChipHorizontalPadding,
    ) {
        HomeVersionStatusIcon(status = status, tint = iconTint)
        Text(
            text = displayTag,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = labelMinWidth),
        )
    }
}

@Composable
private fun HomeVersionStatusIcon(
    status: HomeVersionWidgetStatus,
    tint: Color,
) {
    when (status) {
        HomeVersionWidgetStatus.Checking -> {
            CircularProgressIndicator(
                modifier = Modifier.size(HomeVersionIconSize),
                strokeWidth = 2.dp * HomeVersionChipScale,
                color = tint,
            )
        }
        HomeVersionWidgetStatus.UpdateAvailable -> {
            Icon(
                imageVector = Icons.Outlined.SystemUpdate,
                contentDescription = null,
                modifier = Modifier.size(HomeVersionIconSize),
                tint = tint,
            )
        }
        HomeVersionWidgetStatus.UpToDate -> {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(HomeVersionIconSize),
                tint = tint,
            )
        }
    }
}
