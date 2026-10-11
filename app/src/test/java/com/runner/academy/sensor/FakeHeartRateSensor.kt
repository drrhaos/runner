package com.runner.academy.sensor

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** A [HeartRateSensor] driven by the test: no radio, leases are only counted. */
class FakeHeartRateSensor(link: SensorLink = SensorLink.IDLE) : HeartRateSensor {

    private val _status = MutableStateFlow(SensorStatus(link))
    override val status: StateFlow<SensorStatus> = _status.asStateFlow()

    private val _samples = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = BUFFER)
    override val samples: SharedFlow<HeartRateSample> = _samples.asSharedFlow()

    private val _diagnostics = MutableSharedFlow<SensorDiagEvent>(extraBufferCapacity = BUFFER)
    override val diagnostics: SharedFlow<SensorDiagEvent> = _diagnostics.asSharedFlow()

    private val held = mutableListOf<Any>()
    private val heldOwners = mutableMapOf<Any, String>()

    /** Leases taken and not yet released. */
    val leaseCount: Int get() = synchronized(held) { held.size }

    /** Owners of the current leases, oldest first. */
    val owners: List<String> get() = synchronized(held) { held.map { heldOwners.getValue(it) } }

    override fun acquire(owner: String): SensorLease {
        val token = Any()
        synchronized(held) {
            held += token
            heldOwners[token] = owner
        }
        return SensorLease {
            synchronized(held) {
                if (held.remove(token)) heldOwners.remove(token)
            }
        }
    }

    /** A sample at [atNanos]; [bpm] null is "no contact". */
    fun emit(bpm: Int?, atNanos: Long) {
        _samples.tryEmit(HeartRateSample(atNanos, bpm))
    }

    fun setLink(link: SensorLink, batteryPercent: Int? = _status.value.batteryPercent, lastSeenAt: Long? = _status.value.lastSeenAt) {
        _status.value = SensorStatus(link, batteryPercent, lastSeenAt)
    }

    fun diag(event: SensorDiagEvent) {
        _diagnostics.tryEmit(event)
    }

    private companion object {
        const val BUFFER = 64
    }
}
