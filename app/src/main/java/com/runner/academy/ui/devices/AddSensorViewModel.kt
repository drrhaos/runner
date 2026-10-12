package com.runner.academy.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runner.academy.sensor.FoundSensor
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.ProbeFailure
import com.runner.academy.sensor.ProbeResult
import com.runner.academy.sensor.ScanBlocker
import com.runner.academy.sensor.ScanException
import com.runner.academy.sensor.ScanFailure
import com.runner.academy.sensor.SensorPermissionPolicy
import com.runner.academy.sensor.SensorScanner
import com.runner.academy.sensor.SensorType
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What step 1 asks for. */
enum class PermissionPrompt {
    /** 31+: "Nearby devices", location is not used. */
    RATIONALE_31,

    /** 30 and below: precise location is what finds Bluetooth devices. */
    RATIONALE_30,

    /** Refused for good: only the app's system settings can grant it. */
    DENIED,

    /** 30 and below: the location switch is off. */
    LOCATION_OFF
}

/** A device in the scan list. */
data class FoundRow(val sensor: FoundSensor, val signal: SignalLevel, val alreadyPaired: Boolean)

/** The add-sensor screen: one step at a time, one group of views each. */
sealed interface AddSensorStep {
    /** Before the first permission check. */
    data object Starting : AddSensorStep

    data class Permission(val prompt: PermissionPrompt) : AddSensorStep

    /** A scan running for [msLeft] more. */
    data class Scanning(val found: List<FoundRow>, val msLeft: Long) : AddSensorStep

    /**
     * The scan is over (or waiting to start). [retryInSeconds] > 0: "Search again" is waiting
     * out the system's start limit and will start the scan by itself.
     */
    data class Results(val found: List<FoundRow>, val hintsOpen: Boolean, val retryInSeconds: Int) : AddSensorStep

    data class Probing(val device: FoundSensor) : AddSensorStep

    /** [reason] is never [ProbeFailure.NO_PERMISSION]: that goes back to [Permission]. */
    data class ProbeFailed(val device: FoundSensor, val reason: ProbeFailure) : AddSensorStep

    /** [reason]: [ScanFailure.UNSUPPORTED], [ScanFailure.BLUETOOTH_OFF] or [ScanFailure.FAILED] with [errorCode]. */
    data class ScanFailed(val reason: ScanFailure, val errorCode: Int?) : AddSensorStep

    /** Saved: the screen goes back with [name]. */
    data class Paired(val name: String?) : AddSensorStep
}

/** What TalkBack hears on a step change: results, not every row or tick. */
sealed interface AddSensorAnnouncement {
    data class Found(val count: Int) : AddSensorAnnouncement
    data object NothingFound : AddSensorAnnouncement
    data class Probing(val device: FoundSensor) : AddSensorAnnouncement
    data class ProbeFailed(val device: FoundSensor, val reason: ProbeFailure) : AddSensorAnnouncement
    data class Paired(val name: String?) : AddSensorAnnouncement

    companion object {
        fun of(previous: AddSensorStep?, next: AddSensorStep): AddSensorAnnouncement? = when {
            // A countdown to retry (start limit) is not the end of a scan: nothing was searched
            previous is AddSensorStep.Scanning && next is AddSensorStep.Results && next.retryInSeconds == 0 ->
                if (next.found.isEmpty()) NothingFound else Found(next.found.size)
            previous is AddSensorStep.Probing && next is AddSensorStep.Paired -> Paired(next.name)
            previous !is AddSensorStep.Probing && next is AddSensorStep.Probing -> Probing(next.device)
            previous is AddSensorStep.Probing && next is AddSensorStep.ProbeFailed -> ProbeFailed(next.device, next.reason)
            else -> null
        }
    }
}

/**
 * Adding a heart-rate sensor: permission → scan → choose → probe → pair.
 *
 * @param pairedSensor the heart-rate sensor paired now.
 * @param pair saves the checked device ([com.runner.academy.sensor.PairedSensorStore.put]).
 * @param nowMs a monotonic clock for the scan's countdown; [wallMs] for `pairedAt`.
 */
class AddSensorViewModel(
    private val scanner: SensorScanner,
    private val sdkInt: Int,
    private val pairedSensor: () -> PairedSensor?,
    private val pair: (PairedSensor) -> Unit,
    private val nowMs: () -> Long,
    private val wallMs: () -> Long
) : ViewModel() {

    private val _state = MutableStateFlow<AddSensorStep>(AddSensorStep.Starting)
    val state: StateFlow<AddSensorStep> = _state.asStateFlow()

    private var step: AddSensorStep
        get() = _state.value
        set(value) {
            _state.value = value
        }

    private var found: List<FoundSensor> = emptyList()
    private var permanentlyDenied = false
    private val permissionAsks = PermissionAsks()
    private var scanJob: Job? = null
    private var probeJob: Job? = null

    /** Called on every return to the screen: the user may have changed things in Settings. */
    fun check(granted: Set<String>, locationOn: Boolean) {
        val blocker = SensorPermissionPolicy.canScan(sdkInt, granted, locationOn)
        if (blocker == null) {
            permanentlyDenied = false
            if (step == AddSensorStep.Starting || step is AddSensorStep.Permission) startScan()
            return
        }
        if (step is AddSensorStep.Paired) return
        cancelAll()
        step = AddSensorStep.Permission(prompt(blocker))
    }

    /** Call right before the system dialog, with whether a rationale would be shown now. */
    fun onPermissionRequest(rationale: Boolean) {
        permissionAsks.beforeRequest(rationale)
    }

    /** After the system dialog; [rationale]: whether the system would show one now. */
    fun onPermissionResult(granted: Set<String>, locationOn: Boolean, rationale: Boolean) {
        val blocker = SensorPermissionPolicy.canScan(sdkInt, granted, locationOn)
        if (blocker == ScanBlocker.NO_PERMISSION && permissionAsks.afterDenial(rationale)) permanentlyDenied = true
        check(granted, locationOn)
    }

    private fun prompt(blocker: ScanBlocker): PermissionPrompt = when {
        blocker == ScanBlocker.LOCATION_OFF -> PermissionPrompt.LOCATION_OFF
        permanentlyDenied -> PermissionPrompt.DENIED
        sdkInt >= SensorPermissionPolicy.NEARBY_DEVICES_SDK -> PermissionPrompt.RATIONALE_31
        else -> PermissionPrompt.RATIONALE_30
    }

    fun scanAgain() = startScan()

    fun showHints() {
        (step as? AddSensorStep.Results)?.let { step = it.copy(hintsOpen = true) }
    }

    private fun startScan() {
        cancelAll()
        scanJob = viewModelScope.launch {
            var waitMs = scanner.waitBeforeScanMs()
            while (true) {
                if (waitMs > 0) countdown(waitMs)
                waitMs = runScan() ?: break
            }
        }
    }

    /** "Retry in N s" by seconds until [waitMs] is over. */
    private suspend fun countdown(waitMs: Long) {
        val hintsOpen = (step as? AddSensorStep.Results)?.hintsOpen ?: false
        val seconds = ((waitMs + SECOND_MS - 1) / SECOND_MS).toInt()
        for (left in seconds downTo 1) {
            step = AddSensorStep.Results(rows(found), hintsOpen, left)
            delay(if (left == seconds) waitMs - (seconds - 1) * SECOND_MS else SECOND_MS)
        }
    }

    /** One scan to its end; returns how long to wait before retrying, or null when done. */
    private suspend fun runScan(): Long? = coroutineScope {
        val startedAt = nowMs()
        fun msLeft() = (SensorScanner.SCAN_DURATION_MS - (nowMs() - startedAt)).coerceAtLeast(0L)
        found = emptyList()
        step = AddSensorStep.Scanning(emptyList(), SensorScanner.SCAN_DURATION_MS)
        val ticker = launch {
            while (true) {
                delay(SECOND_MS)
                (step as? AddSensorStep.Scanning)?.let { step = it.copy(msLeft = msLeft()) }
            }
        }
        try {
            scanner.scan().collect { list ->
                found = list
                step = AddSensorStep.Scanning(rows(list), msLeft())
            }
        } catch (e: ScanException) {
            ticker.cancel()
            return@coroutineScope onScanFailed(e)
        }
        ticker.cancel()
        step = AddSensorStep.Results(rows(found), hintsOpen = found.isEmpty(), retryInSeconds = 0)
        null
    }

    private fun onScanFailed(e: ScanException): Long? {
        when (e.reason) {
            ScanFailure.TOO_FREQUENT -> return e.waitMs.coerceAtLeast(SECOND_MS)
            ScanFailure.NO_PERMISSION -> step = AddSensorStep.Permission(prompt(ScanBlocker.NO_PERMISSION))
            ScanFailure.LOCATION_OFF -> step = AddSensorStep.Permission(PermissionPrompt.LOCATION_OFF)
            ScanFailure.UNSUPPORTED, ScanFailure.BLUETOOTH_OFF ->
                step = AddSensorStep.ScanFailed(e.reason, null)
            ScanFailure.FAILED -> step = AddSensorStep.ScanFailed(e.reason, e.errorCode)
        }
        return null
    }

    private fun rows(list: List<FoundSensor>): List<FoundRow> {
        val pairedAddress = pairedSensor()?.address
        return list.map { FoundRow(it, SignalLevel.of(it.rssi), it.address == pairedAddress) }
    }

    /** The paired device that choosing [device] would replace (ask first), or null. */
    fun replaces(device: FoundSensor): PairedSensor? =
        pairedSensor()?.takeIf { it.address != device.address }

    /** Stops the scan and checks [device]; pairs it when it is a working heart-rate sensor. */
    fun probe(device: FoundSensor) {
        cancelAll()
        step = AddSensorStep.Probing(device)
        probeJob = viewModelScope.launch {
            when (val result = scanner.probe(device.address)) {
                is ProbeResult.Ok -> {
                    val name = device.name ?: result.name
                    pair(PairedSensor(SensorType.HEART_RATE, device.address, name, wallMs(), null, result.batteryPercent))
                    step = AddSensorStep.Paired(name)
                }
                is ProbeResult.Failed -> step =
                    if (result.reason == ProbeFailure.NO_PERMISSION) {
                        AddSensorStep.Permission(prompt(ScanBlocker.NO_PERMISSION))
                    } else {
                        AddSensorStep.ProbeFailed(device, result.reason)
                    }
            }
        }
    }

    fun retryProbe() {
        (step as? AddSensorStep.ProbeFailed)?.let { probe(it.device) }
    }

    fun cancelProbe() {
        if (step !is AddSensorStep.Probing) return
        backToList()
    }

    fun backToList() {
        cancelAll()
        step = AddSensorStep.Results(rows(found), hintsOpen = false, retryInSeconds = 0)
    }

    /** `RESULT_OK` from the system "turn on Bluetooth" request. */
    fun onBluetoothEnabled() {
        when (val current = step) {
            is AddSensorStep.ScanFailed -> if (current.reason == ScanFailure.BLUETOOTH_OFF) startScan()
            is AddSensorStep.ProbeFailed -> if (current.reason == ProbeFailure.BLUETOOTH_OFF) probe(current.device)
            else -> Unit
        }
    }

    /** Retry after [ScanFailure.FAILED]. */
    fun retryScan() = startScan()

    /** Stops a running scan, countdown or probe. */
    fun cancelAll() {
        scanJob?.cancel()
        scanJob = null
        probeJob?.cancel()
        probeJob = null
    }

    override fun onCleared() {
        cancelAll()
    }

    private companion object {
        const val SECOND_MS = 1_000L
    }
}

class AddSensorViewModelFactory(
    private val scanner: SensorScanner,
    private val sdkInt: Int,
    private val pairedSensor: () -> PairedSensor?,
    private val pair: (PairedSensor) -> Unit,
    private val nowMs: () -> Long,
    private val wallMs: () -> Long
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AddSensorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AddSensorViewModel(scanner, sdkInt, pairedSensor, pair, nowMs, wallMs) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
