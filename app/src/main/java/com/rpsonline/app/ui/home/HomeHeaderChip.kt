package com.rpsonline.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpsonline.app.ui.components.RpsCard
import com.rpsonline.app.ui.components.RpsOutlinedBorderWidth

/** Chips are 80% of the original 48dp welcome-header size. */
private const val HomeHeaderChipScale = 0.8f

private val HomeHeaderChipBaseHeight = 48.dp
internal val HomeHeaderChipHeight = HomeHeaderChipBaseHeight * HomeHeaderChipScale

internal val HomeHeaderChipBorderWidth = RpsOutlinedBorderWidth
private val HomeHeaderChipPaddingHorizontal = 12.dp * HomeHeaderChipScale
private val HomeHeaderChipColumnPaddingHorizontal = 10.dp * HomeHeaderChipScale
internal val HomeHeaderChipColumnPaddingVertical = 4.dp * HomeHeaderChipScale
private val HomeHeaderChipColumnItemSpacing = 2.dp * HomeHeaderChipScale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeHeaderChip(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    containerColor: Color,
    borderColor: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.combinedClickable(onClick = onClick)
    }
    RpsCard(
        modifier = modifier
            .wrapContentWidth()
            .clip(shape)
            .height(HomeHeaderChipHeight)
            .semantics { this.contentDescription = contentDescription }
            .then(clickModifier),
        containerColor = containerColor,
        borderColor = borderColor,
        borderWidth = HomeHeaderChipBorderWidth,
    ) {
        Row(
            modifier = Modifier
                .height(HomeHeaderChipHeight)
                .padding(horizontal = HomeHeaderChipPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeHeaderChipColumn(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    containerColor: Color,
    borderColor: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
    minWidth: Dp = 0.dp,
    borderWidth: Dp = HomeHeaderChipBorderWidth,
    chipHeight: Dp = HomeHeaderChipHeight,
    columnPaddingTop: Dp = HomeHeaderChipColumnPaddingVertical,
    columnPaddingBottom: Dp = HomeHeaderChipColumnPaddingVertical,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.combinedClickable(onClick = onClick)
    }
    RpsCard(
        modifier = modifier
            .wrapContentWidth()
            .then(if (minWidth > 0.dp) Modifier.widthIn(min = minWidth) else Modifier)
            .clip(shape)
            .height(chipHeight)
            .semantics { this.contentDescription = contentDescription }
            .then(clickModifier),
        containerColor = containerColor,
        borderColor = borderColor,
        borderWidth = borderWidth,
    ) {
        Box(
            modifier = Modifier
                .height(chipHeight)
                .padding(
                    start = HomeHeaderChipColumnPaddingHorizontal,
                    end = HomeHeaderChipColumnPaddingHorizontal,
                    top = columnPaddingTop,
                    bottom = columnPaddingBottom,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(HomeHeaderChipColumnItemSpacing),
                content = content,
            )
        }
    }
}
