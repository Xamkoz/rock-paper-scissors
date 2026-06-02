package com.rpsonline.app.data.repository

import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.rpsonline.app.data.model.Move
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

internal object GameFunctions {
    private const val SUBMIT_MOVE_CALLABLE = "submitMatchMove"
    private const val CALL_TIMEOUT_MS = 8_000L
    private const val QUOTA_RETRY_ATTEMPTS = 2
    private const val QUOTA_RETRY_DELAY_MS = 1_000L

    suspend fun submitMove(matchId: String, roundNumber: Int, move: Move) {
        var lastError: Exception? = null
        repeat(QUOTA_RETRY_ATTEMPTS) { attempt ->
            try {
                awaitCallableAuthForSubmit()
                val functions = FirebaseFunctions.getInstance(
                    FirebaseApp.getInstance(),
                    FIREBASE_FUNCTIONS_REGION,
                )
                val payload = hashMapOf(
                    "matchId" to matchId,
                    "roundNumber" to roundNumber,
                    "choice" to move.name,
                )
                withTimeout(CALL_TIMEOUT_MS) {
                    functions.getHttpsCallable(SUBMIT_MOVE_CALLABLE).call(payload).await()
                }
                return
            } catch (e: FirebaseFunctionsException) {
                lastError = e
                if (isRecoverableViaFirestore(e) && !isQuotaExceededError(e)) {
                    throw e
                }
                if (isQuotaExceededError(e) && attempt < QUOTA_RETRY_ATTEMPTS - 1) {
                    delay(QUOTA_RETRY_DELAY_MS * (attempt + 1))
                } else if (e.code == FirebaseFunctionsException.Code.UNAUTHENTICATED && attempt == 0) {
                    delay(400)
                } else {
                    throw e
                }
            } catch (e: Exception) {
                lastError = e
                if (isRecoverableViaFirestore(e) && !isQuotaExceededError(e)) {
                    throw e
                }
                if (isQuotaExceededError(e) && attempt < QUOTA_RETRY_ATTEMPTS - 1) {
                    delay(QUOTA_RETRY_DELAY_MS * (attempt + 1))
                } else if (attempt > 0) {
                    throw e
                } else {
                    delay(400)
                }
            }
        }
        throw lastError ?: IllegalStateException("Could not submit move via server")
    }

    fun isRecoverableViaFirestore(error: Throwable): Boolean {
        if (error is TimeoutCancellationException) return true
        if (isQuotaExceededError(error)) return true
        val functionsError = error as? FirebaseFunctionsException ?: error.cause as? FirebaseFunctionsException
        return when (functionsError?.code) {
            FirebaseFunctionsException.Code.UNAUTHENTICATED,
            FirebaseFunctionsException.Code.UNAVAILABLE,
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
            FirebaseFunctionsException.Code.NOT_FOUND,
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
            -> true
            else -> false
        }
    }

    fun isStaleRoundSubmitError(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        if (message.contains("no longer open", ignoreCase = true)) return true
        val functionsError = error as? FirebaseFunctionsException ?: error.cause as? FirebaseFunctionsException
        return functionsError?.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION &&
            functionsError.message.orEmpty().contains("no longer open", ignoreCase = true)
    }

    /** Server already finalized the match while the client still shows an active round. */
    fun isMatchNotActiveSubmitError(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        if (message.contains("not active", ignoreCase = true)) return true
        val functionsError = error as? FirebaseFunctionsException ?: error.cause as? FirebaseFunctionsException
        return functionsError?.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION &&
            functionsError.message.orEmpty().contains("not active", ignoreCase = true)
    }

    /** Callable verification failed after apply — move may still be on the match doc. */
    fun isMoveNotRecordedVerificationError(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        if (message.contains("not recorded", ignoreCase = true)) return true
        val functionsError = error as? FirebaseFunctionsException ?: error.cause as? FirebaseFunctionsException
        return functionsError?.code == FirebaseFunctionsException.Code.INTERNAL &&
            functionsError.message.orEmpty().contains("not recorded", ignoreCase = true)
    }

    fun toSubmitErrorMessage(error: Throwable): String? {
        if (isRecoverableViaFirestore(error)) return null
        if (isQuotaExceededError(error)) return quotaExceededUserMessage()
        val functionsError = error as? FirebaseFunctionsException ?: error.cause as? FirebaseFunctionsException
        return when (functionsError?.code) {
            FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                "Could not verify your account with the game server. Sign out and sign in again."
            FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                "You cannot submit a move in this match."
            FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
                functionsError.message ?: "This round is no longer open."
            FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                functionsError.message ?: "Invalid move."
            else -> null
        }
    }
}
