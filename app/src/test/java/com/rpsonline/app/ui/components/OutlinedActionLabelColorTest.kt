package com.rpsonline.app.ui.components

import androidx.compose.ui.graphics.Color
import com.rpsonline.app.data.preferences.AppThemeStyle
import com.rpsonline.app.ui.theme.outlinedActionLabelColor
import org.junit.Assert.assertEquals
import org.junit.Test

class OutlinedActionLabelColorTest {

    private val onSurface = Color(0xFF111111)
    private val primary = Color(0xFF00F0FF)

    @Test
    fun lightAndDarkUseOnSurface() {
        assertEquals(
            onSurface,
            outlinedActionLabelColor(AppThemeStyle.LIGHT, onSurface, primary),
        )
        assertEquals(
            onSurface,
            outlinedActionLabelColor(AppThemeStyle.DARK, onSurface, primary),
        )
    }

    @Test
    fun styledPalettesUsePrimary() {
        assertEquals(
            primary,
            outlinedActionLabelColor(AppThemeStyle.CYBERPUNK, onSurface, primary),
        )
        assertEquals(
            primary,
            outlinedActionLabelColor(AppThemeStyle.COSMOS, onSurface, primary),
        )
        assertEquals(
            primary,
            outlinedActionLabelColor(AppThemeStyle.FIRE, onSurface, primary),
        )
    }
}
