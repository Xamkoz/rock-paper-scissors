package com.rpsonline.app.data.repository

import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

internal data class TouchPresenceResult(
    val serverTimeMs: Long,
    val onlineCount: Int?,
)

internal object PresenceFunctions {
    private const val TOUCH_CALLABLE = "touchPresence"
    private const val CALL_TIMEOUT_MS = 10_000L

    /** Updates presence on the server. Returns server time and online count when successful. */
    suspend fun tryTouchPresence(): TouchPresenceResult? {
        repeat(2) { attempt ->
            try {
                awaitCallableAuth()
                val functions = FirebaseFunctions.getInstance(
                    FirebaseApp.getInstance(),
                    FIREBASE_FUNCTIONS_REGION,
                )
                val result = withTimeout(CALL_TIMEOUT_MS) {
                    functions.getHttpsCallable(TOUCH_CALLABLE).call(emptyMap<String, Any>()).await()
                }
                @Suppress("UNCHECKED_CAST")
                val body = result.getData() as? Map<String, Any?> ?: return null
                val serverTimeMs = (body["serverTimeMs"] as? Number)?.toLong() ?: return null
                val onlineCount = (body["onlineCount"] as? Number)?.toInt()
                return TouchPresenceResult(serverTimeMs = serverTimeMs, onlineCount = onlineCount)
            } catch (e: FirebaseFunctionsException) {
                if (e.code == FirebaseFunctionsException.Code.UNAUTHENTICATED && attempt == 0) {
                    delay(400)
                } else {
                    return null
                }
            } catch (e: Exception) {
                if (attempt > 0) return null
                delay(400)
            }
        }
        return null
    }
}
