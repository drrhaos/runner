package com.runner.academy.ui.workout

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.data.ConflictResolution
import com.runner.academy.data.PendingConflict
import com.runner.academy.data.WorkoutRepository
import com.runner.academy.databinding.FragmentImportConflictsBinding
import com.runner.academy.databinding.ItemImportConflictBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Imported workouts that happened at the same time as others, waiting for the user: each one
 * is skipped, replaces what it overlaps, or is kept beside it; or all of them the same way.
 */
class ImportConflictsFragment : Fragment() {

    private var _binding: FragmentImportConflictsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ImportConflictsViewModel by viewModels {
        ImportConflictsViewModelFactory(requireContext().appContainer().workoutRepository)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentImportConflictsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityHeading(binding.textViewImportConflictsTitle, true)

        val adapter = ImportConflictsAdapter(onResolve = viewModel::resolve)
        binding.recyclerViewImportConflicts.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewImportConflicts.adapter = adapter
        binding.buttonImportConflictsAll.setOnClickListener { askForAll() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.conflicts.combine(viewModel.busy) { conflicts, busy -> conflicts to busy }
                    .collect { (conflicts, busy) ->
                        adapter.enabled = !busy
                        adapter.submitList(conflicts)
                        render(conflicts.size, busy)
                    }
            }
        }
    }

    private fun render(count: Int, busy: Boolean) {
        val binding = _binding ?: return
        binding.textViewImportConflictsSubtitle.text = getString(R.string.import_conflicts_subtitle, count)
        binding.textViewImportConflictsSubtitle.visibility = if (count > 0) View.VISIBLE else View.GONE
        binding.buttonImportConflictsAll.visibility = if (count > 1) View.VISIBLE else View.GONE
        binding.buttonImportConflictsAll.isEnabled = !busy
        binding.progressImportConflicts.visibility = if (busy) View.VISIBLE else View.GONE
        binding.textViewImportConflictsEmpty.visibility = if (count == 0) View.VISIBLE else View.GONE
    }

    /** One decision for every waiting workout; replacing all is confirmed (it deletes). */
    private fun askForAll() {
        val choices = arrayOf(
            getString(R.string.import_conflicts_all_skip),
            getString(R.string.import_conflicts_all_replace),
            getString(R.string.import_conflicts_all_keep_both)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_conflicts_all)
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> viewModel.resolveAll(ConflictResolution.KEEP_EXISTING)
                    1 -> confirmReplaceAll()
                    2 -> viewModel.resolveAll(ConflictResolution.KEEP_BOTH)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmReplaceAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_conflicts_all_replace)
            .setMessage(R.string.import_conflicts_replace_all_message)
            .setPositiveButton(R.string.import_conflicts_all_replace) { _, _ ->
                viewModel.resolveAll(ConflictResolution.REPLACE)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class ImportConflictsViewModel(private val repository: WorkoutRepository) : ViewModel() {

    val conflicts: Flow<List<PendingConflict>> = repository.observePendingConflicts()

    private val _busy = MutableStateFlow(false)

    /** A decision is being saved: the buttons wait (a second tap would decide twice). */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun resolve(pendingId: Long, resolution: ConflictResolution) = decide {
        repository.resolvePendingImport(pendingId, resolution)
    }

    fun resolveAll(resolution: ConflictResolution) = decide {
        repository.resolveAllPendingImports(resolution)
    }

    private fun decide(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }
}

class ImportConflictsViewModelFactory(private val repository: WorkoutRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ImportConflictsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ImportConflictsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/** A card per waiting workout: what it overlaps and the three decisions. */
class ImportConflictsAdapter(
    private val onResolve: (pendingId: Long, ConflictResolution) -> Unit
) : ListAdapter<PendingConflict, ImportConflictsAdapter.Holder>(Diff) {

    /** False while a decision is saved. */
    var enabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    class Holder(val binding: ItemImportConflictBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemImportConflictBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val conflict = getItem(position)
        val binding = holder.binding
        val context = binding.root.context
        binding.textViewImportConflict.text = ImportConflictText.message(context, conflict)
        // Nothing overlaps any more (deleted meanwhile): only import it or not
        val overlaps = conflict.others.isNotEmpty()
        binding.buttonImportConflictReplace.visibility = if (overlaps) View.VISIBLE else View.GONE
        binding.buttonImportConflictKeepBoth.setText(
            if (overlaps) R.string.workout_import_conflict_keep_both else R.string.workout_import_conflict_import
        )
        listOf(
            binding.buttonImportConflictSkip to ConflictResolution.KEEP_EXISTING,
            binding.buttonImportConflictReplace to ConflictResolution.REPLACE,
            binding.buttonImportConflictKeepBoth to ConflictResolution.KEEP_BOTH
        ).forEach { (button, resolution) ->
            button.isEnabled = enabled
            button.setOnClickListener { onResolve(conflict.pendingId, resolution) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<PendingConflict>() {
        override fun areItemsTheSame(oldItem: PendingConflict, newItem: PendingConflict) = oldItem.pendingId == newItem.pendingId
        override fun areContentsTheSame(oldItem: PendingConflict, newItem: PendingConflict) = oldItem == newItem
    }
}
