package com.rpsonline.app.data.repository

import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.rpsonline.app.data.model.UserProfile
import com.rpsonline.app.domain.MatchMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

internal data class JoinQueueCallableResult(
    val clientJoinedAtMs: Long,
    /** Server `joinedAt` from the queue write (preferred timer anchor). */
    val serverJoinedAtMs: Long,
    val activeMatchId: String? = null,
)

internal object MatchmakingFunctions {
    private const val JOIN_CALLABLE = "joinMatchmakingQueue"
    private const val CONFIRM_READY_CALLABLE = "confirmMatchReady"
    private const val CALL_TIMEOUT_MS = 15_000L
    private const val JOIN_QUOTA_RETRY_ATTEMPTS = 3
    private const val JOIN_QUOTA_RETRY_DELAY_MS = 1_000L

    suspend fun joinQueue(matchModes: Set<MatchMode>, profile: UserProfile): JoinQueueCallableResult {
        var lastError: Exception? = null
        repeat(JOIN_QUOTA_RETRY_ATTEMPTS) { attempt ->
            try {
                awaitCallableAuth()
                val functions = FirebaseFunctions.getInstance(
                    FirebaseApp.getInstance(),
                    FIREBASE_FUNCTIONS_REGION,
                )
                val payload = hashMapOf(
                    "matchModes" to matchModes.map { it.name },
                    "displayName" to profile.displayName,
                    "elo" to profile.elo,
                )
                val result = withTimeout(CALL_TIMEOUT_MS) {
                    functions.getHttpsCallable(JOIN_CALLABLE).call(payload).await()
                }
                @Suppress("UNCHECKED_CAST")
                val body = result.getData() as? Map<String, Any?>
                    ?: throw IllegalStateException("Invalid matchmaking response")
                val activeMatchId = (body["activeMatchId"] as? String)?.takeIf { it.isNotBlank() }
                val clientJoinedAtMs = (body["clientJoinedAtMs"] as? Number)?.toLong()
                    ?: System.currentTimeMillis()
                val serverJoinedAtMs = (body["joinedAtMs"] as? Number)?.toLong()
                    ?: clientJoinedAtMs
                return JoinQueueCallableResult(
                    clientJoinedAtMs = clientJoinedAtMs,
                    serverJoinedAtMs = serverJoinedAtMs,
                    activeMatchId = activeMatchId,
                )
            } catch (e: FirebaseFunctionsException) {
                lastError = e
                when {
                    e.code == FirebaseFunctionsException.Code.UNAUTHENTICATED && attempt == 0 ->
                        delay(400)
                    isQuotaExceededError(e) && attempt < JOIN_QUOTA_RETRY_ATTEMPTS - 1 ->
                        delay(JOIN_QUOTA_RETRY_DELAY_MS * (attempt + 1))
                    else -> throw e
                }
            } catch (e: Exception) {
                lastError = e
                when {
                    isQuotaExceededError(e) && attempt < JOIN_QUOTA_RETRY_ATTEMPTS - 1 ->
                        delay(JOIN_QUOTA_RETRY_DELAY_MS * (attempt + 1))
                    attempt > 0 -> throw e
                    else -> delay(400)
                }
            }
        }
        throw lastError ?: IllegalStateException("Could not join matchmaking via server")
    }

    suspend fun confirmMatchReady(matchId: String) {
        var lastError: Exception? = null
        repeat(5) { attempt ->
            try {
                awaitCallableAuth()
                val functions = FirebaseFunctions.getInstance(
                    FirebaseApp.getInstance(),
                    FIREBASE_FUNCTIONS_REGION,
                )
                withTimeout(CALL_TIMEOUT_MS) {
                    functions.getHttpsCallable(CONFIRM_READY_CALLABLE)
                        .call(hashMapOf("matchId" to matchId))
                        .await()
                }
                return
            } catch (e: FirebaseFunctionsException) {
                lastError = e
                when (e.code) {
                    FirebaseFunctionsException.Code.UNAUTHENTICATED,
                    FirebaseFunctionsException.Code.UNAVAILABLE,
                    FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
                    FirebaseFunctionsException.Code.INTERNAL,
                    -> if (attempt < 4) delay(400L * (attempt + 1)) else throw e
                    else -> throw e
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt >= 4) throw e
                delay(400L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("Could not confirm match ready")
    }

    fun isRecoverableViaFirestore(error: Throwable): Boolean {
        if (isQuotaExceededError(error)) return false
        val functionsError = error as? FirebaseFunctionsException ?: error.cause as? FirebaseFunctionsException
        return when (functionsError?.code) {
            FirebaseFunctionsException.Code.UNAUTHENTICATED,
            FirebaseFunctionsException.Code.UNAVAILABLE,
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
            FirebaseFunctionsException.Code.NOT_FOUND,
            -> true
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> false
            else -> false
        }
    }

    fun toJoinErrorMessage(error: Throwable): String? {
        if (isRecoverableViaFirestore(error)) return null
        if (isQuotaExceededError(error)) return quotaExceededUserMessage()
        val functionsError = error as? FirebaseFunctionsException ?: error.cause as? FirebaseFunctionsException
        return when (functionsError?.code) {
            FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                "Could not verify your account with the matchmaking server. Sign out and sign in again."
            FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                "Could not join matchmaking (permission denied)."
            FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                functionsError.message ?: "Invalid matchmaking request."
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                quotaExceededUserMessage()
            else -> null
        }
    }
}
