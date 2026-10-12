package com.runner.academy.debug

import android.os.SystemClock
import com.runner.academy.sensor.HeartRateSample
import com.runner.academy.sensor.HeartRateSensor
import com.runner.academy.sensor.SensorDiagEvent
import com.runner.academy.sensor.SensorLease
import com.runner.academy.sensor.SensorLink
import com.runner.academy.sensor.SensorStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The 4-minute script of the simulated sensor: every state of the heart-rate tile once.
 * - 0:00–1:00 normal; 1:00–1:12 connected but silent (value, grey, `--`);
 * - 1:12–2:00 normal; 2:00–2:15 no contact (`--` at once, connected icon);
 * - 2:15–3:00 normal; 3:00–3:40 connecting, silent (searching; "lost" after 30 s);
 * - 3:40–4:00 normal ("connected").
 * Normal is a 120–170 bpm sine at 1 Hz.
 */
object SimulatedHeartRateScenario {

    const val CYCLE_MS = 240_000L
    /** Two waves a cycle. */
    private const val SINE_PERIOD_MS = 120_000.0

    /** @param sample a sample comes this second; [bpm] null on it is "no contact". */
    data class Step(val link: SensorLink, val bpm: Int?, val sample: Boolean)

    fun at(tMs: Long): Step {
        val t = Math.floorMod(tMs, CYCLE_MS)
        val sec = t / 1_000L
        return when (sec) {
            in 60L until 72L -> Step(SensorLink.CONNECTED, null, sample = false)
            in 120L until 135L -> Step(SensorLink.CONNECTED, null, sample = true)
            in 180L until 220L -> Step(SensorLink.CONNECTING, null, sample = false)
            else -> Step(SensorLink.CONNECTED, sine(t), sample = true)
        }
    }

    private fun sine(tMs: Long): Int = (145.0 + 25.0 * sin(2 * PI * tMs / SINE_PERIOD_MS)).roundToInt()
}

/**
 * A heart-rate sensor without Bluetooth for the debug build ([DebugSensors]): plays
 * [SimulatedHeartRateScenario] while leased, from the first lease of the process. Idle without
 * a lease, like the real one. Never in release.
 */
class SimulatedHeartRateSensor(
    private val scope: CoroutineScope,
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos
) : HeartRateSensor {

    private val _status = MutableStateFlow(SensorStatus(SensorLink.IDLE, BATTERY))
    override val status: StateFlow<SensorStatus> = _status.asStateFlow()

    private val _samples = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = BUFFER)
    override val samples: SharedFlow<HeartRateSample> = _samples.asSharedFlow()

    private val _diagnostics = MutableSharedFlow<SensorDiagEvent>(extraBufferCapacity = BUFFER)
    override val diagnostics: SharedFlow<SensorDiagEvent> = _diagnostics.asSharedFlow()

    private var leases = 0
    private var playing: Job? = null

    /** The script's zero: the first lease of the process. */
    private var startedAt: Long? = null

    @Synchronized
    override fun acquire(owner: String): SensorLease {
        if (leases++ == 0) play()
        val released = AtomicBoolean(false)
        return SensorLease {
            if (released.compareAndSet(false, true)) onRelease()
        }
    }

    @Synchronized
    private fun onRelease() {
        if (--leases > 0) return
        playing?.cancel()
        playing = null
        _status.value = SensorStatus(SensorLink.IDLE, BATTERY, _status.value.lastSeenAt)
    }

    private fun play() {
        val zero = startedAt ?: clockNanos().also { startedAt = it }
        playing = scope.launch {
            while (isActive) {
                val now = clockNanos()
                val step = SimulatedHeartRateScenario.at((now - zero) / 1_000_000L)
                val seen = if (step.sample) System.currentTimeMillis() else _status.value.lastSeenAt
                _status.value = SensorStatus(step.link, BATTERY, seen)
                if (step.sample) _samples.tryEmit(HeartRateSample(now, step.bpm))
                delay(TICK_MS)
            }
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val BATTERY = 87
        const val BUFFER = 64
    }
}
