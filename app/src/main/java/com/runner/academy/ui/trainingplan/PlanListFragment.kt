package com.runner.academy.ui.trainingplan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.runner.academy.R
import com.runner.academy.data.TrainingPlanRepository
import com.runner.academy.data.WorkoutDatabase
import com.runner.academy.databinding.FragmentPlanListBinding
import kotlinx.coroutines.launch

class PlanListFragment : Fragment() {
    private var _binding: FragmentPlanListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TrainingPlanViewModel by viewModels {
        val db = WorkoutDatabase.getDatabase(requireContext())
        TrainingPlanViewModelFactory(
            TrainingPlanRepository(
                db.workoutTemplateDao(),
                db.trainingPlanDao(),
                db.planScheduleDao()
            )
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlanListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val adapter = PlanListAdapter { plan ->
            findNavController().navigate(R.id.nav_plan_edit, bundleOf("planId" to plan.id))
        }
        binding.recyclerViewPlans.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewPlans.adapter = adapter
        binding.fabAddPlan.setOnClickListener {
            findNavController().navigate(R.id.nav_plan_edit, bundleOf("planId" to -1L))
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.plans.collect { list ->
                adapter.submitList(list)
                binding.textViewEmpty.visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
