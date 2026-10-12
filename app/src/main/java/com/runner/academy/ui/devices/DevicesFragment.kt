package com.runner.academy.ui.devices

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.databinding.FragmentDevicesBinding
import com.runner.academy.sensor.SensorPermissionPolicy
import com.runner.academy.sensor.SensorType
import com.runner.academy.util.openAppSystemSettings
import kotlinx.coroutines.launch

/** Paired sensors: one section per type (only the heart-rate monitor for now). */
class DevicesFragment : Fragment() {

    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DevicesViewModel by viewModels {
        val app = requireContext().appContainer()
        DevicesViewModelFactory(
            sensor = app.heartRateSensor,
            paired = app.pairedSensorStore.changes,
            forgetSensor = { app.pairedSensorStore.remove(it) },
            nowMs = SystemClock::elapsedRealtime
        )
    }

    private val connectPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Refused for good only switches the button to "Open settings": the user taps it
        viewModel.onPermissionResult(
            granted = results.isNotEmpty() && results.values.all { it },
            rationale = results.keys.any { shouldShowRequestPermissionRationale(it) }
        )
    }

    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // The sensor watches the adapter itself: nothing to do on the way back
    }

    private var bleSupported = true
    private var menu: PopupMenu? = null
    private var forgetDialog: AlertDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityHeading(binding.textViewSectionHeartRate, true)
        ViewCompat.setScreenReaderFocusable(binding.layoutDeviceRow, true)

        bleSupported = requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        binding.buttonDevicesAdd.visibility = if (bleSupported) View.VISIBLE else View.GONE
        binding.textViewDevicesBleUnsupported.visibility = if (bleSupported) View.GONE else View.VISIBLE
        binding.buttonDevicesAdd.setOnClickListener { openAddSensor() }
        binding.buttonDeviceMenu.setOnClickListener { showMenu(it) }
        binding.buttonDeviceAction.setOnClickListener { onAction() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
        showPairedSnackbar()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
    }

    override fun onStop() {
        viewModel.onStop()
        super.onStop()
    }

    /** Once, after the add-sensor screen paired a device. */
    private fun showPairedSnackbar() {
        val handle = findNavController().currentBackStackEntry?.savedStateHandle ?: return
        handle.getLiveData<String>(PAIRED_NAME_KEY).observe(viewLifecycleOwner) { name ->
            if (name == null) return@observe
            handle.remove<String>(PAIRED_NAME_KEY)
            Snackbar.make(binding.root, getString(R.string.devices_paired_snackbar, name), Snackbar.LENGTH_LONG).show()
        }
    }

    private fun render(state: DevicesState) {
        val b = _binding ?: return
        val row = state.heartRate
        b.layoutDevicesEmpty.visibility = if (row == null) View.VISIBLE else View.GONE
        b.layoutDeviceRow.visibility = if (row == null) View.GONE else View.VISIBLE
        if (row != null) renderRow(row, state.permissionDeniedForGood)
    }

    private fun renderRow(row: DeviceRow, permissionDeniedForGood: Boolean) {
        val b = binding
        val name = displayName(row)
        b.textViewDeviceName.text = name
        b.buttonDeviceMenu.contentDescription = getString(R.string.devices_menu_a11y, name)

        val toneColor = MaterialColors.getColor(
            b.textViewDeviceStatus,
            when (row.tone) {
                DeviceTone.PRIMARY -> androidx.appcompat.R.attr.colorPrimary
                DeviceTone.ERROR -> androidx.appcompat.R.attr.colorError
                DeviceTone.NORMAL -> com.google.android.material.R.attr.colorOnSurface
            }
        )
        val status = getString(row.status)
        if (b.textViewDeviceStatus.text.toString() != status) b.textViewDeviceStatus.text = status
        b.textViewDeviceStatus.setTextColor(toneColor)

        val bpm = row.bpm
        if (bpm == null) {
            b.textViewDeviceBpm.visibility = View.GONE
        } else {
            b.textViewDeviceBpm.visibility = View.VISIBLE
            b.textViewDeviceBpm.text = getString(R.string.devices_bpm, bpm)
            b.textViewDeviceBpm.contentDescription = resources.getQuantityString(R.plurals.heart_rate_beats_per_minute, bpm, bpm)
            b.textViewDeviceBpm.setTextColor(toneColor)
        }

        renderMeta(row)

        val action = row.action
        b.buttonDeviceAction.visibility = if (action == null) View.GONE else View.VISIBLE
        when (action) {
            DeviceAction.ENABLE_BLUETOOTH -> b.buttonDeviceAction.setText(R.string.devices_enable_bluetooth)
            DeviceAction.ALLOW -> b.buttonDeviceAction.setText(
                if (permissionDeniedForGood) R.string.devices_open_settings else R.string.devices_allow
            )
            null -> Unit
        }
    }

    /** "battery 80% · last seen: 11 Oct, 07:40"; TalkBack hears "percent" instead of "%". */
    private fun renderMeta(row: DeviceRow) {
        val meta = binding.textViewDeviceMeta
        val shown = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        row.batteryPercent?.let {
            shown += getString(R.string.devices_battery, it)
            spoken += resources.getQuantityString(R.plurals.devices_battery_a11y, it, it)
        }
        row.lastSeenAt?.let {
            val time = DateUtils.formatDateTime(
                requireContext(), it,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
            )
            val text = getString(R.string.devices_last_seen, time)
            shown += text
            spoken += text
        }
        if (shown.isEmpty()) {
            meta.visibility = View.GONE
            return
        }
        val separator = getString(R.string.devices_meta_separator)
        meta.visibility = View.VISIBLE
        meta.text = shown.joinToString(separator)
        meta.contentDescription = spoken.joinToString(separator)
        meta.setTextColor(
            MaterialColors.getColor(
                meta,
                if (row.batteryLow) androidx.appcompat.R.attr.colorError
                else com.google.android.material.R.attr.colorOnSurfaceVariant
            )
        )
    }

    private fun displayName(row: DeviceRow): String = row.name ?: getString(R.string.devices_unnamed)

    private fun onAction() {
        val state = viewModel.state.value
        when (state.heartRate?.action) {
            DeviceAction.ENABLE_BLUETOOTH -> requestBluetoothEnable(requireContext(), enableBluetooth)
            DeviceAction.ALLOW -> {
                val permissions = SensorPermissionPolicy.connectPermissions(Build.VERSION.SDK_INT)
                if (state.permissionDeniedForGood || permissions.isEmpty()) {
                    openAppSystemSettings()
                } else {
                    viewModel.onPermissionRequest(rationale = permissions.any { shouldShowRequestPermissionRationale(it) })
                    connectPermissionRequest.launch(permissions.toTypedArray())
                }
            }
            null -> Unit
        }
    }

    /** Guarded: a double tap would open it twice. */
    private fun openAddSensor() {
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.nav_devices) navController.navigate(R.id.nav_add_sensor)
    }

    private fun showMenu(anchor: View) {
        val row = viewModel.state.value.heartRate ?: return
        val popup = PopupMenu(requireContext(), anchor)
        // Without Bluetooth LE nothing could replace it
        if (bleSupported) popup.menu.add(0, MENU_REPLACE, 0, R.string.devices_menu_replace)
        popup.menu.add(0, MENU_FORGET, 1, R.string.devices_menu_forget)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_REPLACE -> openAddSensor()
                MENU_FORGET -> confirmForget(displayName(row))
            }
            true
        }
        popup.setOnDismissListener { if (menu === popup) menu = null }
        menu = popup
        popup.show()
    }

    private fun confirmForget(name: String) {
        forgetDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.devices_forget_title, name))
            .setMessage(R.string.devices_forget_message)
            .setPositiveButton(R.string.devices_forget_confirm) { _, _ -> viewModel.forget(SensorType.HEART_RATE) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        menu?.dismiss()
        menu = null
        forgetDialog?.dismiss()
        forgetDialog = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Set on this screen's back-stack entry by [AddSensorFragment] after pairing. */
        const val PAIRED_NAME_KEY = "paired_name"

        private const val MENU_REPLACE = 1
        private const val MENU_FORGET = 2
    }
}
