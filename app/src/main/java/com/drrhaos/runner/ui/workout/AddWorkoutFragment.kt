package com.drrhaos.runner.ui.workout

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.drrhaos.runner.R
import com.drrhaos.runner.data.Workout
import com.drrhaos.runner.data.WorkoutDatabase
import com.drrhaos.runner.data.WorkoutType
import com.drrhaos.runner.data.displayName
import com.drrhaos.runner.databinding.FragmentAddWorkoutBinding
import java.text.SimpleDateFormat
import java.util.*

class AddWorkoutFragment : Fragment() {

    private var _binding: FragmentAddWorkoutBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WorkoutViewModel by viewModels {
        val database = WorkoutDatabase.getDatabase(requireContext())
        val repository = com.drrhaos.runner.data.WorkoutRepository(database.workoutDao())
        WorkoutViewModelFactory(repository)
    }

    private var selectedDate: Date = Date()
    private var selectedWorkoutType: WorkoutType = WorkoutType.EASY_RUN

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddWorkoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupWorkoutTypeSpinner()
        setupClickListeners()
        setupDatePicker()
    }

    private fun setupWorkoutTypeSpinner() {
        val workoutTypes = WorkoutType.entries
        val typeNames = workoutTypes.map { it.displayName(requireContext()) }
        
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, typeNames)
        binding.autoCompleteTextViewType.setAdapter(adapter)
        
        binding.autoCompleteTextViewType.setOnItemClickListener { _, _, position, _ ->
            selectedWorkoutType = workoutTypes[position]
        }
        
        // Устанавливаем значение по умолчанию
        binding.autoCompleteTextViewType.setText(typeNames[0], false)
    }

    private fun setupDatePicker() {
        val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        binding.editTextDate.setText(dateFormat.format(selectedDate))
        
        binding.editTextDate.setOnClickListener {
            val calendar = Calendar.getInstance().apply { time = selectedDate }
            DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    calendar.set(year, month, dayOfMonth)
                    selectedDate = calendar.time
                    binding.editTextDate.setText(dateFormat.format(selectedDate))
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
    }

    private fun setupClickListeners() {
        binding.buttonSave.setOnClickListener {
            saveWorkout()
        }

        binding.buttonCancel.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun saveWorkout() {
        val distanceText = binding.editTextDistance.text.toString()
        val hoursText = binding.editTextHours.text.toString()
        val minutesText = binding.editTextMinutes.text.toString()
        val secondsText = binding.editTextSeconds.text.toString()
        val caloriesText = binding.editTextCalories.text.toString()
        val notesText = binding.editTextNotes.text.toString()

        // Валидация обязательных полей
        if (distanceText.isBlank() || hoursText.isBlank() || minutesText.isBlank() || secondsText.isBlank()) {
            Toast.makeText(context, getString(R.string.fill_required_fields), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val distance = distanceText.toFloat()
            val hours = hoursText.toInt()
            val minutes = minutesText.toInt()
            val seconds = secondsText.toInt()
            
            if (distance <= 0) {
                Toast.makeText(context, getString(R.string.distance_must_be_positive), Toast.LENGTH_SHORT).show()
                return
            }

            val totalSeconds = hours * 3600 + minutes * 60 + seconds
            val duration = totalSeconds * 1000L // конвертируем в миллисекунды
            
            if (duration <= 0) {
                Toast.makeText(context, getString(R.string.time_must_be_positive), Toast.LENGTH_SHORT).show()
                return
            }

            val calories = if (caloriesText.isNotBlank()) {
                caloriesText.toIntOrNull()
            } else null

            val avgPace = viewModel.calculatePace(distance, duration)

            val workout = Workout(
                date = selectedDate,
                distance = distance,
                duration = duration,
                avgPace = avgPace,
                calories = calories,
                notes = notesText.ifBlank { null },
                type = selectedWorkoutType,
                trackData = null // для ручного добавления тренировки траектория не нужна
            )

            viewModel.insertWorkout(workout)
            
            Toast.makeText(context, getString(R.string.workout_saved), Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
            
        } catch (e: NumberFormatException) {
            Toast.makeText(context, getString(R.string.check_input_data), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
