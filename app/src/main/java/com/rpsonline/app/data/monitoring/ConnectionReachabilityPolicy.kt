package com.rpsonline.app.data.monitoring

internal object ConnectionReachabilityPolicy {
    const val SERVER_STALE_MS = 60_000L

    fun resolveStatus(
        hasNetwork: Boolean,
        serverReachable: Boolean,
        nowMs: Long,
        lastServerSuccessMs: Long,
        staleMs: Long = SERVER_STALE_MS,
        definitiveUnavailable: Boolean = false,
    ): NetworkConnectionStatus {
        if (!hasNetwork) return NetworkConnectionStatus.Offline
        if (serverReachable) return NetworkConnectionStatus.Connected
        if (definitiveUnavailable || lastServerSuccessMs <= 0L) {
            return NetworkConnectionStatus.Offline
        }
        return if (nowMs - lastServerSuccessMs > staleMs) {
            NetworkConnectionStatus.Offline
        } else {
            NetworkConnectionStatus.Connected
        }
    }
}
