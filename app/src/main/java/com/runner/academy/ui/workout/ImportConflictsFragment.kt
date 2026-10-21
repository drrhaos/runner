package com.runner.academy.ui.workout

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.data.ConflictResolution
import com.runner.academy.databinding.FragmentImportConflictsBinding
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
