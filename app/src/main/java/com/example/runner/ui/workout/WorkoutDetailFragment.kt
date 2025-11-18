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
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import android.location.Location
import androidx.core.content.ContextCompat
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

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
    private var currentTrackData: com.example.runner.data.TrackData? = null
    private var userPreferences: com.example.runner.util.UserPreferences? = null
    private var segmentsDisplayMode: SegmentsDisplayMode = SegmentsDisplayMode.PACE
    private var positionMarker: org.osmdroid.views.overlay.Marker? = null
    private var positionMarkerEnd: org.osmdroid.views.overlay.Marker? = null

    enum class SegmentsDisplayMode {
        PACE, SPEED
    }

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

        userPreferences = com.example.runner.util.UserPreferences(requireContext())
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
            outlinePaint.color = Color.RED
            outlinePaint.strokeWidth = 10f // Увеличиваем толщину для лучшей видимости
        }
        mapView?.overlays?.add(trackPolyline)
        
        // Инициализация маркеров для отображения выбранных точек
        mapView?.let { view ->
            // Маркер начала отрезка (синий)
            positionMarker = org.osmdroid.views.overlay.Marker(view).apply {
                icon = createDefaultMarkerIcon(Color.BLUE)
                setVisible(false)
                // Центрируем маркер относительно точки (0.5, 0.5 = центр)
                setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
                setInfoWindowAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_TOP)
            }
            view.overlays.add(positionMarker)
            
            // Маркер конца отрезка (синий, как и начало)
            positionMarkerEnd = org.osmdroid.views.overlay.Marker(view).apply {
                icon = createDefaultMarkerIcon(Color.BLUE)
                setVisible(false)
                setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
                setInfoWindowAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_TOP)
            }
            view.overlays.add(positionMarkerEnd)
        }
        
        android.util.Log.d("WorkoutDetail", "Map setup completed")
    }
    
    private fun createDefaultMarkerIcon(color: Int = Color.BLUE): android.graphics.drawable.Drawable {
        // Уменьшаем размер в 3 раза: было 32dp, стало ~11dp
        val size = (32 * resources.displayMetrics.density / 3f).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = android.graphics.Paint.Style.FILL
        }
        // Рисуем круг, уменьшаем отступы пропорционально
        val radius = size / 2f - 2f
        canvas.drawCircle(size / 2f, size / 2f, radius, paint)
        paint.color = Color.WHITE
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 2f // Уменьшаем толщину обводки пропорционально
        canvas.drawCircle(size / 2f, size / 2f, radius, paint)
        return android.graphics.drawable.BitmapDrawable(resources, bitmap)
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

        binding.buttonFix.setOnClickListener {
            currentWorkout?.let { workout ->
                fixWorkoutData(workout)
            } ?: run {
                Toast.makeText(requireContext(), "Тренировка не загружена", Toast.LENGTH_SHORT).show()
            }
        }

        // Устанавливаем первую кнопку как выбранную по умолчанию
        binding.buttonBasicInfo.isChecked = true
        
        // Обработчик переключения между основными параметрами и графиками
        binding.toggleGroupAnalysis.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    binding.buttonBasicInfo.id -> {
                        binding.layoutBasicInfo.visibility = View.VISIBLE
                        binding.layoutCharts.visibility = View.GONE
                    }
                    binding.buttonCharts.id -> {
                        binding.layoutBasicInfo.visibility = View.GONE
                        binding.layoutCharts.visibility = View.VISIBLE
                    }
                }
            }
        }

        // Обработчик для переключения режима отображения сегментов
        binding.chipGroupSegmentDisplay.setOnCheckedStateChangeListener { group, checkedIds ->
            when {
                binding.chipPace.isChecked -> {
                    segmentsDisplayMode = SegmentsDisplayMode.PACE
                    currentTrackData?.let { updateSegmentsChart(it) }
                }
                binding.chipSpeed.isChecked -> {
                    segmentsDisplayMode = SegmentsDisplayMode.SPEED
                    currentTrackData?.let { updateSegmentsChart(it) }
                }
            }
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

    private fun fixWorkoutData(workout: com.example.runner.data.Workout) {
        if (workout.trackData == null) {
            Toast.makeText(requireContext(), "Нет данных трека для исправления", Toast.LENGTH_SHORT).show()
            return
        }

        // Показываем индикатор загрузки
        binding.progressBarMapLoading.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Очищаем данные тренировки от GPS выбросов (принудительно)
                val cleanedWorkout = viewModel.cleanWorkoutData(workout, forceClean = true)
                
                if (cleanedWorkout != null) {
                    // Обновляем текущую тренировку
                    currentWorkout = cleanedWorkout
                    
                    // Обновляем отображение
                    displayWorkout(cleanedWorkout)
                    displayTrackOnMap(cleanedWorkout)
                    
                    // Обновляем графики, если они открыты
                    cleanedWorkout.trackData?.let { trackDataJson ->
                        val gson = com.google.gson.Gson()
                        val cleanedTrackData = gson.fromJson(trackDataJson, com.example.runner.data.TrackData::class.java)
                        currentTrackData = cleanedTrackData
                        updateCharts(cleanedTrackData)
                    }
                    
                    Toast.makeText(requireContext(), "Данные тренировки исправлены", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Не удалось исправить данные", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("WorkoutDetail", "Error fixing workout data: ${e.message}", e)
                Toast.makeText(requireContext(), "Ошибка при исправлении данных: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                // Скрываем индикатор загрузки
                binding.progressBarMapLoading.visibility = View.GONE
            }
        }
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
                    
                    // Сохраняем данные трека для графиков
                    currentTrackData = cleanedTrackData
                    
                    // Создаем GeoPoints из очищенных данных
                    val geoPoints = cleanedTrackData.points.map { trackPoint ->
                        GeoPoint(trackPoint.latitude, trackPoint.longitude)
                    }
                    
                    trackPolyline?.setPoints(geoPoints.toMutableList())
                    
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
                    
                    // Обновляем графики
                    updateCharts(cleanedTrackData)
                    
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

    private fun updateCharts(trackData: com.example.runner.data.TrackData) {
        if (trackData.points.isEmpty()) return

        updatePaceSpeedHeartChart(trackData)
        updateElevationChart(trackData)
        updateSegmentsChart(trackData)
    }

    private fun updatePaceSpeedHeartChart(trackData: com.example.runner.data.TrackData) {
        val chart = binding.chartPaceSpeedHeart
        val points = trackData.points

        if (points.size < 2) {
            chart.visibility = View.GONE
            return
        }

        chart.visibility = View.VISIBLE
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.setDragEnabled(true)
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.legend.isEnabled = true

        // Настройка цветов для темной темы
        val isDarkTheme = isDarkTheme()
        val textColor = if (isDarkTheme) Color.WHITE else Color.BLACK
        val gridColor = if (isDarkTheme) Color.parseColor("#40FFFFFF") else Color.parseColor("#40000000")

        val entriesPace = mutableListOf<Entry>()
        val entriesSpeed = mutableListOf<Entry>()

        var cumulativeDistance = 0f
        val startTime = points.firstOrNull()?.timestamp ?: 0L

        for (i in 0 until points.size) {
            val point = points[i]
            
            // Вычисляем накопленную дистанцию
            if (i > 0) {
                val prevPoint = points[i - 1]
                val location1 = Location("").apply {
                    latitude = prevPoint.latitude
                    longitude = prevPoint.longitude
                }
                val location2 = Location("").apply {
                    latitude = point.latitude
                    longitude = point.longitude
                }
                cumulativeDistance += location1.distanceTo(location2) / 1000f // в км
            }

            // Темп (минуты на км) - вычисляем из скорости
            val speedMs = point.speed ?: 0f
            val speedKmh = speedMs * 3.6f
            val pace = if (speedKmh > 0) 60f / speedKmh else 0f

            // Время относительно начала (в минутах)
            val timeMinutes = if (startTime > 0) (point.timestamp - startTime) / 60000f else 0f

            entriesPace.add(Entry(timeMinutes, pace))
            entriesSpeed.add(Entry(timeMinutes, speedKmh))
            // Пульс пока не доступен в данных, оставляем пустым
        }

        val dataSetPace = LineDataSet(entriesPace, "Темп (мин/км)").apply {
            color = Color.parseColor("#FF9800")
            lineWidth = 2f
            setCircleColor(Color.parseColor("#FF9800"))
            setDrawCircles(false)
            setDrawValues(false)
            axisDependency = YAxis.AxisDependency.LEFT
        }

        val dataSetSpeed = LineDataSet(entriesSpeed, "Скорость (км/ч)").apply {
            color = Color.parseColor("#2196F3")
            lineWidth = 2f
            setCircleColor(Color.parseColor("#2196F3"))
            setDrawCircles(false)
            setDrawValues(false)
            axisDependency = YAxis.AxisDependency.LEFT
        }

        val lineData = LineData(dataSetPace, dataSetSpeed)
        chart.data = lineData

        // Настройка осей
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(true)
        xAxis.gridColor = gridColor
        xAxis.textColor = textColor
        xAxis.axisLineColor = textColor
        xAxis.granularity = 1f
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val minutes = value.toInt()
                return "${minutes} мин"
            }
        }

        val leftAxis = chart.axisLeft
        leftAxis.setDrawGridLines(true)
        leftAxis.gridColor = gridColor
        leftAxis.textColor = textColor
        leftAxis.axisLineColor = textColor
        leftAxis.axisMinimum = 0f

        chart.axisRight.isEnabled = false
        chart.legend.textColor = textColor
        
        // Отключаем маркер, так как используем TextView
        chart.setDrawMarkers(false)
        
        // Настраиваем фон TextView в зависимости от темы
        binding.textViewChartPaceSpeedHeartValues.setBackgroundColor(
            if (isDarkTheme) Color.parseColor("#E0FFFFFF") else Color.parseColor("#E0000000")
        )
        binding.textViewChartPaceSpeedHeartValues.setTextColor(
            if (isDarkTheme) Color.BLACK else Color.WHITE
        )
        
        // Добавляем слушатель для отображения значений в TextView и точки на карте
        chart.setOnChartValueSelectedListener(object : com.github.mikephil.charting.listener.OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e != null) {
                    val startTime = points.firstOrNull()?.timestamp ?: 0L
                    val selectedTime = startTime + (e.x * 60000).toLong()
                    val selectedPoint = points.minByOrNull { 
                        kotlin.math.abs(it.timestamp - selectedTime) 
                    }
                    
                    if (selectedPoint != null) {
                        // Получаем скорость в м/с
                        val speedMs = selectedPoint.speed ?: 0f
                        // Вычисляем темп (минуты на км)
                        val speedKmh = speedMs * 3.6f
                        val pace = if (speedKmh > 0) 60f / speedKmh else 0f
                        
                        // Форматируем время
                        val timeMinutes = e.x.toInt()
                        
                        // Обновляем TextView
                        val valuesText = String.format("время: %d мин.\nскорость: %.0f м/сек\nтемп: %.2f", 
                            timeMinutes, speedMs, pace)
                        binding.textViewChartPaceSpeedHeartValues.text = valuesText
                        binding.textViewChartPaceSpeedHeartValues.visibility = View.VISIBLE
                        
                        // Показываем точку на карте
                        showPositionOnMap(selectedPoint)
                    }
                }
            }
            
            override fun onNothingSelected() {
                binding.textViewChartPaceSpeedHeartValues.visibility = View.GONE
                hidePositionMarker()
            }
        })
        
        chart.invalidate()
    }

    private fun updateElevationChart(trackData: com.example.runner.data.TrackData) {
        val chart = binding.chartElevation
        val points = trackData.points

        if (points.isEmpty() || points.all { it.altitude == null }) {
            chart.visibility = View.GONE
            return
        }

        chart.visibility = View.VISIBLE
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.setDragEnabled(true)
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.legend.isEnabled = false
        
        // Настройка цветов для темной темы
        val isDarkTheme = isDarkTheme()
        val textColor = if (isDarkTheme) Color.WHITE else Color.BLACK
        val gridColor = if (isDarkTheme) Color.parseColor("#40FFFFFF") else Color.parseColor("#40000000")

        val entries = mutableListOf<Entry>()
        var cumulativeDistance = 0f

        for (i in 0 until points.size) {
            val point = points[i]
            
            if (i > 0) {
                val prevPoint = points[i - 1]
                val location1 = Location("").apply {
                    latitude = prevPoint.latitude
                    longitude = prevPoint.longitude
                }
                val location2 = Location("").apply {
                    latitude = point.latitude
                    longitude = point.longitude
                }
                cumulativeDistance += location1.distanceTo(location2) / 1000f // в км
            }

            val altitude = point.altitude ?: 0.0
            entries.add(Entry(cumulativeDistance, altitude.toFloat()))
        }

        val dataSet = LineDataSet(entries, "Высота").apply {
            color = Color.parseColor("#4CAF50")
            lineWidth = 2f
            setCircleColor(Color.parseColor("#4CAF50"))
            setDrawCircles(false)
            setDrawValues(false)
            setDrawFilled(true)
            fillColor = Color.parseColor("#4CAF50")
            fillAlpha = 50
        }

        val lineData = LineData(dataSet)
        chart.data = lineData

        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(true)
        xAxis.gridColor = gridColor
        xAxis.textColor = textColor
        xAxis.axisLineColor = textColor
        xAxis.granularity = 0.5f
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return String.format("%.1f км", value)
            }
        }

        val leftAxis = chart.axisLeft
        leftAxis.setDrawGridLines(true)
        leftAxis.gridColor = gridColor
        leftAxis.textColor = textColor
        leftAxis.axisLineColor = textColor
        leftAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return "${value.toInt()} м"
            }
        }

        chart.axisRight.isEnabled = false
        
        // Отключаем маркер, так как используем TextView
        chart.setDrawMarkers(false)
        
        // Настраиваем фон TextView в зависимости от темы
        binding.textViewChartElevationValues.setBackgroundColor(
            if (isDarkTheme) Color.parseColor("#E0FFFFFF") else Color.parseColor("#E0000000")
        )
        binding.textViewChartElevationValues.setTextColor(
            if (isDarkTheme) Color.BLACK else Color.WHITE
        )
        
        // Добавляем слушатель для отображения значений в TextView и точки на карте
        chart.setOnChartValueSelectedListener(object : com.github.mikephil.charting.listener.OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e != null && currentTrackData != null) {
                    val trackPoints = currentTrackData!!.points
                    val selectedDistance = e.x // дистанция в км
                    val selectedElevation = e.y // высота в метрах
                    
                    // Обновляем TextView
                    val valuesText = String.format("дистанция: %.2f км\nвысота: %.0f м", 
                        selectedDistance, selectedElevation)
                    binding.textViewChartElevationValues.text = valuesText
                    binding.textViewChartElevationValues.visibility = View.VISIBLE
                    
                    // Находим точку по накопленной дистанции
                    var cumulativeDistance = 0f
                    var selectedPoint: com.example.runner.data.TrackPoint? = null
                    
                    for (i in 0 until trackPoints.size) {
                        val point = trackPoints[i]
                        
                        if (i > 0) {
                            val prevPoint = trackPoints[i - 1]
                            val location1 = Location("").apply {
                                latitude = prevPoint.latitude
                                longitude = prevPoint.longitude
                            }
                            val location2 = Location("").apply {
                                latitude = point.latitude
                                longitude = point.longitude
                            }
                            cumulativeDistance += location1.distanceTo(location2) / 1000f
                        }
                        
                        if (cumulativeDistance >= selectedDistance) {
                            selectedPoint = point
                            break
                        }
                    }
                    
                    // Если не нашли, берем последнюю точку
                    if (selectedPoint == null && trackPoints.isNotEmpty()) {
                        selectedPoint = trackPoints.last()
                    }
                    
                    selectedPoint?.let { point ->
                        showPositionOnMap(point)
                    }
                }
            }
            
            override fun onNothingSelected() {
                binding.textViewChartElevationValues.visibility = View.GONE
                hidePositionMarker()
            }
        })
        
        chart.invalidate()
    }

    private fun updateSegmentsChart(trackData: com.example.runner.data.TrackData) {
        val chart = binding.chartSegments
        val points = trackData.points

        if (points.size < 2) {
            chart.visibility = View.GONE
            return
        }

        chart.visibility = View.VISIBLE
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.setDragEnabled(true)
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.legend.isEnabled = true

        // Настройка цветов для темной темы
        val isDarkTheme = isDarkTheme()
        val textColor = if (isDarkTheme) Color.WHITE else Color.BLACK
        val gridColor = if (isDarkTheme) Color.parseColor("#40FFFFFF") else Color.parseColor("#40000000")

        // Используем настройки приложения для определения единиц измерения
        val isMetric = userPreferences?.isMetricSystem() ?: true
        val segmentSize = if (isMetric) 1.0f else 1.60934f // 1 миля = 1.60934 км
        val unitLabel = if (isMetric) "км" else "миля"
        val segments = mutableListOf<Pair<Float, Float>>() // (pace, speed)
        var currentSegmentDistance = 0f
        var segmentStartTime = points.firstOrNull()?.timestamp ?: 0L

        for (i in 1 until points.size) {
            val prevPoint = points[i - 1]
            val point = points[i]

            val location1 = Location("").apply {
                latitude = prevPoint.latitude
                longitude = prevPoint.longitude
            }
            val location2 = Location("").apply {
                latitude = point.latitude
                longitude = point.longitude
            }
            val segmentDistance = location1.distanceTo(location2) / 1000f // в км
            currentSegmentDistance += segmentDistance

            if (currentSegmentDistance >= segmentSize || i == points.size - 1) {
                val segmentDuration = point.timestamp - segmentStartTime
                val segmentDurationMinutes = segmentDuration / 60000f
                val pace = if (currentSegmentDistance > 0) segmentDurationMinutes / currentSegmentDistance else 0f
                var speed = if (segmentDurationMinutes > 0) currentSegmentDistance / (segmentDurationMinutes / 60f) else 0f
                
                // Конвертируем скорость в мили/ч, если используется имперская система
                if (!isMetric) {
                    speed = speed / 1.60934f // конвертация из км/ч в миль/ч
                }

                segments.add(Pair(pace, speed))
                currentSegmentDistance = 0f
                segmentStartTime = point.timestamp
            }
        }

        if (segments.isEmpty()) {
            chart.visibility = View.GONE
            return
        }

        val dataSet: BarDataSet = when (segmentsDisplayMode) {
            SegmentsDisplayMode.PACE -> {
                val entriesPace = segments.mapIndexed { index, pair -> BarEntry(index.toFloat(), pair.first) }
                BarDataSet(entriesPace, "Темп (мин/$unitLabel)").apply {
                    color = Color.parseColor("#FF9800")
                    setDrawValues(true)
                    valueTextColor = textColor
                    valueTextSize = 10f
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            return String.format("%.2f", value)
                        }
                    }
                }
            }
            SegmentsDisplayMode.SPEED -> {
                val entriesSpeed = segments.mapIndexed { index, pair -> BarEntry(index.toFloat(), pair.second) }
                val speedUnit = if (isMetric) "км/ч" else "миль/ч"
                BarDataSet(entriesSpeed, "Скорость ($speedUnit)").apply {
                    color = Color.parseColor("#2196F3")
                    setDrawValues(true)
                    valueTextColor = textColor
                    valueTextSize = 10f
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            return String.format("%.1f", value)
                        }
                    }
                }
            }
        }

        val barData = BarData(dataSet).apply {
            // Увеличиваем ширину столбцов
            barWidth = 0.6f
        }
        chart.data = barData

        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(true)
        xAxis.gridColor = gridColor
        xAxis.textColor = textColor
        xAxis.axisLineColor = textColor
        xAxis.granularity = 1f
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val segmentNum = value.toInt() + 1
                return "$unitLabel $segmentNum"
            }
        }

        val leftAxis = chart.axisLeft
        leftAxis.setDrawGridLines(true)
        leftAxis.gridColor = gridColor
        leftAxis.textColor = textColor
        leftAxis.axisLineColor = textColor
        leftAxis.axisMinimum = 0f

        chart.axisRight.isEnabled = false
        chart.legend.textColor = textColor
        chart.setFitBars(true)
        
        // Отключаем маркер, так как значения отображаются над столбцами
        chart.setDrawMarkers(false)
        
        // Добавляем слушатель для отображения точек на карте
        chart.setOnChartValueSelectedListener(object : com.github.mikephil.charting.listener.OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e != null && currentTrackData != null) {
                    val trackPoints = currentTrackData!!.points
                    val segmentIndex = e.x.toInt()
                    
                    // Вычисляем точки для каждого отрезка
                    var currentSegmentDistance = 0f
                    var segmentStartIndex = 0
                    var segmentCount = 0
                    var segmentStartPoint: com.example.runner.data.TrackPoint? = null
                    var segmentEndPoint: com.example.runner.data.TrackPoint? = null
                    
                    for (i in 1 until trackPoints.size) {
                        val prevPoint = trackPoints[i - 1]
                        val point = trackPoints[i]
                        
                        // Сохраняем начальную точку отрезка
                        if (segmentCount == segmentIndex && segmentStartPoint == null) {
                            segmentStartPoint = trackPoints[segmentStartIndex]
                        }
                        
                        val location1 = Location("").apply {
                            latitude = prevPoint.latitude
                            longitude = prevPoint.longitude
                        }
                        val location2 = Location("").apply {
                            latitude = point.latitude
                            longitude = point.longitude
                        }
                        val segmentDistance = location1.distanceTo(location2) / 1000f
                        currentSegmentDistance += segmentDistance
                        
                        if (currentSegmentDistance >= segmentSize || i == trackPoints.size - 1) {
                            if (segmentCount == segmentIndex) {
                                // Находим начало и конец отрезка
                                segmentStartPoint = trackPoints[segmentStartIndex]
                                segmentEndPoint = point
                                
                                // Показываем обе точки на карте
                                segmentStartPoint?.let { start ->
                                    showPositionOnMap(start)
                                }
                                segmentEndPoint?.let { end ->
                                    showPositionOnMapEnd(end)
                                }
                                
                                // Центрируем карту на середине отрезка
                                if (segmentStartPoint != null && segmentEndPoint != null) {
                                    val midLat = (segmentStartPoint!!.latitude + segmentEndPoint!!.latitude) / 2.0
                                    val midLon = (segmentStartPoint!!.longitude + segmentEndPoint!!.longitude) / 2.0
                                    val midPoint = GeoPoint(midLat, midLon)
                                    mapView?.controller?.animateTo(midPoint)
                                }
                                break
                            }
                            segmentCount++
                            segmentStartIndex = i
                            currentSegmentDistance = 0f
                        }
                    }
                }
            }
            
            override fun onNothingSelected() {
                hidePositionMarkers()
            }
        })
        
        chart.invalidate()
    }

    private fun isDarkTheme(): Boolean {
        val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Кастомный маркер для отображения значений на графиках
     */
    private inner class ValueMarker(
        private val valueFormatter: (Float, Float, String?) -> String
    ) : com.github.mikephil.charting.components.MarkerView(requireContext(), com.example.runner.R.layout.marker_view) {
        
        private val textView: android.widget.TextView by lazy {
            rootView.findViewById<android.widget.TextView>(android.R.id.text1) ?: 
            android.widget.TextView(requireContext()).apply {
                id = android.R.id.text1
            }
        }
        
        init {
            val isDarkTheme = isDarkTheme()
            val textView = rootView.findViewById<android.widget.TextView>(android.R.id.text1)
            textView?.let {
                it.setTextColor(if (isDarkTheme) Color.WHITE else Color.BLACK)
                it.setBackgroundColor(if (isDarkTheme) Color.parseColor("#E0000000") else Color.parseColor("#E0FFFFFF"))
                it.textSize = 12f
                it.setPadding(16, 8, 16, 8)
            }
        }
        
        override fun refreshContent(e: Entry?, highlight: Highlight?) {
            if (e == null || highlight == null) {
                return
            }
            
            val xValue = e.x
            val yValue = e.y
            
            // Получаем название датасета для форматирования
            val dataSetIndex = highlight.dataSetIndex
            val dataSetLabel = when (val chartView = chartView) {
                is LineChart -> {
                    val lineData = chartView.data as? LineData
                    lineData?.getDataSetByIndex(dataSetIndex)?.label
                }
                is BarChart -> {
                    val barData = chartView.data as? BarData
                    barData?.getDataSetByIndex(dataSetIndex)?.label
                }
                else -> null
            }
            
            val formattedValue = valueFormatter(xValue, yValue, dataSetLabel)
            val textView = rootView.findViewById<android.widget.TextView>(android.R.id.text1)
            textView?.text = formattedValue
            
            super.refreshContent(e, highlight)
        }
        
        override fun getOffset(): MPPointF {
            return MPPointF(-width / 2f, -height.toFloat() - 10f)
        }
    }
    
    /**
     * Показывает позицию на карте для выбранной точки трека (начало отрезка)
     */
    private fun showPositionOnMap(trackPoint: com.example.runner.data.TrackPoint) {
        positionMarker?.let { marker ->
            val geoPoint = GeoPoint(trackPoint.latitude, trackPoint.longitude)
            marker.position = geoPoint
            marker.setVisible(true)
            
            // Включаем масштабирование маркера вместе с картой
            // flat = false означает, что маркер будет масштабироваться при зуме
            marker.isFlat = false
            
            mapView?.invalidate()
        }
    }
    
    /**
     * Показывает позицию конца отрезка на карте
     */
    private fun showPositionOnMapEnd(trackPoint: com.example.runner.data.TrackPoint) {
        positionMarkerEnd?.let { marker ->
            val geoPoint = GeoPoint(trackPoint.latitude, trackPoint.longitude)
            marker.position = geoPoint
            marker.setVisible(true)
            marker.isFlat = false
            
            mapView?.invalidate()
        }
    }
    
    /**
     * Скрывает маркеры позиции на карте
     */
    private fun hidePositionMarkers() {
        positionMarker?.setVisible(false)
        positionMarkerEnd?.setVisible(false)
        mapView?.invalidate()
    }
    
    /**
     * Скрывает маркер позиции на карте (для других графиков)
     */
    private fun hidePositionMarker() {
        hidePositionMarkers()
    }
}