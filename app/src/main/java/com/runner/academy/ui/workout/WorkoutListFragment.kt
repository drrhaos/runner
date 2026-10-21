package com.runner.academy.ui.workout

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.data.ExportPeriod
import com.runner.academy.data.ImportResult
import com.runner.academy.databinding.FragmentWorkoutListBinding
import com.runner.academy.service.ImportState
import com.runner.academy.ui.records.RecordsAnnouncement
import com.runner.academy.ui.records.RecordsUpdatedSnackbar
import com.runner.academy.util.ShareExports
import com.runner.academy.util.WorkoutGpxBulkExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/** What an export writes. */
private enum class ExportFormat { JSON, GPX_ZIP }

class WorkoutListFragment : Fragment() {

    private var _binding: FragmentWorkoutListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WorkoutViewModel by viewModels {
        WorkoutViewModelFactory(requireContext().appContainer().workoutRepository)
    }

    private val importViewModel: WorkoutImportViewModel by viewModels {
        WorkoutImportViewModelFactory(requireContext(), requireContext().appContainer().workoutRepository)
    }

    private lateinit var workoutAdapter: WorkoutAdapter

    private val openJsonLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importViewModel.importBackup(uri)
    }

    private val openTrackFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) importViewModel.importFiles(uris)
    }

    private val openTrackFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) importViewModel.importFolder(uri)
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
        reattachExportRangePickers()
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
        binding.fastScrollerWorkouts.attach(binding.recyclerViewWorkouts)

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
        val button = binding.buttonFilterFavorites
        button.isChecked = viewModel.listFilter.value == WorkoutListFilter.FAVORITES
        showFavoritesFilter(button.isChecked)
        button.addOnCheckedChangeListener { _, isChecked ->
            showFavoritesFilter(isChecked)
            viewModel.setListFilter(if (isChecked) WorkoutListFilter.FAVORITES else WorkoutListFilter.ALL)
        }
    }

    /** A filled primary star while only favorites are listed, an outlined one otherwise. */
    private fun showFavoritesFilter(on: Boolean) {
        val button = binding.buttonFilterFavorites
        button.setIconResource(if (on) R.drawable.ic_star else R.drawable.ic_star_border)
        button.iconTint = ColorStateList.valueOf(
            MaterialColors.getColor(
                button,
                if (on) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurfaceVariant
            )
        )
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
        binding.buttonImportStop.setOnClickListener { importViewModel.stop() }
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
                    0 -> showExportPeriodDialog(ExportFormat.JSON)
                    1 -> showExportPeriodDialog(ExportFormat.GPX_ZIP)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** All workouts, or those of a period picked on the calendar. */
    private fun showExportPeriodDialog(format: ExportFormat) {
        val options = arrayOf(
            getString(R.string.workout_list_export_period_all),
            getString(R.string.workout_list_export_period_range)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.workout_list_export_choose_period)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> export(format, period = null)
                    1 -> showExportRangePicker(format)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showExportRangePicker(format: ExportFormat) {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.workout_list_export_period_picker_title)
            .setCalendarConstraints(
                CalendarConstraints.Builder().setValidator(DateValidatorPointBackward.now()).build()
            )
            .build()
        onExportRangePicked(picker, format)
        picker.show(childFragmentManager, exportRangeTag(format))
    }

    /** A picker shown before a rotation is restored without its listener: given it again. */
    private fun reattachExportRangePickers() {
        for (format in ExportFormat.entries) {
            @Suppress("UNCHECKED_CAST")
            val picker = childFragmentManager.findFragmentByTag(exportRangeTag(format))
                as? MaterialDatePicker<androidx.core.util.Pair<Long, Long>> ?: continue
            onExportRangePicked(picker, format)
        }
    }

    private fun onExportRangePicked(picker: MaterialDatePicker<androidx.core.util.Pair<Long, Long>>, format: ExportFormat) {
        picker.addOnPositiveButtonClickListener { selection ->
            val first = selection.first ?: return@addOnPositiveButtonClickListener
            val last = selection.second ?: first
            export(format, ExportPeriod.ofPickerSelection(first, last, ZoneId.systemDefault()))
        }
    }

    private fun exportRangeTag(format: ExportFormat) = "export_range_${format.name}"

    private fun export(format: ExportFormat, period: ExportPeriod?) = when (format) {
        ExportFormat.JSON -> exportAsJson(period)
        ExportFormat.GPX_ZIP -> exportAsGpxZip(period)
    }

    /** "Nothing to export": said for the period when one was picked. */
    private fun showNothingToExport(period: ExportPeriod?) {
        val message = if (period == null) R.string.no_data_export else R.string.workout_list_export_period_empty
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun exportAsJson(period: ExportPeriod?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val progress = showProgressDialog(R.string.workout_list_export_progress)
            try {
                val ids = viewModel.exportIds(period)
                if (ids.isEmpty()) {
                    showNothingToExport(period)
                    return@launch
                }
                val file = viewModel.writeBackupJson(ids, requireContext().cacheDir, requireContext().packageName)
                shareFile(file, "application/json")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The screen left during a long export: nothing to tell
                throw e
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

    private fun exportAsGpxZip(period: ExportPeriod?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val progress = showProgressDialog(R.string.workout_list_export_progress)
            try {
                val ids = viewModel.exportIds(period)
                if (ids.isEmpty()) {
                    showNothingToExport(period)
                    return@launch
                }
                val context = requireContext().applicationContext
                val result = withContext(Dispatchers.IO) {
                    WorkoutGpxBulkExporter.exportToZip(viewModel.workoutsForExport(ids), context, context.cacheDir)
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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

    private fun shareFile(file: File, mimeType: String, showReadyToast: Boolean = true) {
        ShareExports.shareFile(
            context = requireContext(),
            file = file,
            mimeType = mimeType,
            chooserTitle = getString(R.string.workout_list_export_title),
            readyMessage = if (showReadyToast) getString(R.string.workout_list_export_ready) else null
        )
    }

    private fun observeImport() {
        viewLifecycleOwner.lifecycleScope.launch {
            importViewModel.running.collect(::showImportProgress)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // Only while shown: an import that ends meanwhile is reported when the user is back
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { importViewModel.ended.collect(::onImportEnded) }
                launch {
                    importViewModel.rejected.collect {
                        Toast.makeText(requireContext(), R.string.workout_list_import_running, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            importViewModel.pendingCount.collect(::updatePendingBanner)
        }
    }

    /** The running import, in a card: it goes on in the background (with its notification). */
    private fun showImportProgress(progress: ImportState.Running?) {
        val binding = _binding ?: return
        binding.cardViewImportProgress.visibility = if (progress != null) View.VISIBLE else View.GONE
        if (progress == null) return
        val indicator = binding.progressImport
        val determinate = progress.total > 0
        if (indicator.isIndeterminate == determinate) {
            // An indicator switches mode only while hidden
            indicator.visibility = View.INVISIBLE
            indicator.isIndeterminate = !determinate
            indicator.visibility = View.VISIBLE
        }
        if (determinate) {
            indicator.max = progress.total
            indicator.setProgressCompat(progress.done, true)
        }
        binding.textViewImportProgress.text = ImportResultText.progress(requireContext(), progress.done, progress.total)
    }

    private fun onImportEnded(ended: ImportState.Ended) {
        viewModel.refreshStatistics()
        when (ended) {
            is ImportState.NoFiles -> Toast.makeText(
                requireContext(),
                getString(R.string.workout_list_import_no_track_files),
                Toast.LENGTH_LONG
            ).show()
            is ImportState.Failed -> Toast.makeText(
                requireContext(),
                getString(R.string.workout_list_import_error, ended.message),
                Toast.LENGTH_LONG
            ).show()
            is ImportState.Finished -> showImportResult(ended.result)
        }
        // After it was shown: a screen stopped meanwhile shows it when back
        importViewModel.markShown(ended)
    }

    private fun updatePendingBanner(count: Int) {
        val binding = _binding ?: return
        binding.cardViewPendingImports.visibility = if (count > 0) View.VISIBLE else View.GONE
        binding.textViewPendingImports.text = getString(R.string.workout_list_pending_imports, count)
    }

    private fun showProgressDialog(messageRes: Int) =
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

    /** The import result; when it changed records, a snackbar that also offers them. */
    private fun showImportResult(result: ImportResult) {
        val message = ImportResultText.lines(requireContext(), result).joinToString("\n")
        val recordsUpdated = result.changedRecordDistances?.let(RecordsAnnouncement::afterFileImport)
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
            viewModel.historyIndex.collect { index ->
                _binding?.fastScrollerWorkouts?.index = index
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
                        binding.textViewTotalDistance.text = MetricValue.distance(distance)
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
                        binding.textViewAvgPace.text = MetricValue.pace(avgPace)
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
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "WorkoutListExport"
    }
}
