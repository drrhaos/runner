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
import com.runner.academy.appContainer
import com.runner.academy.databinding.FragmentTemplateListBinding
import kotlinx.coroutines.launch

class TemplateListFragment : Fragment() {
    private var _binding: FragmentTemplateListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TrainingPlanViewModel by viewModels {
        TrainingPlanViewModelFactory(requireContext().appContainer().trainingPlanRepository)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTemplateListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val adapter = TemplateListAdapter { template ->
            findNavController().navigate(
                R.id.nav_template_edit,
                bundleOf("templateId" to template.id)
            )
        }
        binding.recyclerViewTemplates.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewTemplates.adapter = adapter
        binding.fabAddTemplate.setOnClickListener {
            findNavController().navigate(
                R.id.nav_template_edit,
                bundleOf("templateId" to -1L)
            )
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.templatesWithSegments.collect { list ->
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
