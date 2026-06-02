package com.rpsonline.app.data.repository

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class GameFunctionsPolicyTest {

    @Test
    fun quotaErrorsAreRecoverableViaFirestore() {
        val error = IllegalStateException("The project quota for this operation has been exceeded.")
        assertTrue(GameFunctions.isRecoverableViaFirestore(error))
    }

    @Test
    fun submitTimeoutsAreRecoverableViaFirestore() {
        val timeout = runBlocking {
            try {
                withTimeout(1) { delay(10_000) }
                error("Expected timeout")
            } catch (e: TimeoutCancellationException) {
                e
            }
        }
        assertTrue(GameFunctions.isRecoverableViaFirestore(timeout))
    }

    @Test
    fun matchNotActiveSubmitError_isDetected() {
        val error = IllegalStateException("Match is not active.")
        assertTrue(GameFunctions.isMatchNotActiveSubmitError(error))
    }

    @Test
    fun moveNotRecordedVerificationError_isDetected() {
        val error = IllegalStateException("Move was not recorded. Try again.")
        assertTrue(GameFunctions.isMoveNotRecordedVerificationError(error))
    }
}
