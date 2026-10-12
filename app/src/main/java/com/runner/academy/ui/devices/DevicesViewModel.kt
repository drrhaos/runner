package com.runner.academy.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runner.academy.sensor.HeartRateSample
import com.runner.academy.sensor.HeartRateSensor
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.SensorLease
import com.runner.academy.sensor.SensorLink
import com.runner.academy.sensor.SensorStatus
import com.runner.academy.sensor.SensorType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Devices screen: one row per paired sensor type, null when none is paired (the empty state). */
data class DevicesState(val heartRate: DeviceRow?)

/**
 * The Devices screen. While it is visible ([onStart] to [onStop]) it holds a lease on the
 * sensor: the live pulse, the battery and "strap not worn" need a connection.
 *
 * @param paired the paired sensors ([com.runner.academy.sensor.PairedSensorStore.changes]).
 * @param forgetSensor unpairs a type ([com.runner.academy.sensor.PairedSensorStore.remove]).
 * @param nowMs a monotonic clock, for "searching" turning into "offline".
 */
class DevicesViewModel(
    private val sensor: HeartRateSensor,
    paired: StateFlow<Map<SensorType, PairedSensor>>,
    private val forgetSensor: (SensorType) -> Unit,
    private val nowMs: () -> Long
) : ViewModel() {

    /** The link as seen since the screen started; the pulse is refreshed once a second. */
    private data class Live(
        val status: SensorStatus,
        val sample: HeartRateSample?,
        val linkSinceMs: Long,
        val nowMs: Long
    )

    private val live = MutableStateFlow<Live?>(null)
    private var lease: SensorLease? = null
    private var job: Job? = null

    /** The newest sample not shown yet; reset when the link changes. */
    private var pendingSample: HeartRateSample? = null

    val state: StateFlow<DevicesState> = combine(paired, live) { sensors, current ->
        DevicesState(heartRate = sensors[SensorType.HEART_RATE]?.let { device -> row(device, current) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DevicesState(null))

    private fun row(device: PairedSensor, current: Live?): DeviceRow =
        if (current == null) {
            DeviceRowText.of(device, sensor.status.value, null, nowMs = 0L, linkSinceMs = 0L)
        } else {
            DeviceRowText.of(device, current.status, current.sample, current.nowMs, current.linkSinceMs)
        }

    fun onStart() {
        if (lease != null) return
        lease = sensor.acquire(LEASE_OWNER)
        val start = nowMs()
        pendingSample = null
        live.value = Live(sensor.status.value, null, start, start)
        job = viewModelScope.launch {
            launch { sensor.status.collect(::onStatus) }
            launch { sensor.samples.collect { pendingSample = it } }
            launch {
                while (true) {
                    delay(TICK_MS)
                    live.update { current ->
                        current?.copy(
                            sample = if (current.status.link == SensorLink.CONNECTED) pendingSample ?: current.sample else null,
                            nowMs = nowMs()
                        )
                    }
                }
            }
        }
    }

    private fun onStatus(status: SensorStatus) {
        val now = nowMs()
        live.update { current ->
            when {
                current == null -> Live(status, null, now, now)
                current.status.link != status.link -> {
                    pendingSample = null
                    Live(status, null, now, now)
                }
                else -> current.copy(status = status, nowMs = now)
            }
        }
    }

    fun onStop() {
        job?.cancel()
        job = null
        lease?.release()
        lease = null
    }

    fun forget(type: SensorType) {
        forgetSensor(type)
    }

    override fun onCleared() {
        onStop()
        super.onCleared()
    }

    companion object {
        const val LEASE_OWNER = "devices"
        private const val TICK_MS = 1_000L
    }
}

class DevicesViewModelFactory(
    private val sensor: HeartRateSensor,
    private val paired: StateFlow<Map<SensorType, PairedSensor>>,
    private val forgetSensor: (SensorType) -> Unit,
    private val nowMs: () -> Long
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DevicesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DevicesViewModel(sensor, paired, forgetSensor, nowMs) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
