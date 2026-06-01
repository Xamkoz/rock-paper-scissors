package com.rpsonline.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpsonline.app.ui.components.RpsCard

internal val HomeHeaderMiniWidgetMinWidth = 0.dp

/** Fixed height so sign-out and version chips align in the welcome header row. */
internal val HomeHeaderChipHeight = 48.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeHeaderMiniWidget(
    caption: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    minWidth: Dp = HomeHeaderMiniWidgetMinWidth,
    valueColor: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String = "$caption $value",
) {
    val scheme = MaterialTheme.colorScheme
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else {
        Modifier.combinedClickable(onClick = onClick)
    }
    RpsCard(
        modifier = modifier
            .then(if (minWidth > 0.dp) Modifier.widthIn(min = minWidth) else Modifier)
            .height(HomeHeaderChipHeight)
            .semantics { this.contentDescription = contentDescription }
            .then(clickModifier),
        containerColor = scheme.surfaceContainerLow.copy(alpha = 0.88f),
        borderColor = scheme.outline.copy(alpha = 0.55f),
        borderWidth = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (caption.isNotBlank()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = valueColor,
            )
        }
    }
}
