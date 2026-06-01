package com.rpsonline.app.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test

class GameFunctionsPolicyTest {

    @Test
    fun quotaErrorsAreRecoverableViaFirestore() {
        val error = IllegalStateException("The project quota for this operation has been exceeded.")
        assertTrue(GameFunctions.isRecoverableViaFirestore(error))
    }
}
