package com.rpsonline.app.data.repository

import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctionsException

const val FIREBASE_QUOTA_USER_MESSAGE =
    "Game server is busy right now. Wait a moment and try again."

fun isQuotaExceededError(error: Throwable): Boolean {
    if (error is FirebaseFirestoreException &&
        error.code == FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED
    ) {
        return true
    }
    if (error is FirebaseFunctionsException &&
        error.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED
    ) {
        return true
    }
    val message = error.message.orEmpty()
    if (message.contains("quota", ignoreCase = true)) return true
    if (message.contains("resource exhausted", ignoreCase = true)) return true
    val cause = error.cause ?: return false
    if (cause === error) return false
    return isQuotaExceededError(cause)
}

fun quotaExceededUserMessage(): String = FIREBASE_QUOTA_USER_MESSAGE
