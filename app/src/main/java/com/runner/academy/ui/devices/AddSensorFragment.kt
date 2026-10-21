package com.runner.academy.ui.devices

import android.app.Activity
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.location.LocationManagerCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.databinding.FragmentAddSensorBinding
import com.runner.academy.sensor.FoundSensor
import com.runner.academy.sensor.ProbeFailure
import com.runner.academy.sensor.ScanFailure
import com.runner.academy.sensor.SensorPermissionPolicy
import com.runner.academy.sensor.SensorScanner
import com.runner.academy.sensor.SensorType
import com.runner.academy.util.openAppSystemSettings
import com.runner.academy.util.openLocationSettings
import kotlinx.coroutines.launch

/** Adding a heart-rate monitor: only binds [AddSensorViewModel]'s steps to the views. */
class AddSensorFragment : Fragment() {

    private var _binding: FragmentAddSensorBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AddSensorViewModel by viewModels {
        val app = requireContext().appContainer()
        AddSensorViewModelFactory(
            scanner = app.sensorScanner,
            sdkInt = Build.VERSION.SDK_INT,
            pairedSensor = { app.pairedSensors.value[SensorType.HEART_RATE] },
            pair = app::pairSensor,
            nowMs = SystemClock::elapsedRealtime,
            wallMs = System::currentTimeMillis
        )
    }

    private val sdk = Build.VERSION.SDK_INT
    private val adapter = FoundSensorAdapter(::onDeviceChosen)

    /** The step shown last: announcements are made on changes only. */
    private var shownStep: AddSensorStep? = null
    private var announcedSecondsLeft: Int? = null
    private var leaving = false

    private val scanPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.onPermissionResult(granted(), locationOn(), rationale = results.keys.any { shouldShowRequestPermissionRationale(it) })
    }

    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onBluetoothEnabled()
    }

    private var replaceDialog: AlertDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAddSensorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        shownStep = null
        for (heading in listOf(binding.textViewScanning, binding.textViewHintsTitle, binding.textViewProbing)) {
            ViewCompat.setAccessibilityHeading(heading, true)
        }
        binding.progressScan.max = SensorScanner.SCAN_DURATION_MS.toInt()
        binding.recyclerViewFound.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewFound.adapter = adapter

        binding.buttonPermission.setOnClickListener { onPermissionButton() }
        binding.buttonNotFound.setOnClickListener { viewModel.showHints() }
        binding.buttonScanAgain.setOnClickListener { viewModel.scanAgain() }
        binding.buttonProbeCancel.setOnClickListener { viewModel.cancelProbe() }
        binding.buttonErrorEnable.setOnClickListener { requestBluetooth() }
        binding.buttonErrorRetry.setOnClickListener {
            if (viewModel.state.value is AddSensorStep.ProbeFailed) viewModel.retryProbe() else viewModel.retryScan()
        }
        binding.buttonErrorBack.setOnClickListener { viewModel.backToList() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions or the location switch may have changed in system settings
        viewModel.check(granted(), locationOn())
    }

    private fun granted(): Set<String> = grantedOf(requireContext(), SensorPermissionPolicy.scanPermissions(sdk))

    private fun locationOn(): Boolean {
        val manager = requireContext().getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    private fun render(step: AddSensorStep) {
        val b = _binding ?: return
        val previous = shownStep
        shownStep = step
        b.groupPermission.visibility = visibleIf(step is AddSensorStep.Permission)
        b.groupScan.visibility = visibleIf(step is AddSensorStep.Scanning || step is AddSensorStep.Results)
        b.groupProbing.visibility = visibleIf(step is AddSensorStep.Probing)
        b.groupError.visibility = visibleIf(step is AddSensorStep.ProbeFailed || step is AddSensorStep.ScanFailed)

        // Rendered before it is announced (a failure reads its text), announced before leaving
        if (step is AddSensorStep.ProbeFailed) renderProbeFailed(step)
        AddSensorAnnouncement.of(previous, step)?.let { b.root.announceForAccessibility(textOf(it)) }

        when (step) {
            AddSensorStep.Starting -> Unit
            is AddSensorStep.Permission -> renderPermission(step.prompt)
            is AddSensorStep.Scanning -> renderScanning(step)
            is AddSensorStep.Results -> renderResults(step)
            is AddSensorStep.Probing -> b.textViewProbing.text = getString(R.string.add_sensor_probing, nameOf(step.device))
            is AddSensorStep.ProbeFailed -> Unit
            is AddSensorStep.ScanFailed -> renderScanFailed(step)
            is AddSensorStep.Paired -> onPaired(step)
        }
    }

    private fun renderPermission(prompt: PermissionPrompt) {
        val b = binding
        b.textViewPermissionMessage.setText(
            when (prompt) {
                PermissionPrompt.RATIONALE_31 -> R.string.add_sensor_permission_rationale_31
                PermissionPrompt.RATIONALE_30 -> R.string.add_sensor_permission_rationale_30
                PermissionPrompt.DENIED -> R.string.add_sensor_permission_denied
                PermissionPrompt.LOCATION_OFF -> R.string.add_sensor_location_off
            }
        )
        b.buttonPermission.setText(
            when (prompt) {
                PermissionPrompt.RATIONALE_31, PermissionPrompt.RATIONALE_30 -> R.string.devices_allow
                PermissionPrompt.DENIED -> R.string.devices_open_settings
                PermissionPrompt.LOCATION_OFF -> R.string.add_sensor_open_location
            }
        )
    }

    private fun onPermissionButton() {
        when ((viewModel.state.value as? AddSensorStep.Permission)?.prompt) {
            PermissionPrompt.RATIONALE_31, PermissionPrompt.RATIONALE_30 -> {
                val permissions = SensorPermissionPolicy.scanPermissions(sdk)
                viewModel.onPermissionRequest(rationale = permissions.any { shouldShowRequestPermissionRationale(it) })
                scanPermissionRequest.launch(permissions.toTypedArray())
            }
            PermissionPrompt.DENIED -> openAppSystemSettings()
            PermissionPrompt.LOCATION_OFF -> openLocationSettings()
            null -> Unit
        }
    }

    private fun renderScanning(step: AddSensorStep.Scanning) {
        val b = binding
        b.layoutScanProgress.visibility = View.VISIBLE
        b.progressScan.setProgressCompat((SensorScanner.SCAN_DURATION_MS - step.msLeft).toInt(), true)
        val seconds = ((step.msLeft + 999) / 1000).toInt()
        b.textViewSecondsLeft.text = getString(R.string.add_sensor_seconds_left, seconds)
        // TalkBack hears the remaining time every 5 s at most
        val announced = announcedSecondsLeft
        if (announced == null || announced - seconds >= STATE_EVERY_S || seconds == 0) {
            announcedSecondsLeft = seconds
            ViewCompat.setStateDescription(
                b.progressScan,
                resources.getQuantityString(R.plurals.add_sensor_seconds_left_a11y, seconds, seconds)
            )
        }
        adapter.submitList(step.found)
        b.buttonNotFound.visibility = View.GONE
        b.groupHints.visibility = View.GONE
        b.buttonScanAgain.visibility = View.GONE
    }

    private fun renderResults(step: AddSensorStep.Results) {
        val b = binding
        announcedSecondsLeft = null
        b.layoutScanProgress.visibility = View.GONE
        adapter.submitList(step.found)
        b.groupHints.visibility = visibleIf(step.hintsOpen)
        b.buttonNotFound.visibility = visibleIf(step.found.isNotEmpty() && !step.hintsOpen)
        b.buttonScanAgain.visibility = View.VISIBLE
        val waiting = step.retryInSeconds > 0
        b.buttonScanAgain.isEnabled = !waiting
        if (waiting) {
            b.buttonScanAgain.text = getString(R.string.add_sensor_retry_in, step.retryInSeconds)
            b.buttonScanAgain.contentDescription =
                resources.getQuantityString(R.plurals.add_sensor_retry_in_a11y, step.retryInSeconds, step.retryInSeconds)
        } else {
            b.buttonScanAgain.setText(R.string.add_sensor_scan_again)
            b.buttonScanAgain.contentDescription = null
        }
    }

    private fun renderProbeFailed(step: AddSensorStep.ProbeFailed) {
        val b = binding
        val name = nameOf(step.device)
        b.textViewError.text = when (step.reason) {
            ProbeFailure.NOT_HEART_RATE_SENSOR -> getString(R.string.add_sensor_probe_not_hr, name)
            ProbeFailure.BLUETOOTH_OFF -> getString(R.string.add_sensor_error_bluetooth_off)
            ProbeFailure.UNREACHABLE, ProbeFailure.NO_PERMISSION -> getString(R.string.add_sensor_probe_unreachable, name)
        }
        b.buttonErrorEnable.visibility = visibleIf(step.reason == ProbeFailure.BLUETOOTH_OFF)
        b.buttonErrorRetry.visibility = View.VISIBLE
        b.buttonErrorBack.visibility = View.VISIBLE
    }

    private fun renderScanFailed(step: AddSensorStep.ScanFailed) {
        val b = binding
        val code = step.errorCode
        b.textViewError.text = when (step.reason) {
            ScanFailure.UNSUPPORTED -> getString(R.string.devices_ble_unsupported)
            ScanFailure.BLUETOOTH_OFF -> getString(R.string.add_sensor_error_bluetooth_off)
            // The others never end a scan here (they are steps of their own): the generic text
            ScanFailure.FAILED, ScanFailure.NO_PERMISSION, ScanFailure.LOCATION_OFF, ScanFailure.TOO_FREQUENT ->
                if (code != null) getString(R.string.add_sensor_error_failed, code)
                else getString(R.string.add_sensor_error_failed_no_code)
        }
        b.buttonErrorEnable.visibility = visibleIf(step.reason == ScanFailure.BLUETOOTH_OFF)
        b.buttonErrorRetry.visibility = visibleIf(step.reason == ScanFailure.FAILED)
        b.buttonErrorBack.visibility = View.GONE
    }

    private fun onPaired(step: AddSensorStep.Paired) {
        if (leaving) return
        leaving = true
        val navController = findNavController()
        navController.previousBackStackEntry?.savedStateHandle?.set(
            DevicesFragment.PAIRED_NAME_KEY,
            step.name ?: getString(R.string.devices_unnamed)
        )
        navController.popBackStack()
    }

    private fun onDeviceChosen(device: FoundSensor) {
        val replaced = viewModel.replaces(device)
        if (replaced == null) {
            viewModel.probe(device)
            return
        }
        val oldName = replaced.name ?: getString(R.string.devices_unnamed)
        replaceDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.add_sensor_replace_title, oldName))
            .setMessage(getString(R.string.add_sensor_replace_message, oldName, nameOf(device)))
            .setPositiveButton(R.string.add_sensor_replace) { _, _ -> viewModel.probe(device) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestBluetooth() = requestBluetoothEnable(requireContext(), enableBluetooth)

    private fun textOf(announcement: AddSensorAnnouncement): String = when (announcement) {
        is AddSensorAnnouncement.Found -> getString(R.string.add_sensor_found_a11y, announcement.count)
        AddSensorAnnouncement.NothingFound -> getString(R.string.add_sensor_empty_title)
        is AddSensorAnnouncement.Probing -> getString(R.string.add_sensor_probing, nameOf(announcement.device))
        is AddSensorAnnouncement.ProbeFailed -> binding.textViewError.text.toString()
        is AddSensorAnnouncement.Paired ->
            getString(R.string.devices_paired_snackbar, announcement.name ?: getString(R.string.devices_unnamed))
    }

    private fun nameOf(device: FoundSensor): String = device.name ?: getString(R.string.devices_unnamed)

    private fun visibleIf(visible: Boolean) = if (visible) View.VISIBLE else View.GONE

    override fun onDestroyView() {
        replaceDialog?.dismiss()
        replaceDialog = null
        super.onDestroyView()
        binding.recyclerViewFound.adapter = null
        _binding = null
    }

    private companion object {
        const val STATE_EVERY_S = 5
    }
}
