package com.rpsonline.app.data.repository

import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

internal object HighlightedMatchFunctions {
    private const val GET_HIGHLIGHTED_MATCH_CALLABLE = "getHighlightedMatch"
    private const val CALL_TIMEOUT_MS = 15_000L

    suspend fun getHighlightedMatchId(windowStartMs: Long): String? {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                awaitCallableAuth()
                val functions = FirebaseFunctions.getInstance(
                    FirebaseApp.getInstance(),
                    FIREBASE_FUNCTIONS_REGION,
                )
                val result = withTimeout(CALL_TIMEOUT_MS) {
                    functions.getHttpsCallable(GET_HIGHLIGHTED_MATCH_CALLABLE)
                        .call(hashMapOf("windowStartMs" to windowStartMs))
                        .await()
                }
                @Suppress("UNCHECKED_CAST")
                val body = result.getData() as? Map<String, Any?> ?: return null
                return (body["matchId"] as? String)?.takeIf { it.isNotBlank() }
            } catch (e: FirebaseFunctionsException) {
                lastError = e
                if (e.code == FirebaseFunctionsException.Code.UNAUTHENTICATED && attempt == 0) {
                    delay(400)
                } else {
                    throw e
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt > 0) throw e
                delay(400)
            }
        }
        throw lastError ?: IllegalStateException("Could not load highlighted match")
    }
}
