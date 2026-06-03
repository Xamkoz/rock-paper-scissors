package com.rpsonline.app.ui.game

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class MatchPanelScoreColorsTest {

    @Test
    fun readableScoreAccent_prefersHighContrastOnDarkCard() {
        val darkCard = Color(0xFF1A2238)
        val mutedAccent = Color(0xFF6B5E8A)
        val brightOnContainer = Color(0xFFFFF0A8)
        assertEquals(
            brightOnContainer,
            readableScoreAccent(
                accent = mutedAccent,
                onAccent = Color(0xFF1A1400),
                onAccentContainer = brightOnContainer,
                background = darkCard,
            ),
        )
    }

    @Test
    fun readableScoreAccent_prefersDarkAccentOnLightCard() {
        val lightCard = Color(0xFFE7E0EC)
        val vividAccent = Color(0xFFFFD319)
        val darkOnAccent = Color(0xFF1A1400)
        assertEquals(
            darkOnAccent,
            readableScoreAccent(
                accent = vividAccent,
                onAccent = darkOnAccent,
                onAccentContainer = Color(0xFFFFF0A8),
                background = lightCard,
            ),
        )
    }
}
