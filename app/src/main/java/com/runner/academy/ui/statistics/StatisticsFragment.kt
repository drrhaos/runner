package com.runner.academy.ui.statistics

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
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.data.WorkoutType
import com.runner.academy.data.displayName
import com.runner.academy.databinding.FragmentStatisticsBinding
import com.runner.academy.util.FormatUtils
import com.runner.academy.util.ShareExports
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatisticsFragment : Fragment() {

    private var _binding: FragmentStatisticsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StatisticsViewModel by viewModels {
        StatisticsViewModelFactory(requireContext().appContainer().workoutRepository)
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
                    Toast.makeText(requireContext(), getString(R.string.no_data_export), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // Экспортируем статистику
                val csvContent = com.runner.academy.util.CsvExporter.exportStatisticsToCsv(
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
                    distanceByType = data.distanceByType,
                    context = requireContext()
                )
                
                val fileName = com.runner.academy.util.CsvExporter.getStatisticsCsvFileName()
                val file = withContext(Dispatchers.IO) {
                    ShareExports.writeCacheTemp(
                        requireContext(),
                        fileName.replace(".csv", ""),
                        ".csv",
                        csvContent
                    )
                }
                ShareExports.shareFile(
                    context = requireContext(),
                    file = file,
                    mimeType = "text/csv",
                    chooserTitle = getString(R.string.export_csv_title),
                    readyMessage = getString(R.string.csv_file_ready)
                )
                
            } catch (e: Exception) {
                android.util.Log.e("Statistics", "Error exporting CSV: ${e.message}", e)
                Toast.makeText(requireContext(), getString(R.string.export_error, e.message), Toast.LENGTH_LONG).show()
            }
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
        binding.textViewTotalDistance.text = String.format("%.2f %s", data.totalDistance, getString(R.string.unit_km))
        binding.textViewTotalDuration.text = FormatUtils.formatTime(data.totalDuration)
        binding.textViewTotalCalories.text = String.format("%s %s", data.totalCalories, getString(R.string.workout_details_calories))

        // Лучшие результаты
        binding.textViewBestPace.text = if (data.bestPace > 0) {
            FormatUtils.formatPace(data.bestPace, requireContext())
        } else {
            getString(R.string.statistics_pace_placeholder)
        }
        binding.textViewLongestDistance.text = String.format("%.2f %s", data.longestDistance, getString(R.string.unit_km))
        binding.textViewLongestDuration.text = FormatUtils.formatTime(data.longestDuration)

        // Средние показатели
        binding.textViewAveragePace.text = if (data.averagePace > 0) {
            FormatUtils.formatPace(data.averagePace, requireContext())
        } else {
            getString(R.string.statistics_pace_placeholder)
        }
        binding.textViewAverageDistance.text = String.format("%.2f %s", data.averageDistance, getString(R.string.unit_km))
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
                text = getString(R.string.no_data_text)
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
                text = "${type.displayName(requireContext())}:"
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
                text = "$count ${getString(R.string.workout_list_num_workouts)} (${String.format("%.2f %s", distance, getString(R.string.unit_km))})"
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
