package com.rpsonline.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.rpsonline.app.navigation.RpsNavGraph
import com.rpsonline.app.ui.theme.LocalThemeController
import com.rpsonline.app.ui.theme.RpsTheme
import com.rpsonline.app.ui.theme.ThemeController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeController = remember { ThemeController(applicationContext) }
            CompositionLocalProvider(LocalThemeController provides themeController) {
                RpsTheme(darkTheme = themeController.useDarkTheme()) {
                    RpsNavGraph()
                }
            }
        }
    }
}
