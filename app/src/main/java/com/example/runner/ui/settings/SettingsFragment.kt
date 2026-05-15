package com.example.runner.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.runner.R
import com.example.runner.databinding.FragmentSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels {
        SettingsViewModelFactory(requireContext())
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

        // Точность GPS
        binding.rowGpsAccuracy.setOnClickListener {
            showGpsAccuracyDialog()
        }

        // Тема приложения
        binding.rowThemeMode.setOnClickListener {
            showThemeModeDialog()
        }

        // Язык приложения
        binding.rowLanguage.setOnClickListener {
            showLanguageDialog()
        }

        // Автоматическая пауза
        binding.switchAutoPause.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateAutoPause(isChecked)
        }

        // Голосовые уведомления
        binding.switchVoiceFeedback.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateVoiceFeedback(isChecked)
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
        binding.textViewGpsAccuracyValue.text = viewModel.getGpsAccuracyDisplayName(settings.gpsAccuracy)
        binding.textViewThemeModeValue.text = viewModel.getThemeModeDisplayName(settings.themeMode)
        binding.textViewLanguageValue.text = viewModel.getLanguageDisplayName(settings.appLanguage)
        binding.switchAutoPause.isChecked = settings.autoPause
        binding.switchVoiceFeedback.isChecked = settings.voiceFeedback
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

    private fun showGpsAccuracyDialog() {
        val options = viewModel.getGpsAccuracyOptions()
        val currentAccuracy = viewModel.settingsState.value.gpsAccuracy
        val currentIndex = options.indexOf(currentAccuracy)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_dialog_gps_title))
            .setMessage(getString(R.string.settings_dialog_gps_message))
            .setSingleChoiceItems(
                options.map { viewModel.getGpsAccuracyDisplayName(it) }.toTypedArray(),
                currentIndex
            ) { dialog, which ->
                viewModel.updateGpsAccuracy(options[which])
                dialog.dismiss()
                Toast.makeText(requireContext(), getString(R.string.settings_gps_accuracy_changed), Toast.LENGTH_SHORT).show()
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
        val calendar = java.util.Calendar.getInstance()
        if (currentBirthDate > 0) {
            calendar.timeInMillis = currentBirthDate
        } else {
            // Устанавливаем дату 25 лет назад по умолчанию
            calendar.add(java.util.Calendar.YEAR, -25)
        }

        val datePicker = android.widget.DatePicker(requireContext())
        datePicker.init(
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH),
            calendar.get(java.util.Calendar.DAY_OF_MONTH),
            null
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_dialog_birthdate_title))
            .setMessage(getString(R.string.settings_dialog_birthdate_message))
            .setView(datePicker)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val selectedCalendar = java.util.Calendar.getInstance()
                selectedCalendar.set(datePicker.year, datePicker.month, datePicker.dayOfMonth)
                viewModel.updateUserBirthDate(selectedCalendar.timeInMillis)
                Toast.makeText(requireContext(), getString(R.string.settings_birthdate_saved), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
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
