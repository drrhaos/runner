package com.drrhaos.runner.ui.workout

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
import com.drrhaos.runner.R
import com.drrhaos.runner.data.WorkoutDatabase
import com.drrhaos.runner.data.WorkoutType
import com.drrhaos.runner.data.displayName
import com.drrhaos.runner.databinding.FragmentWorkoutDetailBinding
import com.drrhaos.runner.util.ChartCalculations
import com.drrhaos.runner.util.GpsFilter
import com.drrhaos.runner.util.WorkoutDataCleaner
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
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
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

    private var currentWorkout: com.drrhaos.runner.data.Workout? = null
    private var mapView: MapView? = null
    private var trackPolyline: Polyline? = null
    private var currentTrackData: com.drrhaos.runner.data.TrackData? = null
    private var userPreferences: com.drrhaos.runner.util.UserPreferences? = null
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

        userPreferences = com.drrhaos.runner.util.UserPreferences(requireContext())
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
                Toast.makeText(requireContext(), getString(R.string.workout_not_loaded), Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonDelete.setOnClickListener {
            showDeleteConfirmationDialog()
        }

        binding.buttonFix.setOnClickListener {
            currentWorkout?.let { workout ->
                fixWorkoutData(workout)
            } ?: run {
                Toast.makeText(requireContext(), getString(R.string.workout_not_loaded), Toast.LENGTH_SHORT).show()
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

    private fun exportWorkoutToGpx(workout: com.drrhaos.runner.data.Workout) {
        try {
            // Получаем данные трека
            val trackData = workout.trackData?.let { trackDataJson ->
                val gson = Gson()
                gson.fromJson(trackDataJson, com.drrhaos.runner.data.TrackData::class.java)
            }
            
            if (trackData == null || trackData.points.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.track_no_data_export), Toast.LENGTH_SHORT).show()
                return
            }
            
            // Создаем GPX файл
            val gpxContent = com.drrhaos.runner.util.GpxExporter.exportWorkoutToGpx(workout, trackData, requireContext())
            
            // Сохраняем файл и делится им
            saveAndShareGpxFile(gpxContent, workout)
            
        } catch (e: Exception) {
            android.util.Log.e("WorkoutDetail", "Error exporting GPX: ${e.message}", e)
            Toast.makeText(requireContext(), getString(R.string.export_error, e.message), Toast.LENGTH_LONG).show()
        }
    }
    
    private fun saveAndShareGpxFile(gpxContent: String, workout: com.drrhaos.runner.data.Workout) {
        try {
            val fileName = com.drrhaos.runner.util.GpxExporter.getGpxFileName(workout, requireContext())
            
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
            
            startActivity(android.content.Intent.createChooser(shareIntent, getString(R.string.export_gpx_title)))
            Toast.makeText(requireContext(), getString(R.string.gpx_file_ready), Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            android.util.Log.e("WorkoutDetail", "Error saving GPX file: ${e.message}", e)
            Toast.makeText(requireContext(), getString(R.string.file_save_error, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun shareWorkout(workout: com.drrhaos.runner.data.Workout) {
        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val duration = viewModel.formatDuration(workout.duration)
        val pace = viewModel.formatPace(workout.avgPace)
        
        val dateStr = dateFormat.format(workout.date)
        val distanceStr = String.format("%.2f", workout.distance)
        val caloriesStr = if (workout.calories != null) "${workout.calories} ккал" else ""
        val shareText = String.format(getString(R.string.workout_share_text), dateStr, distanceStr, duration, pace, caloriesStr)
        
        val shareIntent = android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, shareText)
        }
        
        startActivity(android.content.Intent.createChooser(shareIntent, getString(R.string.share_workout_title)))
    }

    private fun fixWorkoutData(workout: com.drrhaos.runner.data.Workout) {
        if (workout.trackData == null) {
            Toast.makeText(requireContext(), getString(R.string.track_no_data_fix), Toast.LENGTH_SHORT).show()
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
                        val cleanedTrackData = gson.fromJson(trackDataJson, com.drrhaos.runner.data.TrackData::class.java)
                        currentTrackData = cleanedTrackData
                        updateCharts(cleanedTrackData)
                    }
                    
                    Toast.makeText(requireContext(), getString(R.string.workout_data_fixed), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.workout_data_fix_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("WorkoutDetail", "Error fixing workout data: ${e.message}", e)
                Toast.makeText(requireContext(), getString(R.string.workout_data_fix_error, e.message), Toast.LENGTH_LONG).show()
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
            Toast.makeText(requireContext(), getString(R.string.workout_invalid_id), Toast.LENGTH_SHORT).show()
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
                            com.drrhaos.runner.util.ErrorHandler.handleLoadError(requireContext(), Exception("Workout not found"))
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutDetail", "Loading cancelled: ${e.message}")
                // Не показываем ошибку для отмененных корутин
            } catch (e: Exception) {
                android.util.Log.e("WorkoutDetail", "Error loading workout: ${e.message}", e)
                if (isAdded && !isDetached) {
                    com.drrhaos.runner.util.ErrorHandler.handleLoadError(requireContext(), e)
                }
            } finally {
                // Скрываем индикатор загрузки
                if (isAdded && !isDetached) {
                    binding.progressBarMapLoading.visibility = View.GONE
                }
            }
        }
    }

    private fun displayWorkout(workout: com.drrhaos.runner.data.Workout) {
        binding.apply {
            // Дата и время
            val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            textViewDetailDate.text = dateFormat.format(workout.date)
            textViewDetailTime.text = timeFormat.format(workout.date)

            // Тип тренировки
            textViewDetailType.text = workout.type.displayName(requireContext())

            // Дистанция - используем данные из workout (уже могут быть очищены)
            textViewDetailDistance.text = com.drrhaos.runner.util.FormatUtils.formatDistance(workout.distance, requireContext())

            // Время
            textViewDetailDuration.text = viewModel.formatDuration(workout.duration)

            // Средний темп
            textViewDetailPace.text = viewModel.formatPace(workout.avgPace)

            // Средняя скорость
            val avgSpeed = com.drrhaos.runner.util.FormatUtils.calculateAverageSpeed(workout.distance, workout.duration)
            textViewDetailAvgSpeed.text = com.drrhaos.runner.util.FormatUtils.formatSpeed(avgSpeed, true, requireContext())

            // Калории
            textViewDetailCalories.text = com.drrhaos.runner.util.FormatUtils.formatCalories(workout.calories ?: 0, requireContext())
        }
    }

    private fun displayTrackOnMap(workout: com.drrhaos.runner.data.Workout) {
        // Скрываем индикатор загрузки
        binding.progressBarMapLoading.visibility = View.GONE
        
        workout.trackData?.let { trackDataJson ->
            try {
                val gson = Gson()
                // Парсим как TrackData объект, а не как List<TrackPoint>
                val trackData = gson.fromJson(trackDataJson, com.drrhaos.runner.data.TrackData::class.java)
                
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
                    android.widget.Toast.makeText(context, getString(R.string.route_empty), android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("WorkoutDetail", "Error parsing track data: ${e.message}", e)
                // Показываем пользователю, что маршрут недоступен
                android.widget.Toast.makeText(context, getString(R.string.route_load_error), android.widget.Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            android.util.Log.w("WorkoutDetail", "No track data available for workout ${workout.id}")
            // Показываем пользователю, что маршрут недоступен
            android.widget.Toast.makeText(context, getString(R.string.route_not_saved), android.widget.Toast.LENGTH_SHORT).show()
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
            .setTitle(getString(R.string.delete_workout_title))
            .setMessage(getString(R.string.delete_workout_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                deleteWorkout()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun deleteWorkout() {
        currentWorkout?.let { workout ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    viewModel.deleteWorkout(workout)
                    android.widget.Toast.makeText(context, getString(R.string.workout_deleted), android.widget.Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                } catch (e: Exception) {
                    android.util.Log.e("WorkoutDetail", "Error deleting workout: ${e.message}", e)
                    com.drrhaos.runner.util.ErrorHandler.handleSaveError(requireContext(), e)
                }
            }
        }
    }

    private fun updateCharts(trackData: com.drrhaos.runner.data.TrackData) {
        if (trackData.points.isEmpty()) return

        updatePaceSpeedHeartChart(trackData)
        updateElevationChart(trackData)
        updateSegmentsChart(trackData)
    }

    private fun updatePaceSpeedHeartChart(trackData: com.drrhaos.runner.data.TrackData) {
        val chart = binding.chartPaceSpeedHeart
        val points = trackData.points
        val isMetric = userPreferences?.isMetricSystem() ?: true

        val series = ChartCalculations.buildPaceSpeedSeries(points, isMetric)
        if (series.isEmpty()) {
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

        val isDarkTheme = isDarkTheme()
        val textColor = if (isDarkTheme) Color.WHITE else Color.BLACK
        val gridColor = if (isDarkTheme) Color.parseColor("#40FFFFFF") else Color.parseColor("#40000000")

        val entriesPace = series.map { Entry(it.timeMinutes, it.paceMinPerUnit) }
        val entriesSpeed = series.map { Entry(it.timeMinutes, it.speedDisplay) }

        val paceLabel = if (isMetric) {
            getString(R.string.chart_pace_label)
        } else {
            getString(R.string.chart_pace_label_miles)
        }
        val speedLabel = if (isMetric) {
            getString(R.string.chart_speed_label)
        } else {
            getString(R.string.chart_speed_label_miles)
        }

        // Темп — левая ось (мин/км или мин/милю), скорость — правая (км/ч или миль/ч)
        val dataSetPace = LineDataSet(entriesPace, paceLabel).apply {
            color = Color.parseColor("#FF9800")
            lineWidth = 2f
            setCircleColor(Color.parseColor("#FF9800"))
            setDrawCircles(false)
            setDrawValues(false)
            axisDependency = YAxis.AxisDependency.LEFT
        }

        val dataSetSpeed = LineDataSet(entriesSpeed, speedLabel).apply {
            color = Color.parseColor("#2196F3")
            lineWidth = 2f
            setCircleColor(Color.parseColor("#2196F3"))
            setDrawCircles(false)
            setDrawValues(false)
            axisDependency = YAxis.AxisDependency.RIGHT
        }

        chart.data = LineData(dataSetPace, dataSetSpeed)

        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(true)
        xAxis.gridColor = gridColor
        xAxis.textColor = textColor
        xAxis.axisLineColor = textColor
        xAxis.granularity = 1f
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return getString(R.string.chart_time_format, value.toInt())
            }
        }

        val leftAxis = chart.axisLeft
        leftAxis.setDrawGridLines(true)
        leftAxis.gridColor = gridColor
        leftAxis.textColor = Color.parseColor("#FF9800")
        leftAxis.axisLineColor = textColor
        leftAxis.axisMinimum = 0f

        val rightAxis = chart.axisRight
        rightAxis.isEnabled = true
        rightAxis.setDrawGridLines(false)
        rightAxis.textColor = Color.parseColor("#2196F3")
        rightAxis.axisLineColor = textColor
        rightAxis.axisMinimum = 0f

        chart.legend.textColor = textColor
        chart.setDrawMarkers(false)

        binding.textViewChartPaceSpeedHeartValues.setBackgroundColor(
            if (isDarkTheme) Color.parseColor("#E0FFFFFF") else Color.parseColor("#E0000000")
        )
        binding.textViewChartPaceSpeedHeartValues.setTextColor(
            if (isDarkTheme) Color.BLACK else Color.WHITE
        )

        chart.setOnChartValueSelectedListener(object : com.github.mikephil.charting.listener.OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e == null) return

                val pointIndex = ChartCalculations.findNearestPointIndexByTime(points, e.x)
                if (pointIndex < 0) return

                val seriesPoint = series.minByOrNull { kotlin.math.abs(it.timeMinutes - e.x) }
                    ?: return
                val selectedPoint = points[seriesPoint.trackPointIndex]

                val paceMinutes = seriesPoint.paceMinPerUnit.toInt()
                val paceSeconds = ((seriesPoint.paceMinPerUnit - paceMinutes) * 60)
                    .toInt()
                    .coerceIn(0, 59)
                val paceText = if (isMetric) {
                    getString(R.string.chart_pace_value_km, paceMinutes, paceSeconds)
                } else {
                    getString(R.string.chart_pace_value_miles, paceMinutes, paceSeconds)
                }
                val speedText = if (isMetric) {
                    getString(R.string.chart_speed_value_kmh, seriesPoint.speedDisplay)
                } else {
                    getString(R.string.chart_speed_value_mph, seriesPoint.speedDisplay)
                }

                binding.textViewChartPaceSpeedHeartValues.text =
                    getString(R.string.chart_time_format, e.x.toInt()) + "\n" +
                        "${getString(R.string.workout_details_speed)}: $speedText\n" +
                        "${getString(R.string.workout_details_pace)}: $paceText"
                binding.textViewChartPaceSpeedHeartValues.visibility = View.VISIBLE
                showPositionOnMap(selectedPoint)
            }

            override fun onNothingSelected() {
                binding.textViewChartPaceSpeedHeartValues.visibility = View.GONE
                hidePositionMarker()
            }
        })

        chart.invalidate()
    }

    private fun updateElevationChart(trackData: com.drrhaos.runner.data.TrackData) {
        val chart = binding.chartElevation
        val points = trackData.points
        val series = ChartCalculations.buildElevationSeries(points)

        if (series.isEmpty()) {
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

        val isDarkTheme = isDarkTheme()
        val textColor = if (isDarkTheme) Color.WHITE else Color.BLACK
        val gridColor = if (isDarkTheme) Color.parseColor("#40FFFFFF") else Color.parseColor("#40000000")

        val entries = series.map { Entry(it.distanceKm, it.altitudeMeters) }

        val dataSet = LineDataSet(entries, getString(R.string.chart_elevation_title)).apply {
            color = Color.parseColor("#4CAF50")
            lineWidth = 2f
            setCircleColor(Color.parseColor("#4CAF50"))
            setDrawCircles(false)
            setDrawValues(false)
            setDrawFilled(true)
            fillColor = Color.parseColor("#4CAF50")
            fillAlpha = 50
        }

        chart.data = LineData(dataSet)

        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(true)
        xAxis.gridColor = gridColor
        xAxis.textColor = textColor
        xAxis.axisLineColor = textColor
        xAxis.granularity = 0.5f
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return getString(R.string.chart_elevation_x_format, value)
            }
        }

        val leftAxis = chart.axisLeft
        leftAxis.setDrawGridLines(true)
        leftAxis.gridColor = gridColor
        leftAxis.textColor = textColor
        leftAxis.axisLineColor = textColor
        leftAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return getString(R.string.chart_elevation_y_format, value.toInt())
            }
        }

        chart.axisRight.isEnabled = false
        chart.setDrawMarkers(false)

        binding.textViewChartElevationValues.setBackgroundColor(
            if (isDarkTheme) Color.parseColor("#E0FFFFFF") else Color.parseColor("#E0000000")
        )
        binding.textViewChartElevationValues.setTextColor(
            if (isDarkTheme) Color.BLACK else Color.WHITE
        )

        chart.setOnChartValueSelectedListener(object : com.github.mikephil.charting.listener.OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e == null) return

                binding.textViewChartElevationValues.text =
                    getString(R.string.chart_elevation_x_format, e.x) + "\n" +
                        getString(R.string.chart_elevation_y_format, e.y.toInt())
                binding.textViewChartElevationValues.visibility = View.VISIBLE

                val pointIndex = ChartCalculations.findPointIndexByDistance(points, e.x)
                if (pointIndex >= 0) {
                    showPositionOnMap(points[pointIndex])
                }
            }

            override fun onNothingSelected() {
                binding.textViewChartElevationValues.visibility = View.GONE
                hidePositionMarker()
            }
        })

        chart.invalidate()
    }

    private fun updateSegmentsChart(trackData: com.drrhaos.runner.data.TrackData) {
        val chart = binding.chartSegments
        val points = trackData.points
        val isMetric = userPreferences?.isMetricSystem() ?: true
        val segments = ChartCalculations.buildSegments(points, isMetric)

        if (segments.isEmpty()) {
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

        val isDarkTheme = isDarkTheme()
        val textColor = if (isDarkTheme) Color.WHITE else Color.BLACK
        val gridColor = if (isDarkTheme) Color.parseColor("#40FFFFFF") else Color.parseColor("#40000000")
        val unitLabel = if (isMetric) getString(R.string.unit_km) else getString(R.string.unit_mile)

        val dataSet: BarDataSet = when (segmentsDisplayMode) {
            SegmentsDisplayMode.PACE -> {
                val entriesPace = segments.mapIndexed { index, segment ->
                    BarEntry(index.toFloat(), segment.paceMinPerUnit)
                }
                val paceLabel = if (isMetric) {
                    getString(R.string.chart_pace_label)
                } else {
                    getString(R.string.chart_pace_label_miles)
                }
                BarDataSet(entriesPace, paceLabel).apply {
                    color = Color.parseColor("#FF9800")
                    setDrawValues(true)
                    valueTextColor = textColor
                    valueTextSize = 10f
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            return ChartCalculations.formatPaceMmSs(value)
                        }
                    }
                }
            }
            SegmentsDisplayMode.SPEED -> {
                val entriesSpeed = segments.mapIndexed { index, segment ->
                    BarEntry(index.toFloat(), segment.speedDisplay)
                }
                val speedLabel = if (isMetric) {
                    getString(R.string.chart_speed_label)
                } else {
                    getString(R.string.chart_speed_label_miles)
                }
                BarDataSet(entriesSpeed, speedLabel).apply {
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

        chart.data = BarData(dataSet).apply {
            barWidth = 0.6f
        }

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
        chart.setDrawMarkers(false)

        chart.setOnChartValueSelectedListener(object : com.github.mikephil.charting.listener.OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e == null) return
                val segmentIndex = e.x.toInt()
                if (segmentIndex !in segments.indices) return

                val segment = segments[segmentIndex]
                val startPoint = points[segment.startIndex]
                val endPoint = points[segment.endIndex]

                showPositionOnMap(startPoint)
                showPositionOnMapEnd(endPoint)

                val midLat = (startPoint.latitude + endPoint.latitude) / 2.0
                val midLon = (startPoint.longitude + endPoint.longitude) / 2.0
                mapView?.controller?.animateTo(GeoPoint(midLat, midLon))
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
    ) : com.github.mikephil.charting.components.MarkerView(requireContext(), com.drrhaos.runner.R.layout.marker_view) {
        
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
    private fun showPositionOnMap(trackPoint: com.drrhaos.runner.data.TrackPoint) {
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
    private fun showPositionOnMapEnd(trackPoint: com.drrhaos.runner.data.TrackPoint) {
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