package com.example.runner.ui.workout

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.runner.data.WorkoutDatabase
import com.example.runner.data.WorkoutType
import com.example.runner.databinding.FragmentWorkoutDetailBinding
import com.example.runner.util.GpsFilter
import com.example.runner.util.WorkoutDataCleaner
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.text.SimpleDateFormat
import java.util.*

class WorkoutDetailFragment : Fragment() {

    private var _binding: FragmentWorkoutDetailBinding? = null
    private val binding get() = _binding!!

    private val workoutId: Long by lazy {
        arguments?.getLong("workoutId") ?: -1L
    }
    private val viewModel: WorkoutViewModel by viewModels {
        val database = WorkoutDatabase.getDatabase(requireContext())
        WorkoutViewModelFactory(database.workoutDao())
    }

    private var currentWorkout: com.example.runner.data.Workout? = null
    private var mapView: MapView? = null
    private var trackPolyline: Polyline? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWorkoutDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupMap()
        setupClickListeners()
        loadWorkout()
    }

    private fun setupMap() {
        // Конфигурация OSMDroid
        Configuration.getInstance().load(requireContext(), requireContext().getSharedPreferences("osmdroid", 0))
        
        mapView = binding.mapViewDetail
        mapView?.setTileSource(TileSourceFactory.MAPNIK)
        mapView?.setMultiTouchControls(true)
        mapView?.controller?.setZoom(15.0) // Немного уменьшаем зум для лучшего обзора маршрута
        
        // Настройки для лучшего отображения трека
        mapView?.isClickable = true
        mapView?.isFocusable = true
        
        // Инициализация полилинии для трека
        trackPolyline = Polyline().apply {
            color = Color.RED
            width = 10f // Увеличиваем толщину для лучшей видимости
        }
        mapView?.overlays?.add(trackPolyline)
        
        android.util.Log.d("WorkoutDetail", "Map setup completed")
    }

    private fun setupClickListeners() {
        binding.buttonShare.setOnClickListener {
            currentWorkout?.let { workout ->
                shareWorkout(workout)
            }
        }

        binding.buttonExportGpx.setOnClickListener {
            currentWorkout?.let { workout ->
                exportWorkoutToGpx(workout)
            } ?: run {
                Toast.makeText(requireContext(), "Тренировка не загружена", Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonDelete.setOnClickListener {
            showDeleteConfirmationDialog()
        }
    }

    private fun exportWorkoutToGpx(workout: com.example.runner.data.Workout) {
        try {
            // Получаем данные трека
            val trackData = workout.trackData?.let { trackDataJson ->
                val gson = Gson()
                gson.fromJson(trackDataJson, com.example.runner.data.TrackData::class.java)
            }
            
            if (trackData == null || trackData.points.isEmpty()) {
                Toast.makeText(requireContext(), "Нет данных трека для экспорта", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Создаем GPX файл
            val gpxContent = com.example.runner.util.GpxExporter.exportWorkoutToGpx(workout, trackData)
            
            // Сохраняем файл и делится им
            saveAndShareGpxFile(gpxContent, workout)
            
        } catch (e: Exception) {
            android.util.Log.e("WorkoutDetail", "Error exporting GPX: ${e.message}", e)
            Toast.makeText(requireContext(), "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun saveAndShareGpxFile(gpxContent: String, workout: com.example.runner.data.Workout) {
        try {
            val fileName = com.example.runner.util.GpxExporter.getGpxFileName(workout)
            
            // Создаем временный файл
            val file = java.io.File.createTempFile(
                fileName.replace(".gpx", ""),
                ".gpx",
                requireContext().cacheDir
            )
            
            file.writeText(gpxContent, Charsets.UTF_8)
            
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
                type = "application/gpx+xml"
                putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, fileName)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(android.content.Intent.createChooser(shareIntent, "Экспортировать GPX"))
            Toast.makeText(requireContext(), "GPX файл готов к экспорту", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            android.util.Log.e("WorkoutDetail", "Error saving GPX file: ${e.message}", e)
            Toast.makeText(requireContext(), "Ошибка сохранения файла: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareWorkout(workout: com.example.runner.data.Workout) {
        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val duration = viewModel.formatDuration(workout.duration)
        val pace = viewModel.formatPace(workout.avgPace)
        
        val shareText = """
            🏃‍♂️ Тренировка: ${viewModel.getWorkoutTypeDisplayName(workout.type)}
            📅 Дата: ${dateFormat.format(workout.date)}
            📏 Дистанция: ${String.format("%.2f", workout.distance)} км
            ⏱️ Время: $duration
            🏃 Темп: $pace /км
            ${if (workout.calories != null) "🔥 Калории: ${workout.calories} ккал" else ""}
            
            #тренировка #бег #здоровье
        """.trimIndent()
        
        val shareIntent = android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, shareText)
        }
        
        startActivity(android.content.Intent.createChooser(shareIntent, "Поделиться тренировкой"))
    }

    private fun loadWorkout() {
        android.util.Log.d("WorkoutDetail", "Loading workout with ID: $workoutId")
        
        if (workoutId == -1L) {
            android.util.Log.e("WorkoutDetail", "Invalid workout ID: $workoutId")
            Toast.makeText(requireContext(), "Ошибка: неверный ID тренировки", Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
            return
        }
        
        // Показываем индикатор загрузки
        binding.progressBarMapLoading.visibility = View.VISIBLE
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Получаем тренировку по ID
                viewModel.getWorkoutById(workoutId).collect { workout ->
                    if (isAdded && !isDetached) { // Проверяем, что Fragment еще активен
                        workout?.let {
                            android.util.Log.d("WorkoutDetail", "Workout loaded: ${it.type}, distance: ${it.distance}, has track data: ${it.trackData != null}")
                            
                            // Очищаем данные тренировки от GPS выбросов
                            viewModel.cleanWorkoutData(it)?.let { cleanedWorkout ->
                                android.util.Log.d("WorkoutDetail", "Using cleaned workout data")
                                currentWorkout = cleanedWorkout
                                displayWorkout(cleanedWorkout)
                                displayTrackOnMap(cleanedWorkout)
                            } ?: run {
                                android.util.Log.d("WorkoutDetail", "Using original workout data")
                                currentWorkout = it
                                displayWorkout(it)
                                displayTrackOnMap(it)
                            }
                        } ?: run {
                            android.util.Log.e("WorkoutDetail", "Workout not found with ID: $workoutId")
                            com.example.runner.util.ErrorHandler.handleLoadError(requireContext(), Exception("Workout not found"))
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutDetail", "Loading cancelled: ${e.message}")
                // Не показываем ошибку для отмененных корутин
            } catch (e: Exception) {
                android.util.Log.e("WorkoutDetail", "Error loading workout: ${e.message}", e)
                if (isAdded && !isDetached) {
                    com.example.runner.util.ErrorHandler.handleLoadError(requireContext(), e)
                }
            } finally {
                // Скрываем индикатор загрузки
                if (isAdded && !isDetached) {
                    binding.progressBarMapLoading.visibility = View.GONE
                }
            }
        }
    }

    private fun displayWorkout(workout: com.example.runner.data.Workout) {
        binding.apply {
            // Дата и время
            val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            textViewDetailDate.text = dateFormat.format(workout.date)
            textViewDetailTime.text = timeFormat.format(workout.date)

            // Тип тренировки
            textViewDetailType.text = getWorkoutTypeDisplayName(workout.type)

            // Дистанция - используем данные из workout (уже могут быть очищены)
            textViewDetailDistance.text = com.example.runner.util.FormatUtils.formatDistance(workout.distance)

            // Время
            textViewDetailDuration.text = viewModel.formatDuration(workout.duration)

            // Средний темп
            textViewDetailPace.text = viewModel.formatPace(workout.avgPace)

            // Средняя скорость
            val avgSpeed = com.example.runner.util.FormatUtils.calculateAverageSpeed(workout.distance, workout.duration)
            textViewDetailAvgSpeed.text = com.example.runner.util.FormatUtils.formatSpeed(avgSpeed)

            // Калории
            textViewDetailCalories.text = com.example.runner.util.FormatUtils.formatCalories(workout.calories ?: 0)
        }
    }

    private fun displayTrackOnMap(workout: com.example.runner.data.Workout) {
        // Скрываем индикатор загрузки
        binding.progressBarMapLoading.visibility = View.GONE
        
        workout.trackData?.let { trackDataJson ->
            try {
                val gson = Gson()
                // Парсим как TrackData объект, а не как List<TrackPoint>
                val trackData = gson.fromJson(trackDataJson, com.example.runner.data.TrackData::class.java)
                
                if (trackData.points.isNotEmpty()) {
                    android.util.Log.d("WorkoutDetail", "Original track data has ${trackData.points.size} points")
                    
                    // Проверяем, нужна ли очистка данных
                    val needsCleaning = WorkoutDataCleaner.needsCleaning(trackData)
                    android.util.Log.d("WorkoutDetail", "Track data needs cleaning: $needsCleaning")
                    
                    val cleanedTrackData = if (needsCleaning) {
                        android.util.Log.d("WorkoutDetail", "Cleaning track data...")
                        WorkoutDataCleaner.cleanTrackData(trackData)
                    } else {
                        trackData
                    }
                    
                    android.util.Log.d("WorkoutDetail", "Using ${cleanedTrackData.points.size} points for display")
                    
                    // Создаем GeoPoints из очищенных данных
                    val geoPoints = cleanedTrackData.points.map { trackPoint ->
                        GeoPoint(trackPoint.latitude, trackPoint.longitude)
                    }
                    
                    trackPolyline?.setPoints(ArrayList(geoPoints))
                    
                    // Центрируем карту на маршруте с отступами
                    if (geoPoints.isNotEmpty()) {
                        val bounds = org.osmdroid.util.BoundingBox.fromGeoPoints(geoPoints)
                        // Добавляем отступы для лучшего обзора
                        val latSpan = bounds.latNorth - bounds.latSouth
                        val lonSpan = bounds.lonEast - bounds.lonWest
                        val latPadding = latSpan * 0.1 // 10% отступа
                        val lonPadding = lonSpan * 0.1
                        val expandedBounds = org.osmdroid.util.BoundingBox(
                            bounds.latNorth + latPadding,
                            bounds.lonEast + lonPadding,
                            bounds.latSouth - latPadding,
                            bounds.lonWest - lonPadding
                        )
                        mapView?.zoomToBoundingBox(expandedBounds, true, 100)
                    }
                    
                    mapView?.invalidate()
                    
                    android.util.Log.d("WorkoutDetail", "Successfully loaded track with ${geoPoints.size} points")
                } else {
                    android.util.Log.w("WorkoutDetail", "Track data is empty")
                    android.widget.Toast.makeText(context, "Маршрут пуст", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("WorkoutDetail", "Error parsing track data: ${e.message}", e)
                // Показываем пользователю, что маршрут недоступен
                android.widget.Toast.makeText(context, "Ошибка загрузки маршрута", android.widget.Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            android.util.Log.w("WorkoutDetail", "No track data available for workout ${workout.id}")
            // Показываем пользователю, что маршрут недоступен
            android.widget.Toast.makeText(context, "Маршрут не сохранен", android.widget.Toast.LENGTH_SHORT).show()
        }
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

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mapView?.onDetach()
        _binding = null
    }

    private fun showDeleteConfirmationDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Удалить тренировку")
            .setMessage("Вы уверены, что хотите удалить эту тренировку? Это действие нельзя отменить.")
            .setPositiveButton("Удалить") { _, _ ->
                deleteWorkout()
            }
            .setNegativeButton("Отмена", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun deleteWorkout() {
        currentWorkout?.let { workout ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    viewModel.deleteWorkout(workout)
                    android.widget.Toast.makeText(context, "Тренировка удалена", android.widget.Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                } catch (e: Exception) {
                    android.util.Log.e("WorkoutDetail", "Error deleting workout: ${e.message}", e)
                    com.example.runner.util.ErrorHandler.handleSaveError(requireContext(), e)
                }
            }
        }
    }
}