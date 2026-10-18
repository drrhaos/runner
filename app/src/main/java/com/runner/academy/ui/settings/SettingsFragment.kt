package com.runner.academy.ui.settings

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.runner.academy.BuildConfig
import com.runner.academy.NavDestinations
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.databinding.DialogHrMaxBinding
import com.runner.academy.databinding.FragmentSettingsBinding
import com.runner.academy.debug.DebugSensors
import com.runner.academy.ui.workout.HeartRateText
import com.runner.academy.util.HrMax
import com.google.android.material.color.MaterialColors
import com.runner.academy.util.StepPermissionPolicy
import com.runner.academy.util.StepTrackingAccess
import com.runner.academy.util.openAppSystemSettings
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels {
        val app = requireContext().appContainer()
        SettingsViewModelFactory(
            requireContext(),
            app.userPreferences,
            app.pairedSensors,
            app.heartRateSensor.status
        )
    }

    /** Set while the UI writes switch states, so programmatic changes do not act as taps. */
    private var bindingState = false

    private var holdHints: HoldHints? = null

    private val stepPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onStepPermissionResult(granted)
        // Not granted and no rationale: "don't ask again" — only system settings can help now.
        if (!granted && !shouldShowRequestPermissionRationale(StepPermissionPolicy.PERMISSION)) {
            _binding?.let { b ->
                Snackbar.make(b.root, R.string.steps_permission_denied, Snackbar.LENGTH_LONG)
                    .setAction(R.string.steps_permission_open_settings) { openAppSystemSettings() }
                    .show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Debug card: debug builds only; the simulated heart-rate switch (release inflates a Space)
        binding.cardDebug.isVisible = BuildConfig.DEBUG
        DebugSensors.bind(binding.stubDebugSettings.inflate())
        setupClickListeners()
        setupHoldHints()
        observeViewModel()
    }

    /** Explanations on touch and hold; the state warnings are read from the current state. */
    private fun setupHoldHints() {
        val hints = HoldHints(binding.root).also { holdHints = it }
        val state = { viewModel.settingsState.value }
        hints.attach(binding.rowAutoPause) {
            SettingsHintTexts.join(requireContext(), SettingsHintTexts.autoPause(state().hasStepSensor, state().stepsForDistance))
        }
        hints.attach(binding.rowStepsForDistance) {
            SettingsHintTexts.join(requireContext(), SettingsHintTexts.steps(state().hasStepSensor))
        }
        hints.attach(binding.rowGpsDiagnostics) { getString(R.string.settings_hint_gps_diagnostics) }
        HoldHints.sayOnce(binding.root, requireContext().appContainer().userPreferences)
    }

    private fun setupClickListeners() {
        // Вес пользователя
        binding.rowWeight.setOnClickListener {
            showWeightDialog()
        }

        // Рост пользователя
        binding.rowHeight.setOnClickListener {
            showHeightDialog()
        }

        // Дата рождения
        binding.rowAge.setOnClickListener {
            showBirthDateDialog()
        }

        // Максимальный пульс
        binding.rowHrMax.setOnClickListener {
            showHrMaxDialog()
        }

        // Пол пользователя
        binding.rowGender.setOnClickListener {
            showGenderDialog()
        }

        // Система единиц
        binding.rowUnitSystem.setOnClickListener {
            showUnitSystemDialog()
        }

        // Тема приложения
        binding.rowThemeMode.setOnClickListener {
            showThemeModeDialog()
        }

        // Язык приложения
        binding.rowLanguage.setOnClickListener {
            showLanguageDialog()
        }

        // Обратный отсчет перед стартом
        binding.rowStartCountdown.setOnClickListener {
            showStartCountdownDialog()
        }

        // Голосовые подсказки: свой экран, главный переключатель там
        binding.rowVoiceSettings.setOnClickListener {
            val navController = findNavController()
            if (navController.currentDestination?.id in NavDestinations.settings) navController.navigate(R.id.nav_voice_settings)
        }
        ViewCompat.replaceAccessibilityAction(
            binding.rowVoiceSettings,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            getString(R.string.settings_voice_open_a11y),
            null
        )

        // Автопауза: нажатие на всю строку переключает свитч
        binding.rowAutoPause.setOnClickListener { binding.switchAutoPause.toggle() }
        binding.switchAutoPause.setOnCheckedChangeListener { _, isChecked ->
            if (bindingState) return@setOnCheckedChangeListener
            viewModel.updateAutoPause(isChecked)
        }

        // Диагностика GPS: в карточке «Отладка»
        binding.switchGpsDiagnostics.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateGpsDiagnostics(isChecked)
        }

        // Шаги для дистанции при потере GPS
        binding.switchStepsForDistance.setOnCheckedChangeListener { _, isChecked ->
            if (bindingState) return@setOnCheckedChangeListener
            onStepsForDistanceToggled(isChecked)
        }

        // Устройства (пульсометр)
        binding.rowDevices.setOnClickListener {
            // A double tap would open it twice
            val navController = findNavController()
            if (navController.currentDestination?.id in NavDestinations.settings) navController.navigate(R.id.nav_devices)
        }

        // Сброс настроек
        binding.buttonResetSettings.setOnClickListener {
            showResetConfirmationDialog()
        }

        // Лицензии открытого ПО
        binding.rowOssLicenses.setOnClickListener {
            OpenSourceLicensesDialog.show(requireContext())
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.settingsState.collect { settings ->
                updateUI(settings)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.devicesSummary.collect { summary ->
                val name = summary.sensorName ?: getString(R.string.devices_unnamed)
                binding.textViewDevicesSummary.text =
                    if (summary.hasSensor) getString(summary.text, name) else getString(summary.text)
            }
        }
    }

    private fun updateUI(settings: SettingsState) {
        // Профиль пользователя
        binding.textViewWeightValue.text = getString(R.string.settings_weight_format, settings.userWeight.toInt())
        binding.textViewHeightValue.text = getString(R.string.settings_height_format, settings.userHeight.toInt())
        binding.textViewAgeValue.text = if (settings.userAge > 0) getString(R.string.settings_age_format, settings.userAge) else getString(R.string.settings_gender_unknown)
        binding.textViewGenderValue.text = getGenderDisplayName(settings.userGender)
        showHrMax(settings.hrMax)

        // Настройки приложения
        binding.textViewUnitSystemValue.text = viewModel.getUnitSystemDisplayName(settings.unitSystem)
        binding.textViewThemeModeValue.text = viewModel.getThemeModeDisplayName(settings.themeMode)
        binding.textViewLanguageValue.text = viewModel.getLanguageDisplayName(settings.appLanguage)
        binding.textViewStartCountdownValue.text = "${settings.startCountdownSeconds} s"
        showVoiceSummary(settings)
        binding.switchGpsDiagnostics.isChecked = settings.gpsDiagnostics
        bindingState = true
        binding.switchStepsForDistance.isChecked = settings.stepsForDistance && settings.hasStepSensor
        bindingState = false
        binding.switchStepsForDistance.isEnabled = settings.hasStepSensor
        bindingState = true
        binding.switchAutoPause.isChecked = settings.autoPause
        bindingState = false
    }

    /** "On · summary every 1 km"; TalkBack reads the label and the subtitle as one row. */
    private fun showVoiceSummary(settings: SettingsState) {
        val summary = VoiceRowSummary.of(settings.voice)
        val text = summary.every?.let { getString(summary.text, getString(it)) } ?: getString(summary.text)
        binding.textViewVoiceSummary.text = text
        binding.rowVoiceSettings.contentDescription = getString(R.string.settings_label_voice) + ", " + text
    }

    override fun onResume() {
        super.onResume()
        // The permission may have been changed in system settings meanwhile.
        viewModel.refreshStepsForDistance()
        // Back from the voice screen, which writes through its own view model.
        viewModel.refreshVoice()
    }

    private fun onStepsForDistanceToggled(enabled: Boolean) {
        viewModel.updateStepsForDistance(enabled)
        if (enabled && !StepTrackingAccess.hasPermission(requireContext())) {
            stepPermissionRequest.launch(StepPermissionPolicy.PERMISSION)
        }
    }

    private fun showWeightDialog() {
        val currentWeight = viewModel.settingsState.value.userWeight.toInt()
        val input = android.widget.EditText(requireContext()).apply {
            setText(currentWeight.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_dialog_weight_title))
            .setMessage(getString(R.string.settings_dialog_weight_message))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val weightText = input.text.toString()
                val weight = weightText.toFloatOrNull()
                if (weight != null && weight > 0 && weight <= 300) {
                    viewModel.updateUserWeight(weight)
                    Toast.makeText(requireContext(), getString(R.string.settings_weight_saved), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.settings_dialog_weight_invalid), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showUnitSystemDialog() {
        val options = viewModel.getUnitSystemOptions()
        val currentSystem = viewModel.settingsState.value.unitSystem
        val currentIndex = options.indexOf(currentSystem)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_dialog_units_title))
            .setSingleChoiceItems(
                options.map { viewModel.getUnitSystemDisplayName(it) }.toTypedArray(),
                currentIndex
            ) { dialog, which ->
                viewModel.updateUnitSystem(options[which])
                dialog.dismiss()
                Toast.makeText(requireContext(), getString(R.string.settings_units_changed), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showThemeModeDialog() {
        val options = viewModel.getThemeModeOptions()
        val currentMode = viewModel.settingsState.value.themeMode
        val currentIndex = options.indexOf(currentMode).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_dialog_theme_title))
            .setSingleChoiceItems(
                options.map { viewModel.getThemeModeDisplayName(it) }.toTypedArray(),
                currentIndex
            ) { dialog, which ->
                viewModel.updateThemeMode(options[which])
                dialog.dismiss()
                Toast.makeText(requireContext(), getString(R.string.settings_theme_changed), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showLanguageDialog() {
        val options = viewModel.getLanguageOptions()
        val currentLanguage = viewModel.settingsState.value.appLanguage
        val currentIndex = options.indexOf(currentLanguage).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_dialog_language_title))
            .setSingleChoiceItems(
                options.map { viewModel.getLanguageDisplayName(it) }.toTypedArray(),
                currentIndex
            ) { dialog, which ->
                viewModel.updateAppLanguage(options[which])
                dialog.dismiss()
                Toast.makeText(requireContext(), getString(R.string.settings_language_changed), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showStartCountdownDialog() {
        val options = viewModel.getStartCountdownOptions()
        val current = viewModel.settingsState.value.startCountdownSeconds
        val currentIndex = options.indexOf(current).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_dialog_start_countdown_title))
            .setSingleChoiceItems(
                options.map { "${it} s" }.toTypedArray(),
                currentIndex
            ) { dialog, which ->
                viewModel.updateStartCountdownSeconds(options[which])
                dialog.dismiss()
                Toast.makeText(requireContext(), getString(R.string.settings_start_countdown_changed), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showResetConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_dialog_reset_title))
            .setMessage(getString(R.string.settings_dialog_reset_message))
            .setPositiveButton(getString(R.string.settings_dialog_reset_title)) { _, _ ->
                viewModel.resetToDefaults()
                Toast.makeText(requireContext(), getString(R.string.settings_reset_done), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun showHeightDialog() {
        val currentHeight = viewModel.settingsState.value.userHeight.toInt()
        val input = android.widget.EditText(requireContext()).apply {
            setText(currentHeight.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_dialog_height_title))
            .setMessage(getString(R.string.settings_dialog_height_message))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val height = input.text.toString().toFloatOrNull()
                if (height != null && height > 0 && height < 250) {
                    viewModel.updateUserHeight(height)
                    Toast.makeText(requireContext(), getString(R.string.settings_height_saved), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.settings_dialog_height_invalid), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showBirthDateDialog() {
        val currentBirthDate = viewModel.settingsState.value.userBirthDate
        val calendar = Calendar.getInstance()
        // 0 is "not set"; a date before 1970 is negative and real
        if (currentBirthDate != 0L) {
            calendar.timeInMillis = currentBirthDate
        } else {
            calendar.add(Calendar.YEAR, -25)
        }

        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth, 0, 0, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                viewModel.updateUserBirthDate(calendar.timeInMillis)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_birthdate_saved),
                    Toast.LENGTH_SHORT
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).apply {
            setTitle(getString(R.string.settings_dialog_birthdate_title))
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    /** The value, where it comes from (or why the zones are off), read by TalkBack as one row. */
    private fun showHrMax(hrMax: HrMax?) {
        val value = binding.textViewHrMaxValue
        val source = binding.textViewHrMaxSource
        if (hrMax == null) {
            value.setText(R.string.settings_hr_max_none)
            value.setTextColor(MaterialColors.getColor(value, com.google.android.material.R.attr.colorOnSurfaceVariant))
            source.setText(R.string.settings_hr_max_source_none)
            binding.rowHrMax.contentDescription = getString(R.string.settings_hr_max_none_a11y)
            return
        }
        value.text = String.format(Locale.getDefault(), "%d", hrMax.bpm)
        value.setTextColor(MaterialColors.getColor(value, androidx.appcompat.R.attr.colorPrimary))
        val age = hrMax.ageYears
        source.text = if (age != null) {
            getString(R.string.settings_hr_max_source_auto, age)
        } else {
            getString(R.string.settings_hr_max_source_manual)
        }
        binding.rowHrMax.contentDescription = getString(
            R.string.settings_hr_max_a11y,
            HeartRateText.beatsPerMinute(requireContext(), hrMax.bpm),
            getString(if (age != null) R.string.hr_max_source_auto_short else R.string.settings_hr_max_source_manual)
        )
    }

    /**
     * A picker of 100..230 with the zones it gives; Save writes a manual value (unless an
     * automatic one was left as it was), "By age" goes back to the automatic one.
     */
    private fun showHrMaxDialog() {
        val state = viewModel.settingsState.value
        val current = state.hrMax
        val byAge = state.hrMaxByAge
        val dialogBinding = DialogHrMaxBinding.inflate(layoutInflater)
        dialogBinding.textViewHrMaxExplain.text = when (HrMaxDialog.explanation(state.userBirthDate, byAge)) {
            HrMaxDialog.Explanation.FORMULA ->
                getString(R.string.settings_dialog_hr_max_auto_formula, byAge?.ageYears, byAge?.bpm)
            HrMaxDialog.Explanation.NO_BIRTH_DATE -> getString(R.string.settings_dialog_hr_max_no_birth)
            HrMaxDialog.Explanation.AGE_OUT_OF_RANGE ->
                getString(R.string.settings_dialog_hr_max_age_out_of_range)
        }
        val picker = dialogBinding.numberPickerHrMax
        picker.minValue = HrMax.MIN_MANUAL_BPM
        picker.maxValue = HrMax.MAX_MANUAL_BPM
        picker.wrapSelectorWheel = false
        picker.value = HrMaxDialog.initialBpm(current)
        fun showPreview(bpm: Int) {
            dialogBinding.textViewHrMaxZonesPreview.text = HrMaxDialog.zonesPreview(bpm).joinToString("\n") { (zone, range) ->
                getString(R.string.settings_hr_max_zone_line, zone, range.first, range.last)
            }
        }
        showPreview(picker.value)
        picker.setOnValueChangedListener { _, _, bpm -> showPreview(bpm) }

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_dialog_hr_max_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                HrMaxDialog.manualToSave(current, picker.value)?.let {
                    viewModel.updateHrMaxManual(it)
                    Toast.makeText(requireContext(), getString(R.string.settings_hr_max_saved), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
        val (neutralLabel, neutralToast) = when (HrMaxDialog.neutral(current, byAge)) {
            HrMaxDialog.Neutral.BY_AGE -> R.string.settings_dialog_hr_max_auto to R.string.settings_hr_max_saved
            HrMaxDialog.Neutral.ZONES_OFF -> R.string.settings_dialog_hr_max_zones_off to R.string.settings_hr_max_zones_off_done
            null -> null to null
        }
        if (neutralLabel != null && neutralToast != null) {
            builder.setNeutralButton(neutralLabel) { _, _ ->
                viewModel.updateHrMaxManual(0)
                Toast.makeText(requireContext(), getString(neutralToast), Toast.LENGTH_SHORT).show()
            }
        }
        builder.show()
    }

    private fun showGenderDialog() {
        val options = listOf("male", "female")
        val currentGender = viewModel.settingsState.value.userGender
        val currentIndex = options.indexOf(currentGender)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_dialog_gender_title))
            .setMessage(getString(R.string.settings_dialog_gender_message))
            .setSingleChoiceItems(
                listOf(getString(R.string.settings_profile_gender_male), getString(R.string.settings_profile_gender_female)).toTypedArray(),
                currentIndex
            ) { dialog, which ->
                viewModel.updateUserGender(options[which])
                dialog.dismiss()
                Toast.makeText(requireContext(), getString(R.string.settings_gender_saved), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun getGenderDisplayName(gender: String): String {
        return when (gender) {
            "male" -> getString(R.string.settings_profile_gender_male)
            "female" -> getString(R.string.settings_profile_gender_female)
            else -> getString(R.string.settings_gender_unknown)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        holdHints?.dismiss()
        holdHints = null
        _binding = null
    }
}
