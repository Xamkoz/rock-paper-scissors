package com.rpsonline.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rpsonline.app.R

@Composable
fun HomeOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    label: String = stringResource(R.string.back_to_home),
) {
    RpsOutlinedActionButton(
        onClick = onClick,
        text = label,
        modifier = modifier,
    )
}
