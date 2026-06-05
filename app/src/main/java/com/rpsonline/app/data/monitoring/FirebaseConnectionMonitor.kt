package com.rpsonline.app.data.monitoring

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.rpsonline.app.data.repository.AuthRepository
import com.rpsonline.app.data.repository.FirestoreConnectivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Tracks whether the client can reach Firebase (network up + Firestore reachable),
 * matching [com.rpsonline.app.data.repository.AuthRepository.isFirebaseAvailable].
 */
class NetworkConnectionMonitor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val authRepository = AuthRepository()

    private val _status = MutableStateFlow<NetworkConnectionStatus>(NetworkConnectionStatus.Checking)
    val status: StateFlow<NetworkConnectionStatus> = _status.asStateFlow()

    private var lastServerSuccessAtMs = 0L
    private var linkWasDown = false

    private var probeJob: Job? = null
    private var restoreJob: Job? = null
    private var networkCallbackRegistered = false
    private val probeMutex = Mutex()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleConnectivityRestore()
        }

        override fun onLost(network: Network) {
            linkWasDown = true
            if (!hasValidatedNetwork()) {
                _status.value = NetworkConnectionStatus.Offline
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            requestProbe()
        }
    }

    private var monitorScope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        monitorScope = scope
        registerNetworkCallback()
        probeJob?.cancel()
        probeJob = scope.launch {
            probeNow(showChecking = true)
            while (isActive) {
                delay(probeIntervalMs())
                probeNow(showChecking = false)
            }
        }
    }

    fun stop() {
        probeJob?.cancel()
        probeJob = null
        restoreJob?.cancel()
        restoreJob = null
        unregisterNetworkCallback()
        monitorScope = null
    }

    private fun scheduleConnectivityRestore() {
        val scope = monitorScope ?: return
        restoreJob?.cancel()
        restoreJob = scope.launch {
            delay(RESTORE_DEBOUNCE_MS)
            awaitValidatedNetwork()
            val preferHardReset = linkWasDown &&
                _status.value != NetworkConnectionStatus.Connected
            linkWasDown = false
            withContext(Dispatchers.IO) {
                FirestoreConnectivity.restoreAfterConnectivityLoss(preferHardReset = preferHardReset)
            }
            requestProbe()
        }
    }

    private fun requestProbe() {
        val scope = monitorScope ?: return
        scope.launch {
            probeMutex.withLock {
                probeNow(showChecking = false)
            }
        }
    }

    private suspend fun awaitValidatedNetwork() {
        repeat(VALIDATED_NETWORK_POLL_ATTEMPTS) {
            if (hasValidatedNetwork()) return
            delay(VALIDATED_NETWORK_POLL_MS)
        }
    }

    private suspend fun probeNow(showChecking: Boolean) {
        if (!hasValidatedNetwork()) {
            _status.value = NetworkConnectionStatus.Offline
            return
        }
        if (showChecking && _status.value != NetworkConnectionStatus.Connected) {
            _status.value = NetworkConnectionStatus.Checking
        }
        NetworkDataActivityTracker.bump(NetworkDataActivityKind.Connection)
        val nowMs = System.currentTimeMillis()
        val probeOutcome = withContext(Dispatchers.IO) {
            authRepository.probeFirestoreServerOutcome()
        }
        if (probeOutcome.isReachable) {
            lastServerSuccessAtMs = nowMs
        }
        val resolved = ConnectionReachabilityPolicy.resolveStatus(
            hasNetwork = true,
            serverReachable = probeOutcome.isReachable,
            nowMs = nowMs,
            lastServerSuccessMs = lastServerSuccessAtMs,
            definitiveUnavailable = probeOutcome.isDefinitiveUnavailable,
        )
        _status.value = resolved
    }

    private fun probeIntervalMs(): Long =
        if (_status.value == NetworkConnectionStatus.Connected) CONNECTED_PROBE_INTERVAL_MS
        else OFFLINE_PROBE_INTERVAL_MS

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val connectivity = connectivityManager() ?: return
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback,
            )
            networkCallbackRegistered = true
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        val connectivity = connectivityManager() ?: return
        runCatching {
            connectivity.unregisterNetworkCallback(networkCallback)
        }
        networkCallbackRegistered = false
    }

    private fun connectivityManager(): ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun hasValidatedNetwork(): Boolean {
        val connectivity = connectivityManager() ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isCaptivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        val hasUsableTransport =
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        return hasInternet && (isValidated || (hasUsableTransport && !isCaptivePortal))
    }

    companion object {
        private const val CONNECTED_PROBE_INTERVAL_MS = 12_000L
        private const val OFFLINE_PROBE_INTERVAL_MS = 4_000L
        private const val RESTORE_DEBOUNCE_MS = 800L
        private const val VALIDATED_NETWORK_POLL_MS = 200L
        private const val VALIDATED_NETWORK_POLL_ATTEMPTS = 25
    }
}
