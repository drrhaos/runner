package com.runner.academy.ui.workout

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.databinding.FragmentWorkoutListBinding
import com.runner.academy.ui.records.RecordsAnnouncement
import com.runner.academy.ui.records.RecordsUpdatedSnackbar
import com.runner.academy.util.FormatUtils
import com.runner.academy.util.ShareExports
import com.runner.academy.util.TrackFileImport
import com.runner.academy.util.WorkoutBackupFormat
import com.runner.academy.util.WorkoutGpxBulkExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class WorkoutListFragment : Fragment() {

    private var _binding: FragmentWorkoutListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WorkoutViewModel by viewModels {
        WorkoutViewModelFactory(requireContext().appContainer().workoutRepository)
    }

    private val importViewModel: WorkoutImportViewModel by viewModels {
        WorkoutImportViewModelFactory(requireContext().appContainer().workoutRepository)
    }

    private lateinit var workoutAdapter: WorkoutAdapter

    private val openJsonLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importFromJsonUri(uri)
    }

    private val openTrackFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) importTrackFiles { trackDocuments(it, uris) }
    }

    private val openTrackFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) importTrackFiles { folderTrackDocuments(it, uri) }
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
        observeImport()
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshWorkouts.setOnRefreshListener {
            viewModel.refreshStatistics()
            workoutAdapter.refresh()
        }
    }

    private fun setupRecyclerView() {
        workoutAdapter = WorkoutAdapter(
            context = requireContext(),
            onItemClick = { workout ->
                findNavController().navigate(
                    R.id.nav_workout_detail,
                    WorkoutDetailFragmentArgs(workoutId = workout.id).toBundle()
                )
            },
            onFavoriteClick = { workout ->
                viewModel.toggleFavorite(workout.id, workout.isFavorite)
            }
        )

        binding.recyclerViewWorkouts.apply {
            adapter = workoutAdapter
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
        }

        workoutAdapter.addLoadStateListener { loadStates ->
            if (_binding == null || !isAdded || isDetached) return@addLoadStateListener
            val refresh = loadStates.refresh
            binding.swipeRefreshWorkouts.isRefreshing = refresh is LoadState.Loading
            val isEmpty = refresh is LoadState.NotLoading &&
                loadStates.append.endOfPaginationReached &&
                workoutAdapter.itemCount == 0
            updateEmptyState(isEmpty)
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
        binding.buttonPendingImports.setOnClickListener {
            findNavController().navigate(R.id.nav_import_conflicts)
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
            getString(R.string.workout_list_import_track_files),
            getString(R.string.workout_list_import_track_folder)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.workout_list_import_choose_source)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openJsonLauncher.launch(
                        arrayOf("application/json", "text/*", "*/*")
                    )
                    // GPX and TCX have no MIME type every file manager knows: the content decides
                    1 -> openTrackFilesLauncher.launch(arrayOf("*/*"))
                    2 -> openTrackFolderLauncher.launch(null)
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
                val workouts = viewModel.loadAllForExport()
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
                dismissProgressSafely(progress)
            }
        }
    }

    private fun exportAllAsGpxZip() {
        viewLifecycleOwner.lifecycleScope.launch {
            val progress = showProgressDialog(R.string.workout_list_export_progress)
            try {
                val workouts = viewModel.loadAllForExport()
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
                dismissProgressSafely(progress)
            }
        }
    }

    private fun writeTempTextFile(fileName: String, content: String): File {
        return ShareExports.writeCacheText(requireContext(), fileName, content)
    }

    private fun shareFile(file: File, mimeType: String, showReadyToast: Boolean = true) {
        ShareExports.shareFile(
            context = requireContext(),
            file = file,
            mimeType = mimeType,
            chooserTitle = getString(R.string.workout_list_export_title),
            readyMessage = if (showReadyToast) getString(R.string.workout_list_export_ready) else null
        )
    }

    private fun importFromJsonUri(uri: Uri) {
        val resolver = requireContext().applicationContext.contentResolver
        importViewModel.importBackup {
            resolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            } ?: throw IllegalStateException("Cannot open JSON file")
        }
    }

    /** Imports the GPX and TCX files [documents] lists (it runs off the main thread, maybe after the view). */
    private fun importTrackFiles(documents: (Context) -> List<DocumentFile>) {
        val context = requireContext().applicationContext
        importViewModel.importFiles {
            documents(context).map { document ->
                ImportFile(document.name) { context.contentResolver.openInputStream(document.uri) }
            }
        }
    }

    /** The files the user picked one by one: taken whatever their extension. */
    private fun trackDocuments(context: Context, uris: List<Uri>): List<DocumentFile> =
        uris.mapNotNull { DocumentFile.fromSingleUri(context, it) }

    private fun folderTrackDocuments(context: Context, treeUri: Uri): List<DocumentFile> {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Cannot open folder")
        return collectTrackFiles(root)
    }

    private fun collectTrackFiles(dir: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        dir.listFiles().forEach { child ->
            when {
                child.isDirectory -> result.addAll(collectTrackFiles(child))
                child.isFile && TrackFileImport.isTrackFileName(child.name) -> {
                    result.add(child)
                }
            }
        }
        return result
    }

    private fun observeImport() {
        viewLifecycleOwner.lifecycleScope.launch {
            importViewModel.progress.collect(::showImportProgress)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // Only while shown: an import that ends meanwhile is reported when the user is back
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                importViewModel.events.collect(::onImportEvent)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            importViewModel.pendingCount.collect(::updatePendingBanner)
        }
    }

    private var importProgressDialog: AlertDialog? = null

    private fun showImportProgress(progress: ImportProgress?) {
        if (progress == null) {
            dismissProgressSafely(importProgressDialog)
            importProgressDialog = null
            return
        }
        val message = if (progress.total == 0) {
            getString(R.string.workout_list_import_progress)
        } else {
            getString(R.string.workout_list_import_progress_count, progress.done, progress.total)
        }
        val dialog = importProgressDialog ?: MaterialAlertDialogBuilder(requireContext())
            .setMessage(message)
            .setCancelable(false)
            .create()
            .also {
                importProgressDialog = it
                it.show()
            }
        dialog.setMessage(message)
    }

    private fun onImportEvent(event: ImportEvent) {
        viewModel.refreshStatistics()
        when (event) {
            ImportEvent.NoFiles -> Toast.makeText(
                requireContext(),
                getString(R.string.workout_list_import_no_track_files),
                Toast.LENGTH_LONG
            ).show()
            is ImportEvent.Failed -> Toast.makeText(
                requireContext(),
                getString(R.string.workout_list_import_error, event.message),
                Toast.LENGTH_LONG
            ).show()
            is ImportEvent.Finished -> showImportResult(
                event.imported,
                event.failed,
                recordsUpdated = event.changedRecordDistances?.let(RecordsAnnouncement::afterGpxImport),
                pending = event.pending,
                duplicates = event.duplicates
            )
        }
    }

    private fun updatePendingBanner(count: Int) {
        val binding = _binding ?: return
        binding.cardViewPendingImports.visibility = if (count > 0) View.VISIBLE else View.GONE
        binding.textViewPendingImports.text = getString(R.string.workout_list_pending_imports, count)
    }

    private fun showProgressDialog(messageRes: Int = R.string.workout_list_import_progress) =
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(messageRes)
            .setCancelable(false)
            .create()
            .also { it.show() }

    private fun dismissProgressSafely(dialog: android.app.Dialog?) {
        if (dialog == null) return
        try {
            if (isAdded && dialog.isShowing) {
                dialog.dismiss()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * The import result; with [recordsUpdated] a snackbar that also offers the records. Adds how
     * many wait for a decision ([pending], the banner leads there) and were there already.
     */
    private fun showImportResult(imported: Int, failed: Int, recordsUpdated: Int? = null, pending: Int = 0, duplicates: Int = 0) {
        val counts = when {
            imported == 0 && failed == 0 && pending == 0 && duplicates == 0 -> getString(R.string.workout_list_import_empty)
            failed > 0 -> getString(R.string.workout_list_import_partial, imported, failed)
            else -> getString(R.string.workout_list_import_success, imported)
        }
        val message = buildString {
            append(counts)
            if (pending > 0) append('\n').append(getString(R.string.workout_list_import_pending_summary, pending))
            if (duplicates > 0) append('\n').append(getString(R.string.workout_list_import_duplicates_summary, duplicates))
        }
        val binding = _binding
        if (recordsUpdated != null && binding != null) {
            val navController = findNavController()
            RecordsUpdatedSnackbar.show(
                view = binding.root,
                navController = navController,
                count = recordsUpdated,
                offerOpen = RecordsAnnouncement.offersOpen(navController.currentDestination?.id, workoutActive = false),
                message = message,
                // The import button lives in the closed speed dial: the main button is what stays on screen
                anchor = binding.fabMain
            )
        } else {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.pagedWorkouts.collectLatest { pagingData ->
                    workoutAdapter.submitData(pagingData)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutList", "Paging cancelled: ${e.message}")
            } catch (e: Exception) {
                android.util.Log.e("WorkoutList", "Error loading workouts: ${e.message}", e)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.listItemCount.collect { count ->
                    if (isAdded && !isDetached && workoutAdapter.itemCount == 0) {
                        updateEmptyState(count == 0)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutList", "List count cancelled")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.totalWorkouts.collect { total ->
                    if (_binding != null && isAdded && !isDetached) {
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
                    if (_binding != null && isAdded && !isDetached) {
                        binding.textViewTotalDistance.text = FormatUtils.formatDistance(distance, requireContext())
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutList", "Total distance loading cancelled")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.averagePace.collect { avgPace ->
                    if (_binding != null && isAdded && !isDetached) {
                        binding.textViewAvgPace.text = viewModel.formatPace(avgPace, requireContext())
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutList", "Average pace loading cancelled")
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        val binding = _binding ?: return
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

    override fun onResume() {
        super.onResume()
        // ViewModel outlives detail/edit screens: totals must be reloaded after edits or deletes there.
        viewModel.refreshStatistics()
    }

    override fun onDestroyView() {
        isSpeedDialOpen = false
        // The import runs on; the next view shows its progress again
        importProgressDialog?.dismiss()
        importProgressDialog = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "WorkoutListImport"
    }
}
