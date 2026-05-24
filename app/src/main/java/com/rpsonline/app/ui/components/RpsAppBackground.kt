package com.rpsonline.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.rpsonline.app.ui.theme.LocalBackgroundController

@Composable
fun RpsAppBackground(
    modifier: Modifier = Modifier,
    scrimAlpha: Float = 0.42f,
    content: @Composable () -> Unit,
) {
    val background = LocalBackgroundController.current.background

    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(background.drawableRes),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha)),
        )
        content()
    }
}
