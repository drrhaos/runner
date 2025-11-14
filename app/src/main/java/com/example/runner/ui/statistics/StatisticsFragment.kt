package com.example.runner.ui.statistics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.runner.R
import com.example.runner.data.WorkoutDatabase
import com.example.runner.data.WorkoutType
import com.example.runner.databinding.FragmentStatisticsBinding
import com.example.runner.util.FormatUtils
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch

class StatisticsFragment : Fragment() {

    private var _binding: FragmentStatisticsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StatisticsViewModel by viewModels {
        val database = WorkoutDatabase.getDatabase(requireContext())
        StatisticsViewModelFactory(database.workoutDao())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatisticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupClickListeners()
        observeViewModel()
    }

    private fun setupClickListeners() {
        binding.buttonExportCsv.setOnClickListener {
            exportStatisticsToCsv()
        }
    }
    
    private fun exportStatisticsToCsv() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = viewModel.statisticsData.value
                
                if (data.totalWorkouts == 0) {
                    Toast.makeText(requireContext(), "Нет данных для экспорта", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // Экспортируем статистику
                val csvContent = com.example.runner.util.CsvExporter.exportStatisticsToCsv(
                    totalWorkouts = data.totalWorkouts,
                    totalDistance = data.totalDistance,
                    totalDuration = data.totalDuration,
                    totalCalories = data.totalCalories,
                    averagePace = data.averagePace,
                    averageDistance = data.averageDistance,
                    averageDuration = data.averageDuration,
                    bestPace = data.bestPace,
                    longestDistance = data.longestDistance,
                    longestDuration = data.longestDuration,
                    workoutsByType = data.workoutsByType,
                    distanceByType = data.distanceByType
                )
                
                // Сохраняем и делимся файлом
                saveAndShareCsvFile(csvContent, com.example.runner.util.CsvExporter.getStatisticsCsvFileName())
                
            } catch (e: Exception) {
                android.util.Log.e("Statistics", "Error exporting CSV: ${e.message}", e)
                Toast.makeText(requireContext(), "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun saveAndShareCsvFile(csvContent: String, fileName: String) {
        try {
            // Создаем временный файл
            val file = java.io.File.createTempFile(
                fileName.replace(".csv", ""),
                ".csv",
                requireContext().cacheDir
            )
            
            file.writeText(csvContent, Charsets.UTF_8)
            
            // Создаем URI для файла
            val fileUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file
                )
            } else {
                @Suppress("DEPRECATION")
                android.net.Uri.fromFile(file)
            }
            
            // Интент для отправки файла
            val shareIntent = android.content.Intent().apply {
                action = android.content.Intent.ACTION_SEND
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, fileName)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(android.content.Intent.createChooser(shareIntent, "Экспортировать CSV"))
            Toast.makeText(requireContext(), "CSV файл готов к экспорту", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            android.util.Log.e("Statistics", "Error saving CSV file: ${e.message}", e)
            Toast.makeText(requireContext(), "Ошибка сохранения файла: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun observeViewModel() {
        // Наблюдаем за состоянием загрузки
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                binding.progressBarLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }

        // Наблюдаем за данными статистики
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.statisticsData.collect { data ->
                updateStatisticsUI(data)
            }
        }
    }

    private fun updateStatisticsUI(data: StatisticsData) {
        // Проверяем, есть ли данные
        val hasData = data.totalWorkouts > 0
        
        // Показываем/скрываем пустое состояние
        binding.textViewEmptyState.visibility = if (hasData) View.GONE else View.VISIBLE

        if (!hasData) {
            return
        }

        // Общая статистика
        binding.textViewTotalWorkouts.text = data.totalWorkouts.toString()
        binding.textViewTotalDistance.text = String.format("%.2f км", data.totalDistance)
        binding.textViewTotalDuration.text = FormatUtils.formatTime(data.totalDuration)
        binding.textViewTotalCalories.text = "${data.totalCalories} ккал"

        // Лучшие результаты
        binding.textViewBestPace.text = if (data.bestPace > 0) {
            FormatUtils.formatPace(data.bestPace)
        } else {
            "--:-- /км"
        }
        binding.textViewLongestDistance.text = String.format("%.2f км", data.longestDistance)
        binding.textViewLongestDuration.text = FormatUtils.formatTime(data.longestDuration)

        // Средние показатели
        binding.textViewAveragePace.text = if (data.averagePace > 0) {
            FormatUtils.formatPace(data.averagePace)
        } else {
            "--:-- /км"
        }
        binding.textViewAverageDistance.text = String.format("%.2f км", data.averageDistance)
        binding.textViewAverageDuration.text = FormatUtils.formatTime(data.averageDuration)

        // Активность
        binding.textViewWorkoutsThisWeek.text = data.workoutsThisWeek.toString()
        binding.textViewWorkoutsThisMonth.text = data.workoutsThisMonth.toString()

        // Распределение по типам тренировок
        updateWorkoutsByType(data.workoutsByType, data.distanceByType)
    }

    private fun updateWorkoutsByType(
        workoutsByType: Map<WorkoutType, Int>,
        distanceByType: Map<WorkoutType, Float>
    ) {
        val container = binding.linearLayoutWorkoutsByType
        container.removeAllViews()

        if (workoutsByType.isEmpty()) {
            val emptyText = TextView(requireContext()).apply {
                text = "Нет данных"
                textSize = 14f
                setTextColor(
                    MaterialColors.getColor(
                        requireContext(),
                        com.google.android.material.R.attr.colorOnSurface,
                        ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
                    )
                )
            }
            container.addView(emptyText)
            return
        }

        workoutsByType.forEach { (type, count) ->
            val distance = distanceByType[type] ?: 0f
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8.dpToPx()
                }
            }

            val typeText = TextView(requireContext()).apply {
                text = "${getWorkoutTypeDisplayName(type)}:"
                textSize = 14f
                setTextColor(
                    MaterialColors.getColor(
                        requireContext(),
                        com.google.android.material.R.attr.colorOnSurface,
                        ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
                    )
                )
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val countText = TextView(requireContext()).apply {
                text = "$count тренировок (${String.format("%.2f км", distance)})"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(
                    MaterialColors.getColor(
                        requireContext(),
                        com.google.android.material.R.attr.colorPrimary,
                        ContextCompat.getColor(requireContext(), R.color.blue_500)
                    )
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            rowLayout.addView(typeText)
            rowLayout.addView(countText)
            container.addView(rowLayout)
        }
    }

    private fun Int.dpToPx(): Int {
        val density = resources.displayMetrics.density
        return (this * density).toInt()
    }

    private fun getWorkoutTypeDisplayName(type: WorkoutType): String {
        return when (type) {
            WorkoutType.EASY_RUN -> "Легкий бег"
            WorkoutType.TEMPO_RUN -> "Темповый бег"
            WorkoutType.INTERVAL_TRAINING -> "Интервальная тренировка"
            WorkoutType.LONG_RUN -> "Длинный бег"
            WorkoutType.RECOVERY_RUN -> "Восстановительный бег"
            WorkoutType.RACE -> "Соревнование"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
