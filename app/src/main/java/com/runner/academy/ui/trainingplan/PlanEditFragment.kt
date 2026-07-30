package com.runner.academy.ui.trainingplan

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runner.academy.R
import com.runner.academy.data.TrainingPlanDay
import com.runner.academy.data.TrainingPlanRepository
import com.runner.academy.data.WorkoutDatabase
import com.runner.academy.data.WorkoutTemplate
import com.runner.academy.databinding.FragmentPlanEditBinding
import com.runner.academy.databinding.ItemPlanDayBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

class PlanEditFragment : Fragment() {
    private var _binding: FragmentPlanEditBinding? = null
    private val binding get() = _binding!!

    private val planIdArg: Long by lazy { arguments?.getLong("planId", -1L) ?: -1L }
    private var planId: Long = -1L

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

    private val selectedDays = linkedSetOf<Int>()
    private var days: List<TrainingPlanDay> = emptyList()
    private var templates: List<WorkoutTemplate> = emptyList()
    private lateinit var dayAdapter: PlanDayAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlanEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        planId = planIdArg
        dayAdapter = PlanDayAdapter(
            selectedDays = selectedDays,
            templateName = { id -> templates.find { it.id == id }?.name },
            onToggleSelect = { index, checked ->
                if (checked) selectedDays.add(index) else selectedDays.remove(index)
            },
            onAssignClick = { dayIndex -> showTemplatePicker(dayIndex) }
        )
        binding.recyclerViewPlanDays.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewPlanDays.adapter = dayAdapter

        binding.buttonSavePlan.setOnClickListener { savePlan() }
        binding.buttonDeletePlan.setOnClickListener { confirmDelete() }
        binding.buttonRepeatPattern.setOnClickListener { repeatPattern() }
        binding.buttonApplyCalendar.setOnClickListener { pickStartDateAndApply() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.templates.collectLatest { templates = it }
        }

        if (planId > 0) {
            binding.buttonDeletePlan.visibility = View.VISIBLE
            viewLifecycleOwner.lifecycleScope.launch {
                val loaded = viewModel.loadPlan(planId) ?: return@launch
                binding.editTextPlanName.setText(loaded.first.name)
                binding.editTextPlanDays.setText(loaded.first.durationDays.toString())
                observeDays()
            }
        } else {
            binding.editTextPlanDays.setText("56")
        }
    }

    private fun observeDays() {
        if (planId <= 0) return
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.observePlanDays(planId).collectLatest { list ->
                days = list
                dayAdapter.submit(list)
            }
        }
    }

    private fun savePlan() {
        val name = binding.editTextPlanName.text?.toString()?.trim().orEmpty()
        val duration = binding.editTextPlanDays.text?.toString()?.toIntOrNull() ?: 0
        if (name.isEmpty() || duration <= 0) {
            Toast.makeText(requireContext(), R.string.fill_required_fields, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            planId = viewModel.savePlan(
                id = if (planId > 0) planId else 0L,
                name = name,
                durationDays = duration
            )
            binding.buttonDeletePlan.visibility = View.VISIBLE
            Toast.makeText(requireContext(), R.string.plan_saved, Toast.LENGTH_SHORT).show()
            observeDays()
        }
    }

    private fun confirmDelete() {
        if (planId <= 0) return
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.plan_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.loadPlan(planId)?.first?.let { viewModel.deletePlan(it) }
                    findNavController().navigateUp()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTemplatePicker(dayIndex: Int) {
        if (planId <= 0) {
            Toast.makeText(requireContext(), R.string.fill_required_fields, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = mutableListOf(getString(R.string.plan_day_rest))
        labels.addAll(templates.map { it.name })
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.plan_pick_template)
            .setItems(labels.toTypedArray()) { _, which ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val templateId = if (which == 0) null else templates[which - 1].id
                    viewModel.setDayTemplate(planId, dayIndex, templateId)
                }
            }
            .show()
    }

    private fun repeatPattern() {
        if (planId <= 0) return
        if (selectedDays.isEmpty()) {
            Toast.makeText(requireContext(), R.string.plan_repeat_need_selection, Toast.LENGTH_SHORT)
                .show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.repeatPattern(planId, selectedDays.toList())
            selectedDays.clear()
            dayAdapter.notifyDataSetChanged()
        }
    }

    private fun pickStartDateAndApply() {
        if (planId <= 0) {
            Toast.makeText(requireContext(), R.string.fill_required_fields, Toast.LENGTH_SHORT).show()
            return
        }
        val cal = Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                cal.set(y, m, d, 0, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.applyToCalendar(planId, cal.timeInMillis)
                    Toast.makeText(requireContext(), R.string.plan_applied, Toast.LENGTH_LONG).show()
                }
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class PlanDayAdapter(
    private val selectedDays: MutableSet<Int>,
    private val templateName: (Long) -> String?,
    private val onToggleSelect: (Int, Boolean) -> Unit,
    private val onAssignClick: (Int) -> Unit
) : RecyclerView.Adapter<PlanDayAdapter.VH>() {

    private var items: List<TrainingPlanDay> = emptyList()

    fun submit(list: List<TrainingPlanDay>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPlanDayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val binding: ItemPlanDayBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(day: TrainingPlanDay) {
            val ctx = binding.root.context
            binding.textViewDay.text = ctx.getString(R.string.plan_day_label, day.dayIndex + 1)
            binding.textViewAssignment.text = day.templateId?.let { templateName(it) }
                ?: ctx.getString(R.string.plan_day_rest)
            binding.checkBoxSelect.setOnCheckedChangeListener(null)
            binding.checkBoxSelect.isChecked = selectedDays.contains(day.dayIndex)
            binding.checkBoxSelect.setOnCheckedChangeListener { _, checked ->
                onToggleSelect(day.dayIndex, checked)
            }
            binding.textViewAssignment.setOnClickListener { onAssignClick(day.dayIndex) }
        }
    }
}
