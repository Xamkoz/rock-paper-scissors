package com.rpsonline.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastAwareColorsTest {

    @Test
    fun contrastAwareAccent_preservesSemanticWhenAlreadyReadable() {
        val background = Color(0xFF1A2238)
        val semantic = Color(0xFF5CFFE8)
        val fallback = Color(0xFFE8DEF8)
        assertEquals(
            semantic,
            contrastAwareAccent(semantic, background, fallback),
        )
    }

    @Test
    fun contrastAwareAccent_meetsMinimumContrastWhenSemanticIsMuted() {
        val background = Color(0xFF4A4458)
        val semantic = Color(0xFF6B5E8A)
        val fallback = Color(0xFFE8DEF8)
        val adjusted = contrastAwareAccent(semantic, background, fallback)
        assertTrue(contrastRatio(adjusted, background) >= MinProfileAccentContrast)
        assertTrue(adjusted != semantic)
    }
}
