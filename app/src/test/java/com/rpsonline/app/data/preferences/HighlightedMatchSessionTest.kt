package com.rpsonline.app.data.preferences

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightedMatchSessionTest {

    @After
    fun tearDown() {
        HighlightedMatchSession.clear()
    }

    @Test
    fun dismissStaysTrueUntilClear() {
        assertFalse(HighlightedMatchSession.dismissed)
        HighlightedMatchSession.dismiss()
        assertTrue(HighlightedMatchSession.dismissed)
        HighlightedMatchSession.clear()
        assertFalse(HighlightedMatchSession.dismissed)
    }
}
