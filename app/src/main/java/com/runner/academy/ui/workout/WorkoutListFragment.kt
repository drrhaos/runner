package com.runner.academy.ui.workout

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runner.academy.R
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutDatabase
import com.runner.academy.databinding.FragmentWorkoutListBinding
import com.runner.academy.util.GpxImporter
import com.runner.academy.util.WorkoutBackupFormat
import com.runner.academy.util.WorkoutGpxBulkExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class WorkoutListFragment : Fragment() {

    private var _binding: FragmentWorkoutListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WorkoutViewModel by viewModels {
        val database = WorkoutDatabase.getDatabase(requireContext())
        val repository = com.runner.academy.data.WorkoutRepository(database.workoutDao())
        WorkoutViewModelFactory(repository)
    }

    private lateinit var workoutAdapter: WorkoutAdapter

    private val openJsonLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importFromJsonUri(uri)
    }

    private val openGpxFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) importFromGpxFolder(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWorkoutListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupClickListeners()
        setupFilterChips()
        setupSwipeRefresh()
        observeViewModel()
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshWorkouts.setOnRefreshListener {
            viewModel.refreshStatistics()
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(350)
                if (_binding != null) {
                    binding.swipeRefreshWorkouts.isRefreshing = false
                }
            }
        }
    }

    private fun setupRecyclerView() {
        workoutAdapter = WorkoutAdapter(
            context = requireContext(),
            onItemClick = { workout ->
                val bundle = Bundle().apply {
                    putLong("workoutId", workout.id)
                }
                findNavController().navigate(R.id.nav_workout_detail, bundle)
            },
            onFavoriteClick = { workout ->
                viewModel.toggleFavorite(workout)
            }
        )

        binding.recyclerViewWorkouts.apply {
            adapter = workoutAdapter
            layoutManager = LinearLayoutManager(context)
        }
    }

    private fun setupFilterChips() {
        binding.chipGroupWorkoutFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when {
                checkedIds.contains(R.id.chip_filter_favorites) -> WorkoutListFilter.FAVORITES
                else -> WorkoutListFilter.ALL
            }
            viewModel.setListFilter(filter)
        }
    }

    private var isSpeedDialOpen = false

    private fun setupClickListeners() {
        binding.fabMain.setOnClickListener {
            setSpeedDialExpanded(!isSpeedDialOpen)
        }
        binding.fabSpeedDialScrim.setOnClickListener {
            setSpeedDialExpanded(false)
        }
        binding.fabAddWorkout.setOnClickListener {
            setSpeedDialExpanded(false)
            findNavController().navigate(R.id.nav_add_workout)
        }
        binding.fabImportWorkouts.setOnClickListener {
            setSpeedDialExpanded(false)
            showImportSourceDialog()
        }
        binding.fabExportWorkouts.setOnClickListener {
            setSpeedDialExpanded(false)
            showExportFormatDialog()
        }
        binding.buttonEmptyStartWorkout.setOnClickListener {
            findNavController().navigate(R.id.nav_tracking)
        }
    }

    private fun setSpeedDialExpanded(expanded: Boolean) {
        if (_binding == null) return
        isSpeedDialOpen = expanded
        val dial = binding.layoutFabSpeedDial
        val scrim = binding.fabSpeedDialScrim
        val mainFab = binding.fabMain

        if (expanded) {
            scrim.visibility = View.VISIBLE
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(150).start()
            dial.visibility = View.VISIBLE
            dial.alpha = 0f
            dial.translationY = dial.height.takeIf { it > 0 }?.toFloat() ?: 48f
            dial.animate().alpha(1f).translationY(0f).setDuration(180).start()
            mainFab.animate().rotation(45f).setDuration(180).start()
            mainFab.contentDescription = getString(R.string.workout_list_fab_close)
        } else {
            scrim.animate().alpha(0f).setDuration(150).withEndAction {
                if (_binding != null) scrim.visibility = View.GONE
            }.start()
            dial.animate().alpha(0f).translationY(48f).setDuration(150).withEndAction {
                if (_binding != null) dial.visibility = View.GONE
            }.start()
            mainFab.animate().rotation(0f).setDuration(180).start()
            mainFab.contentDescription = getString(R.string.workout_list_fab_actions)
        }
    }

    private fun showImportSourceDialog() {
        val options = arrayOf(
            getString(R.string.workout_list_import_json),
            getString(R.string.workout_list_import_gpx_folder)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.workout_list_import_choose_source)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openJsonLauncher.launch(
                        arrayOf("application/json", "text/*", "*/*")
                    )
                    1 -> openGpxFolderLauncher.launch(null)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showExportFormatDialog() {
        val options = arrayOf(
            getString(R.string.workout_list_export_json),
            getString(R.string.workout_list_export_gpx)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.workout_list_export_choose_format)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportAllAsJson()
                    1 -> exportAllAsGpxZip()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportAllAsJson() {
        viewLifecycleOwner.lifecycleScope.launch {
            val progress = showProgressDialog(R.string.workout_list_export_progress)
            try {
                val workouts = viewModel.allWorkouts.first()
                if (workouts.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.no_data_export, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val json = withContext(Dispatchers.Default) {
                    WorkoutBackupFormat.toBackupJson(workouts, requireContext().packageName)
                }
                val fileName = WorkoutBackupFormat.getBackupJsonFileName()
                val file = withContext(Dispatchers.IO) {
                    writeTempTextFile(fileName, json)
                }
                shareFile(file, "application/json")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "JSON export failed: ${e.message}", e)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.export_error, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun exportAllAsGpxZip() {
        viewLifecycleOwner.lifecycleScope.launch {
            val progress = showProgressDialog(R.string.workout_list_export_progress)
            try {
                val workouts = viewModel.allWorkouts.first()
                if (workouts.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.no_data_export, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    WorkoutGpxBulkExporter.exportToZip(
                        workouts,
                        requireContext(),
                        requireContext().cacheDir
                    )
                }
                shareFile(result.zipFile, "application/zip", showReadyToast = result.skippedWithoutTrack == 0)
                if (result.skippedWithoutTrack > 0) {
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.workout_list_export_gpx_partial,
                            result.exportedCount,
                            result.skippedWithoutTrack
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "GPX ZIP export failed: ${e.message}", e)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.export_error, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun writeTempTextFile(fileName: String, content: String): File {
        val safeBase = fileName.substringBeforeLast('.').ifBlank { "export" }
        val extension = fileName.substringAfterLast('.', "txt")
        val file = File(requireContext().cacheDir, fileName)
        // Avoid createTempFile truncating long readable names
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    private fun shareFile(file: File, mimeType: String, showReadyToast: Boolean = true) {
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.workout_list_export_title)))
        if (showReadyToast) {
            Toast.makeText(requireContext(), R.string.workout_list_export_ready, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFromJsonUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val progress = showProgressDialog()
            try {
                val json = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                    } ?: throw IllegalStateException("Cannot open JSON file")
                }
                val workouts = withContext(Dispatchers.Default) {
                    WorkoutBackupFormat.parseBackupJson(json)
                }
                val count = viewModel.importWorkouts(workouts)
                showImportResult(count, failed = 0)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "JSON import failed: ${e.message}", e)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.workout_list_import_error, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun importFromGpxFolder(treeUri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val progress = showProgressDialog()
            try {
                val (workouts, failed) = withContext(Dispatchers.IO) {
                    parseGpxDocuments(treeUri)
                }
                if (workouts.isEmpty() && failed == 0) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.workout_list_import_no_gpx),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                val count = if (workouts.isNotEmpty()) {
                    viewModel.importWorkouts(workouts)
                } else {
                    0
                }
                showImportResult(count, failed)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "GPX folder import failed: ${e.message}", e)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.workout_list_import_error, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun parseGpxDocuments(treeUri: Uri): Pair<List<Workout>, Int> {
        val root = DocumentFile.fromTreeUri(requireContext(), treeUri)
            ?: throw IllegalStateException("Cannot open folder")
        val files = collectGpxFiles(root)
        if (files.isEmpty()) return emptyList<Workout>() to 0

        val imported = mutableListOf<Workout>()
        var failed = 0
        for (file in files) {
            try {
                val workout = requireContext().contentResolver.openInputStream(file.uri)?.use { stream ->
                    GpxImporter.parseGpx(stream, file.name)
                } ?: throw IllegalStateException("Cannot open ${file.name}")
                imported.add(workout)
            } catch (e: Exception) {
                failed++
                android.util.Log.w(TAG, "Skip GPX ${file.name}: ${e.message}")
            }
        }
        return imported to failed
    }

    private fun collectGpxFiles(dir: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        dir.listFiles().forEach { child ->
            when {
                child.isDirectory -> result.addAll(collectGpxFiles(child))
                child.isFile && child.name?.endsWith(".gpx", ignoreCase = true) == true -> {
                    result.add(child)
                }
            }
        }
        return result
    }

    private fun showProgressDialog(messageRes: Int = R.string.workout_list_import_progress) =
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(messageRes)
            .setCancelable(false)
            .create()
            .also { it.show() }

    private fun showImportResult(imported: Int, failed: Int) {
        val message = when {
            imported == 0 && failed == 0 -> getString(R.string.workout_list_import_empty)
            failed > 0 -> getString(R.string.workout_list_import_partial, imported, failed)
            else -> getString(R.string.workout_list_import_success, imported)
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.displayedWorkouts.collect { workouts ->
                    if (isAdded && !isDetached) {
                        workoutAdapter.submitList(workouts)
                        updateEmptyState(workouts.isEmpty())
                        if (workouts.isNotEmpty() &&
                            viewModel.listFilter.value == WorkoutListFilter.ALL
                        ) {
                            cleanWorkoutsInBackground(workouts)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutList", "Loading cancelled: ${e.message}")
            } catch (e: Exception) {
                android.util.Log.e("WorkoutList", "Error loading workouts: ${e.message}", e)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.totalWorkouts.collect { total ->
                    if (isAdded && !isDetached) {
                        binding.textViewTotalWorkouts.text = total.toString()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutList", "Total workouts loading cancelled")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.totalDistance.collect { distance ->
                    if (isAdded && !isDetached) {
                        binding.textViewTotalDistance.text = String.format("%.1f км", distance)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutList", "Total distance loading cancelled")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.averagePace.collect { avgPace ->
                    if (isAdded && !isDetached) {
                        binding.textViewAvgPace.text = viewModel.formatPace(avgPace)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutList", "Average pace loading cancelled")
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.recyclerViewWorkouts.visibility = View.GONE
            val favoritesOnly = viewModel.listFilter.value == WorkoutListFilter.FAVORITES
            if (favoritesOnly) {
                binding.textViewEmptyTitle.setText(R.string.workout_list_message_no_favorites)
                binding.textViewEmptySubtitle.setText(R.string.workout_list_message_add_favorites)
                binding.buttonEmptyStartWorkout.visibility = View.GONE
            } else {
                binding.textViewEmptyTitle.setText(R.string.workout_list_message_no_workouts)
                binding.textViewEmptySubtitle.setText(R.string.workout_list_message_start_workout)
                binding.buttonEmptyStartWorkout.visibility = View.VISIBLE
            }
        } else {
            binding.layoutEmptyState.visibility = View.GONE
            binding.recyclerViewWorkouts.visibility = View.VISIBLE
        }
    }

    private fun cleanWorkoutsInBackground(workouts: List<Workout>) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var cleanedCount = 0
                for (workout in workouts) {
                    if (workout.trackData != null) {
                        val cleanedWorkout = viewModel.cleanWorkoutData(workout)
                        if (cleanedWorkout != null && cleanedWorkout != workout) {
                            cleanedCount++
                        }
                    }
                }
                if (cleanedCount > 0) {
                    android.util.Log.d("WorkoutList", "Cleaned $cleanedCount workouts in background")
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "WorkoutList",
                    "Error cleaning workouts in background: ${e.message}",
                    e
                )
            }
        }
    }

    override fun onDestroyView() {
        isSpeedDialOpen = false
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "WorkoutListImport"
    }
}
