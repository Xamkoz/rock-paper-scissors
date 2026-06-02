package com.rpsonline.app.data.repository

import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.TimeoutCancellationException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

const val FIREBASE_QUOTA_USER_MESSAGE =
    "Game server is busy right now. Wait a moment and try again."

const val FIREBASE_CONNECTIVITY_USER_MESSAGE =
    "Can't reach the game server. Check your internet connection and try again."

/** Result of a lightweight Firestore reachability probe. */
enum class FirestoreProbeOutcome {
    Reachable,
    QuotaExceeded,
    UnreachableDefinitive,
    UnreachableTimeout,
    UnreachableOther,
    ;

    val isReachable: Boolean
        get() = this == Reachable

    val isDefinitiveUnavailable: Boolean
        get() = this == UnreachableDefinitive || this == QuotaExceeded

    val isQuotaExceeded: Boolean
        get() = this == QuotaExceeded
}

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

fun connectivityFailureUserMessage(): String = FIREBASE_CONNECTIVITY_USER_MESSAGE

fun userFacingFirebaseError(error: Throwable, fallback: String): String = when {
    isQuotaExceededError(error) -> quotaExceededUserMessage()
    isConnectivityFailure(error) -> connectivityFailureUserMessage()
    else -> error.message?.takeIf { it.isNotBlank() && !isQuotaExceededError(error) } ?: fallback
}

fun isConnectivityFailure(error: Throwable): Boolean =
    classifyFirestoreProbeFailure(error) == FirestoreProbeOutcome.UnreachableDefinitive ||
        hasConnectivityCause(error)

fun classifyFirestoreProbeFailure(error: Throwable): FirestoreProbeOutcome {
    if (isQuotaExceededError(error)) return FirestoreProbeOutcome.QuotaExceeded
    if (error is TimeoutCancellationException) return FirestoreProbeOutcome.UnreachableTimeout
    if (error is FirebaseFirestoreException) {
        return when (error.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            FirebaseFirestoreException.Code.UNAUTHENTICATED,
            -> FirestoreProbeOutcome.Reachable
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
            -> if (hasConnectivityCause(error)) {
                FirestoreProbeOutcome.UnreachableDefinitive
            } else {
                FirestoreProbeOutcome.UnreachableOther
            }
            else -> FirestoreProbeOutcome.UnreachableOther
        }
    }
    return if (hasConnectivityCause(error)) {
        FirestoreProbeOutcome.UnreachableDefinitive
    } else {
        FirestoreProbeOutcome.UnreachableOther
    }
}

fun hasConnectivityCause(error: Throwable): Boolean {
    val seen = HashSet<Int>()
    var current: Throwable? = error
    while (current != null && seen.add(System.identityHashCode(current))) {
        when (current) {
            is UnknownHostException,
            is ConnectException,
            is SocketTimeoutException,
            is SSLException,
            -> return true
        }
        val message = current.message.orEmpty()
        if (
            message.contains("Unable to resolve host", ignoreCase = true) ||
            message.contains("No address associated with hostname", ignoreCase = true) ||
            message.contains("Network is unreachable", ignoreCase = true)
        ) {
            return true
        }
        current = current.cause
    }
    return false
}
