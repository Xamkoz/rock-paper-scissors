package com.rpsonline.app.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundFeedbackModeTest {

    @Test
    fun next_cyclesSoundHapticOff() {
        assertEquals(
            SoundFeedbackMode.HAPTIC_ONLY,
            SoundFeedbackMode.SOUND_AND_HAPTIC.next(),
        )
        assertEquals(SoundFeedbackMode.OFF, SoundFeedbackMode.HAPTIC_ONLY.next())
        assertEquals(SoundFeedbackMode.SOUND_AND_HAPTIC, SoundFeedbackMode.OFF.next())
    }

    @Test
    fun allowsSound_onlyOnFullMode() {
        assertTrue(SoundFeedbackMode.SOUND_AND_HAPTIC.allowsSound())
        assertFalse(SoundFeedbackMode.HAPTIC_ONLY.allowsSound())
        assertFalse(SoundFeedbackMode.OFF.allowsSound())
    }

    @Test
    fun allowsHaptic_whenNotOff() {
        assertTrue(SoundFeedbackMode.SOUND_AND_HAPTIC.allowsHaptic())
        assertTrue(SoundFeedbackMode.HAPTIC_ONLY.allowsHaptic())
        assertFalse(SoundFeedbackMode.OFF.allowsHaptic())
    }

    @Test
    fun storageRoundTrip() {
        SoundFeedbackMode.entries.forEach { mode ->
            assertEquals(mode, SoundFeedbackMode.fromStorage(mode.toStorage()))
        }
    }
}
