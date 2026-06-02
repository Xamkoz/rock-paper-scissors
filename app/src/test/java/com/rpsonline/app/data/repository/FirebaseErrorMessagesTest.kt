package com.rpsonline.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseErrorMessagesTest {

    @Test
    fun isQuotaExceededError_matchesProjectQuotaMessage() {
        val error = IllegalStateException("The project quota for this operation has been exceeded.")
        assertTrue(isQuotaExceededError(error))
    }

    @Test
    fun isQuotaExceededError_ignoresUnrelatedErrors() {
        assertFalse(isQuotaExceededError(IllegalStateException("Permission denied")))
    }

    @Test
    fun classifyFirestoreProbeFailure_quotaMessage() {
        val error = IllegalStateException("The project quota for this operation has been exceeded.")
        assertEquals(FirestoreProbeOutcome.QuotaExceeded, classifyFirestoreProbeFailure(error))
    }

    @Test
    fun quotaExceededUserMessage_isActionable() {
        assertEquals(
            "Game server is busy right now. Wait a moment and try again.",
            quotaExceededUserMessage(),
        )
    }
}
