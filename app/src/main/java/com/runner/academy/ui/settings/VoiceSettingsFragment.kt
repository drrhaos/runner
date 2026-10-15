package com.runner.academy.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import androidx.appcompat.app.AlertDialog
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.databinding.FragmentVoiceSettingsBinding
import com.runner.academy.databinding.LayoutVoiceChoiceRowBinding
import com.runner.academy.databinding.LayoutVoiceMetricsRowBinding
import com.runner.academy.databinding.LayoutVoiceSwitchRowBinding
import com.runner.academy.voice.SummaryEvery
import com.runner.academy.voice.SummaryMetric
import com.runner.academy.voice.VoiceSettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Voice coaching: the master switch, the two profiles (picked by whether the workout has
 * segments) and the pace tolerance. No Save button: each change goes to the preferences at once
 * and the service reads them on its next tick.
 */
class VoiceSettingsFragment : Fragment() {

    private var _binding: FragmentVoiceSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels {
        val app = requireContext().appContainer()
        SettingsViewModelFactory(requireContext(), app.userPreferences, app.pairedSensors, app.heartRateSensor.status)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVoiceSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        for (heading in listOf(binding.textViewSectionFree, binding.textViewSectionIntervals, binding.textViewSectionTarget)) {
            ViewCompat.setAccessibilityHeading(heading, true)
        }
        setUpRows()
        viewLifecycleOwner.lifecycleScope.launch {
            combine(viewModel.settingsState, viewModel.hasHeartRateSensor) { state, hasSensor ->
                VoiceScreen.of(state.voice, hasSensor, state.unitSystem)
            }.collect { show(it) }
        }
    }

    private fun setUpRows() = with(binding) {
        rowVoiceEnabled.setTexts(R.string.voice_enabled_label, R.string.voice_enabled_hint)
        rowIntervalsSegmentChange.setTexts(R.string.voice_segment_change_label, R.string.voice_segment_change_hint)
        rowIntervalsTargetCheck.setTexts(R.string.voice_target_check_label, R.string.voice_target_check_hint)
        for (row in listOf(rowFreeSummary, rowIntervalsSummary)) {
            row.textViewLabel.setText(R.string.voice_summary_label)
        }
        rowPaceTolerance.textViewLabel.setText(R.string.voice_pace_tolerance_label)
        for (row in listOf(rowFreeSummary, rowIntervalsSummary, rowPaceTolerance)) {
            // At 200 % the value wraps within half the row instead of squeezing the label
            row.root.doOnLayout { row.textViewValue.maxWidth = it.width / 2 }
        }

        rowVoiceEnabled.root.onClickIfEnabled { viewModel.updateVoiceEnabled(!current.enabled) }
        rowFreeSummary.root.onClickIfEnabled { showSummaryDialog(VoiceProfileKind.FREE) }
        rowIntervalsSummary.root.onClickIfEnabled { showSummaryDialog(VoiceProfileKind.INTERVALS) }
        rowFreeMetrics.root.onClickIfEnabled { showMetricsDialog(VoiceProfileKind.FREE) }
        rowIntervalsMetrics.root.onClickIfEnabled { showMetricsDialog(VoiceProfileKind.INTERVALS) }
        rowIntervalsSegmentChange.root.onClickIfEnabled {
            viewModel.updateVoiceSegmentChange(!current.intervals.segmentChange)
        }
        rowIntervalsTargetCheck.root.onClickIfEnabled {
            viewModel.updateVoiceTargetCheck(!current.intervals.targetCheck)
        }
        rowPaceTolerance.root.onClickIfEnabled { showToleranceDialog() }
    }

    private val current: VoiceSettings get() = viewModel.settingsState.value.voice

    private fun show(screen: VoiceScreen) = with(binding) {
        rowVoiceEnabled.show(screen.enabled, enabled = true)

        showSummary(rowFreeSummary, screen.free)
        showMetrics(rowFreeMetrics, screen.free)
        showSummary(rowIntervalsSummary, screen.intervals)
        showMetrics(rowIntervalsMetrics, screen.intervals)
        rowIntervalsSegmentChange.show(screen.intervals.segmentChange, screen.intervals.rowsEnabled)
        rowIntervalsTargetCheck.show(screen.intervals.targetCheck, screen.intervals.rowsEnabled)

        rowPaceTolerance.showValue(getString(R.string.voice_pace_tolerance_value, screen.paceToleranceSec))
        rowPaceTolerance.root.setRowEnabled(screen.toleranceEnabled)

        textViewUnitsNote.visibility = if (screen.kilometresNote) View.VISIBLE else View.GONE
    }

    private fun showSummary(row: LayoutVoiceChoiceRowBinding, profile: VoiceProfileRows) {
        row.showValue(getString(VoiceSettingsTexts.every(profile.summary)))
        row.root.setRowEnabled(profile.rowsEnabled)
    }

    private fun showMetrics(row: LayoutVoiceMetricsRowBinding, profile: VoiceProfileRows) {
        row.textViewValue.text = VoiceSettingsTexts.metricsLower(profile.metrics)
            .joinToString(", ") { getString(it) }
        row.textViewHrNote.visibility = if (profile.heartRateSkipped) View.VISIBLE else View.GONE
        row.root.setRowEnabled(profile.metricsEnabled)
    }

    /** "Summary, every 1 km" as one group. */
    private fun LayoutVoiceChoiceRowBinding.showValue(value: String) {
        textViewValue.text = value
        root.contentDescription = textViewLabel.text.toString() + ", " + value
    }

    /** The switch only shows the state; TalkBack hears it from the row, a checkable group. */
    private fun LayoutVoiceSwitchRowBinding.setTexts(label: Int, hint: Int) {
        textViewLabel.setText(label)
        textViewHint.setText(hint)
        ViewCompat.setAccessibilityDelegate(root, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = switchValue.isChecked
            }
        })
    }

    private fun LayoutVoiceSwitchRowBinding.show(checked: Boolean, enabled: Boolean) {
        switchValue.isChecked = checked
        switchValue.isEnabled = enabled
        root.setRowEnabled(enabled)
    }

    /** Greyed out but still focusable: TalkBack says "disabled"; the value stays readable. */
    private fun View.setRowEnabled(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    /** performClick reaches the listener of a disabled view too: it must not act then. */
    private fun View.onClickIfEnabled(action: () -> Unit) = setOnClickListener { if (it.isEnabled) action() }

    private fun showSummaryDialog(kind: VoiceProfileKind) {
        val profile = if (kind == VoiceProfileKind.FREE) current.free else current.intervals
        val options = SummaryEvery.entries
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.voice_summary_dialog_title)
            .setSingleChoiceItems(
                options.map { getString(VoiceSettingsTexts.every(it)) }.toTypedArray(),
                options.indexOf(profile.activeSummary)
            ) { dialog, which ->
                viewModel.updateVoiceSummary(kind, options[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Done is off while nothing is ticked: an empty summary is turned off by its interval. */
    private fun showMetricsDialog(kind: VoiceProfileKind) {
        val metrics = if (kind == VoiceProfileKind.FREE) current.free.metrics else current.intervals.metrics
        val options = SummaryMetric.entries
        val ticked = BooleanArray(options.size) { options[it] in metrics }
        lateinit var dialog: AlertDialog
        fun updateDone() {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = ticked.any { it }
        }
        dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.voice_metrics_dialog_title)
            .setMultiChoiceItems(options.map { getString(VoiceSettingsTexts.metric(it)) }.toTypedArray(), ticked) { _, which, isChecked ->
                ticked[which] = isChecked
                updateDone()
            }
            .setPositiveButton(R.string.voice_metrics_done) { _, _ ->
                viewModel.updateVoiceMetrics(kind, options.filterIndexed { i, _ -> ticked[i] }.toSet())
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener { updateDone() }
        dialog.show()
    }

    private fun showToleranceDialog() {
        val options = VoiceSettings.PACE_TOLERANCES_SEC
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.voice_pace_tolerance_label)
            .setSingleChoiceItems(
                options.map { getString(R.string.voice_pace_tolerance_value, it) }.toTypedArray(),
                options.indexOf(current.paceToleranceSec)
            ) { dialog, which ->
                viewModel.updateVoicePaceTolerance(options[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val DISABLED_ALPHA = 0.38f
    }
}
