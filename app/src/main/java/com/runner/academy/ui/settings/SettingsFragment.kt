package com.runner.academy.ui.settings

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.databinding.FragmentSettingsBinding
import com.runner.academy.util.StepPermissionPolicy
import com.runner.academy.util.StepTrackingAccess
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels {
        val app = requireContext().appContainer()
        SettingsViewModelFactory(requireContext(), app.userPreferences)
    }

    /** Set while the UI writes switch states, so programmatic changes do not act as taps. */
    private var bindingState = false

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
        
        setupClickListeners()
        observeViewModel()
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

        // Голосовые уведомления
        binding.switchVoiceFeedback.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateVoiceFeedback(isChecked)
        }

        // Автопауза: нажатие на всю строку переключает свитч
        binding.rowAutoPause.setOnClickListener { binding.switchAutoPause.toggle() }
        binding.switchAutoPause.setOnCheckedChangeListener { _, isChecked ->
            if (bindingState) return@setOnCheckedChangeListener
            viewModel.updateAutoPause(isChecked)
        }

        // Диагностика GPS
        binding.switchGpsDiagnostics.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateGpsDiagnostics(isChecked)
        }

        // Шаги для дистанции при потере GPS
        binding.switchStepsForDistance.setOnCheckedChangeListener { _, isChecked ->
            if (bindingState) return@setOnCheckedChangeListener
            onStepsForDistanceToggled(isChecked)
        }

        // Сброс настроек
        binding.buttonResetSettings.setOnClickListener {
            showResetConfirmationDialog()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.settingsState.collect { settings ->
                updateUI(settings)
            }
        }
    }

    private fun updateUI(settings: SettingsState) {
        // Профиль пользователя
        binding.textViewWeightValue.text = getString(R.string.settings_weight_format, settings.userWeight.toInt())
        binding.textViewHeightValue.text = getString(R.string.settings_height_format, settings.userHeight.toInt())
        binding.textViewAgeValue.text = if (settings.userAge > 0) getString(R.string.settings_age_format, settings.userAge) else getString(R.string.settings_gender_unknown)
        binding.textViewGenderValue.text = getGenderDisplayName(settings.userGender)
        
        // Настройки приложения
        binding.textViewUnitSystemValue.text = viewModel.getUnitSystemDisplayName(settings.unitSystem)
        binding.textViewThemeModeValue.text = viewModel.getThemeModeDisplayName(settings.themeMode)
        binding.textViewLanguageValue.text = viewModel.getLanguageDisplayName(settings.appLanguage)
        binding.textViewStartCountdownValue.text = "${settings.startCountdownSeconds} s"
        binding.switchVoiceFeedback.isChecked = settings.voiceFeedback
        binding.switchGpsDiagnostics.isChecked = settings.gpsDiagnostics
        bindingState = true
        binding.switchStepsForDistance.isChecked = settings.stepsForDistance && settings.hasStepSensor
        bindingState = false
        binding.switchStepsForDistance.isEnabled = settings.hasStepSensor
        binding.textViewStepsForDistanceHint.setText(
            if (settings.hasStepSensor) R.string.settings_hint_steps_for_distance
            else R.string.settings_hint_steps_no_sensor
        )
        bindingState = true
        binding.switchAutoPause.isChecked = settings.autoPause
        bindingState = false
        binding.textViewAutoPauseHint.text = autoPauseHint(settings)
    }

    /** Without steps the detector relies on GPS alone: say so under the setting. */
    private fun autoPauseHint(settings: SettingsState): String = buildString {
        append(getString(R.string.settings_hint_auto_pause))
        when {
            !settings.hasStepSensor -> append('\n').append(getString(R.string.settings_hint_auto_pause_no_sensor))
            !settings.stepsForDistance -> append('\n').append(getString(R.string.settings_hint_auto_pause_gps_only))
        }
    }

    override fun onResume() {
        super.onResume()
        // The permission may have been changed in system settings meanwhile.
        viewModel.refreshStepsForDistance()
    }

    private fun onStepsForDistanceToggled(enabled: Boolean) {
        viewModel.updateStepsForDistance(enabled)
        if (enabled && !StepTrackingAccess.hasPermission(requireContext())) {
            stepPermissionRequest.launch(StepPermissionPolicy.PERMISSION)
        }
    }

    private fun openAppSystemSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", requireContext().packageName, null)
                )
            )
        } catch (e: Exception) {
            android.util.Log.w("SettingsFragment", "App settings unavailable: ${e.message}")
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
        if (currentBirthDate > 0) {
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
        _binding = null
    }
}
