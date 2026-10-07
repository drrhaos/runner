package com.runner.academy.ui.records

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
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.databinding.FragmentRecordsBinding
import com.runner.academy.ui.workout.WorkoutDetailFragmentArgs
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Personal records: one card per distance with its history, the recalculation above them. */
class RecordsFragment : Fragment() {

    private var _binding: FragmentRecordsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RecordsViewModel by viewModels {
        val container = requireContext().appContainer()
        RecordsViewModelFactory(container.workoutRepository, container.metricsBackfill.progress)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRecordsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityHeading(binding.textViewRecordsTitle, true)

        val adapter = RecordsAdapter(
            onWorkoutClick = { workoutId ->
                findNavController().navigate(
                    R.id.nav_workout_detail,
                    WorkoutDetailFragmentArgs(workoutId = workoutId).toBundle()
                )
            },
            onHistoryToggle = viewModel::toggleHistory
        )
        binding.recyclerViewRecords.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewRecords.adapter = adapter
        binding.buttonRecordsEmptyStart.setOnClickListener {
            findNavController().navigate(R.id.nav_tracking)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.filterNotNull().combine(viewModel.expanded) { state, expanded -> state to expanded }
                    .collect { (state, expanded) ->
                        render(state)
                        adapter.submitList(state.rows.map { RecordsAdapter.Item(it, it.distance in expanded) })
                    }
            }
        }
    }

    private fun render(state: RecordsScreenState) {
        val binding = _binding ?: return
        val progress = binding.progressRecordsRecalc
        when (val recalc = state.recalc) {
            RecordsRecalc.None -> {
                progress.visibility = View.GONE
                binding.textViewRecordsRecalc.visibility = View.GONE
            }
            RecordsRecalc.Indeterminate -> {
                if (!progress.isIndeterminate) {
                    // An indicator switches mode only while hidden
                    progress.visibility = View.INVISIBLE
                    progress.isIndeterminate = true
                }
                progress.visibility = View.VISIBLE
                binding.textViewRecordsRecalc.setText(R.string.records_recalc_indeterminate)
                binding.textViewRecordsRecalc.visibility = View.VISIBLE
            }
            is RecordsRecalc.Progress -> {
                if (progress.isIndeterminate) {
                    progress.visibility = View.INVISIBLE
                    progress.isIndeterminate = false
                }
                progress.max = recalc.total.coerceAtLeast(1)
                progress.setProgressCompat(recalc.done, true)
                progress.visibility = View.VISIBLE
                binding.textViewRecordsRecalc.text = getString(R.string.records_recalc_progress, recalc.done, recalc.total)
                binding.textViewRecordsRecalc.visibility = View.VISIBLE
            }
        }
        binding.layoutRecordsEmpty.visibility = if (state.empty) View.VISIBLE else View.GONE
        binding.textViewRecordsEmptyManualOnly.visibility = if (state.manualOnly) View.VISIBLE else View.GONE
        binding.recyclerViewRecords.visibility = if (state.empty) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
