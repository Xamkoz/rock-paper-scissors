package com.rpsonline.app.data.monitoring

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** Firebase I/O source shown on the top-bar bridge slots (slots 4–6). */
enum class NetworkDataActivityKind {
    Queue,
    Match,
    Presence,
    Connection,
}

/**
 * Short-lived flags for the segmented top-bar while Firebase reads/writes are in flight.
 * Each [bump] lights a move-specific segment pattern on its bridge slot (half-lit, no animation).
 */
object NetworkDataActivityTracker {
    private const val DEFAULT_ACTIVE_MS = 450L

    private val activeUntilByKind = NetworkDataActivityKind.entries.associateWith { AtomicLong(0L) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _activeKinds = MutableStateFlow<Set<NetworkDataActivityKind>>(emptySet())
    val activeKinds: StateFlow<Set<NetworkDataActivityKind>> = _activeKinds.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    @Volatile
    private var decayJob: Job? = null

    fun bump(
        kind: NetworkDataActivityKind,
        durationMs: Long = DEFAULT_ACTIVE_MS,
    ) {
        val until = System.currentTimeMillis() + durationMs
        val counter = activeUntilByKind.getValue(kind)
        while (true) {
            val current = counter.get()
            if (until <= current) break
            if (counter.compareAndSet(current, until)) break
        }
        publishActiveKinds()
        scheduleDecay()
    }

    private fun publishActiveKinds() {
        val now = System.currentTimeMillis()
        val active = NetworkDataActivityKind.entries.filter { kind ->
            activeUntilByKind.getValue(kind).get() > now
        }.toSet()
        _activeKinds.value = active
        _isActive.value = active.isNotEmpty()
    }

    /** Recomputes [activeKinds] from expiry timestamps (unit tests only). */
    internal fun refreshActiveKindsForTest() = publishActiveKinds()

    /** Clears all kinds (unit tests only). */
    internal fun resetForTest() {
        activeUntilByKind.values.forEach { it.set(0L) }
        decayJob?.cancel()
        decayJob = null
        publishActiveKinds()
    }

    private fun scheduleDecay() {
        decayJob?.cancel()
        decayJob = scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val nextExpiry = activeUntilByKind.values
                    .map { it.get() }
                    .filter { it > now }
                    .minOrNull()
                if (nextExpiry == null) break
                delay((nextExpiry - now).coerceAtMost(100L))
            }
            publishActiveKinds()
        }
    }
}
