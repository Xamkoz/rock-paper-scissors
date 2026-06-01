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

/**
 * Short-lived flag for UI (segmented top bar) while Firebase reads/writes are in flight.
 * [bump] extends the active window; decays automatically when activity stops.
 */
object NetworkDataActivityTracker {
    private const val DEFAULT_ACTIVE_MS = 450L

    private val activeUntilMs = AtomicLong(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    @Volatile
    private var decayJob: Job? = null

    fun bump(durationMs: Long = DEFAULT_ACTIVE_MS) {
        val until = System.currentTimeMillis() + durationMs
        while (true) {
            val current = activeUntilMs.get()
            if (until <= current) break
            if (activeUntilMs.compareAndSet(current, until)) break
        }
        _isActive.value = true
        scheduleDecay()
    }

    private fun scheduleDecay() {
        decayJob?.cancel()
        decayJob = scope.launch {
            while (true) {
                val remaining = activeUntilMs.get() - System.currentTimeMillis()
                if (remaining <= 0L) break
                delay(remaining.coerceAtMost(100L))
            }
            if (System.currentTimeMillis() >= activeUntilMs.get()) {
                _isActive.value = false
            }
        }
    }
}
