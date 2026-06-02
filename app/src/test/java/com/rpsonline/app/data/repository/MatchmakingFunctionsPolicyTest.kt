package com.rpsonline.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MatchmakingFunctionsPolicyTest {

    @Test
    fun quotaErrorsAreNotRecoverableViaFirestore() {
        val error = IllegalStateException("The project quota for this operation has been exceeded.")
        assertFalse(MatchmakingFunctions.isRecoverableViaFirestore(error))
    }

    @Test
    fun quotaErrorsSurfaceBusyMessage() {
        val error = IllegalStateException("The project quota for this operation has been exceeded.")
        assertEquals(quotaExceededUserMessage(), MatchmakingFunctions.toJoinErrorMessage(error))
    }
}
