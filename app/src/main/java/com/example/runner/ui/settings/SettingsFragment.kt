package com.example.runner.ui.settings

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.runner.databinding.FragmentSettingsBinding
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
        binding.textViewWeightValue.text = "${settings.userWeight.toInt()} кг"
        binding.textViewHeightValue.text = "${settings.userHeight.toInt()} см"
        binding.textViewAgeValue.text = if (settings.userAge > 0) "${settings.userAge} лет" else "Не указан"
        binding.textViewGenderValue.text = getGenderDisplayName(settings.userGender)
        
        // Настройки приложения
        binding.textViewUnitSystemValue.text = viewModel.getUnitSystemDisplayName(settings.unitSystem)
        binding.textViewGpsAccuracyValue.text = viewModel.getGpsAccuracyDisplayName(settings.gpsAccuracy)
        binding.textViewThemeModeValue.text = viewModel.getThemeModeDisplayName(settings.themeMode)
        binding.switchAutoPause.isChecked = settings.autoPause
        binding.switchVoiceFeedback.isChecked = settings.voiceFeedback
    }

    private fun showWeightDialog() {
        val currentWeight = viewModel.settingsState.value.userWeight.toInt()
        val input = android.widget.EditText(requireContext()).apply {
            setText(currentWeight.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Вес пользователя")
            .setMessage("Введите ваш вес в килограммах:")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val weightText = input.text.toString()
                val weight = weightText.toFloatOrNull()
                if (weight != null && weight > 0 && weight <= 300) {
                    viewModel.updateUserWeight(weight)
                    Toast.makeText(requireContext(), "Вес сохранен", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Введите корректный вес (1-300 кг)", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showUnitSystemDialog() {
        val options = viewModel.getUnitSystemOptions()
        val currentSystem = viewModel.settingsState.value.unitSystem
        val currentIndex = options.indexOf(currentSystem)

        AlertDialog.Builder(requireContext())
            .setTitle("Система единиц")
            .setSingleChoiceItems(
                options.map { viewModel.getUnitSystemDisplayName(it) }.toTypedArray(),
                currentIndex
            ) { dialog, which ->
                viewModel.updateUnitSystem(options[which])
                dialog.dismiss()
                Toast.makeText(requireContext(), "Система единиц изменена", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showGpsAccuracyDialog() {
        val options = viewModel.getGpsAccuracyOptions()
        val currentAccuracy = viewModel.settingsState.value.gpsAccuracy
        val currentIndex = options.indexOf(currentAccuracy)

        AlertDialog.Builder(requireContext())
            .setTitle("Точность GPS")
            .setMessage("Выберите уровень точности GPS. Высокая точность потребляет больше батареи.")
            .setSingleChoiceItems(
                options.map { viewModel.getGpsAccuracyDisplayName(it) }.toTypedArray(),
                currentIndex
            ) { dialog, which ->
                viewModel.updateGpsAccuracy(options[which])
                dialog.dismiss()
                Toast.makeText(requireContext(), "Точность GPS изменена", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showThemeModeDialog() {
        val options = viewModel.getThemeModeOptions()
        val currentMode = viewModel.settingsState.value.themeMode
        val currentIndex = options.indexOf(currentMode).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("Тема приложения")
            .setSingleChoiceItems(
                options.map { viewModel.getThemeModeDisplayName(it) }.toTypedArray(),
                currentIndex
            ) { dialog, which ->
                viewModel.updateThemeMode(options[which])
                dialog.dismiss()
                Toast.makeText(requireContext(), "Тема изменена", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showResetConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Сброс настроек")
            .setMessage("Вы уверены, что хотите сбросить все настройки к значениям по умолчанию?")
            .setPositiveButton("Сбросить") { _, _ ->
                viewModel.resetToDefaults()
                Toast.makeText(requireContext(), "Настройки сброшены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun showHeightDialog() {
        val currentHeight = viewModel.settingsState.value.userHeight.toInt()
        val input = android.widget.EditText(requireContext()).apply {
            setText(currentHeight.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Рост пользователя")
            .setMessage("Введите ваш рост в сантиметрах:")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val height = input.text.toString().toFloatOrNull()
                if (height != null && height > 0 && height < 250) {
                    viewModel.updateUserHeight(height)
                    Toast.makeText(requireContext(), "Рост сохранен", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Введите корректный рост (1-250 см)", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
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

        AlertDialog.Builder(requireContext())
            .setTitle("Дата рождения")
            .setMessage("Выберите вашу дату рождения:")
            .setView(datePicker)
            .setPositiveButton("Сохранить") { _, _ ->
                val selectedCalendar = java.util.Calendar.getInstance()
                selectedCalendar.set(datePicker.year, datePicker.month, datePicker.dayOfMonth)
                viewModel.updateUserBirthDate(selectedCalendar.timeInMillis)
                Toast.makeText(requireContext(), "Дата рождения сохранена", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showGenderDialog() {
        val options = listOf("male", "female")
        val currentGender = viewModel.settingsState.value.userGender
        val currentIndex = options.indexOf(currentGender)

        AlertDialog.Builder(requireContext())
            .setTitle("Пол пользователя")
            .setMessage("Выберите ваш пол:")
            .setSingleChoiceItems(
                listOf("Мужской", "Женский").toTypedArray(),
                currentIndex
            ) { dialog, which ->
                viewModel.updateUserGender(options[which])
                dialog.dismiss()
                Toast.makeText(requireContext(), "Пол сохранен", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun getGenderDisplayName(gender: String): String {
        return when (gender) {
            "male" -> "Мужской"
            "female" -> "Женский"
            else -> "Не указан"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
