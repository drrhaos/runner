package com.runner.academy.ui.trainingplan

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
import com.runner.academy.data.TrainingIcon
import com.runner.academy.data.TrainingPlanDay
import com.runner.academy.data.TrainingPlanRepository
import com.runner.academy.data.WorkoutDatabase
import com.runner.academy.data.WorkoutTemplate
import com.runner.academy.data.defaultTrainingIcon
import com.runner.academy.data.displayName
import com.runner.academy.data.drawableRes
import com.runner.academy.data.parseTrainingIcon
import com.runner.academy.databinding.FragmentPlanEditBinding
import com.runner.academy.databinding.ItemPlanDayBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlanEditFragment : Fragment() {
    private var _binding: FragmentPlanEditBinding? = null
    private val binding get() = _binding!!

    private val planIdArg: Long by lazy { arguments?.getLong("planId", -1L) ?: -1L }
    private var planId: Long = -1L
    private var observingDays = false

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
    private var selectedIcon: TrainingIcon = TrainingIcon.PLAN
    private lateinit var dayAdapter: PlanDayAdapter
    private lateinit var iconAdapter: TrainingIconPickerAdapter

    private val planIcons = TrainingIcon.entries

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
            templateIcon = { id ->
                templates.find { it.id == id }?.let { t ->
                    parseTrainingIcon(t.iconKey, t.workoutType.defaultTrainingIcon())
                }
            },
            onToggleSelect = { index, checked ->
                if (checked) selectedDays.add(index) else selectedDays.remove(index)
                updateRepeatButtonState()
            },
            onAssignClick = { dayIndex -> showTemplatePicker(dayIndex) }
        )
        binding.recyclerViewPlanDays.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewPlanDays.adapter = dayAdapter

        iconAdapter = TrainingIconPickerAdapter(planIcons) { icon ->
            selectedIcon = icon
            updateIconHint()
        }
        iconAdapter.selected = selectedIcon
        binding.recyclerViewIcons.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerViewIcons.adapter = iconAdapter
        updateIconHint()

        binding.buttonBuildSchedule.setOnClickListener { buildSchedule() }
        binding.buttonSavePlan.setOnClickListener { savePlan() }
        binding.buttonDeletePlan.setOnClickListener { confirmDelete() }
        binding.buttonRepeatPattern.setOnClickListener { repeatPattern() }
        updateRepeatButtonState()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.templates.collectLatest { templates = it }
        }

        if (planId > 0) {
            binding.buttonDeletePlan.visibility = View.VISIBLE
            viewLifecycleOwner.lifecycleScope.launch {
                val loaded = viewModel.loadPlan(planId) ?: return@launch
                binding.editTextPlanName.setText(loaded.first.name)
                binding.editTextPlanDays.setText(loaded.first.durationDays.toString())
                selectedIcon = parseTrainingIcon(loaded.first.iconKey, TrainingIcon.PLAN)
                iconAdapter.selected = selectedIcon
                updateIconHint()
                showScheduleUi(hasSchedule = true)
                observeDays()
            }
        } else {
            binding.editTextPlanDays.setText("56")
            showScheduleUi(hasSchedule = false)
        }
    }

    private fun updateIconHint() {
        binding.textViewIconHint.text = getString(
            R.string.intensity_icon_selected,
            selectedIcon.displayName(requireContext())
        )
    }

    private fun showScheduleUi(hasSchedule: Boolean) {
        val scheduleVisibility = if (hasSchedule) View.VISIBLE else View.GONE
        val hintVisibility = if (hasSchedule) View.GONE else View.VISIBLE
        binding.textViewBuildHint.visibility = hintVisibility
        binding.layoutPlanActions.visibility = scheduleVisibility
        binding.textViewDaysTitle.visibility = scheduleVisibility
        binding.recyclerViewPlanDays.visibility = scheduleVisibility
    }

    private fun updateRepeatButtonState() {
        binding.buttonRepeatPattern.isEnabled = selectedDays.isNotEmpty()
    }

    private fun observeDays() {
        if (planId <= 0 || observingDays) return
        observingDays = true
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.observePlanDays(planId).collectLatest { list ->
                days = list
                dayAdapter.submit(list)
                showScheduleUi(hasSchedule = list.isNotEmpty())
            }
        }
    }

    private fun readPlanFields(): Pair<String, Int>? {
        val name = binding.editTextPlanName.text?.toString()?.trim().orEmpty()
        val duration = binding.editTextPlanDays.text?.toString()?.toIntOrNull() ?: 0
        if (name.isEmpty() || duration <= 0) {
            Toast.makeText(requireContext(), R.string.fill_required_fields, Toast.LENGTH_SHORT).show()
            return null
        }
        return name to duration
    }

    private fun buildSchedule() {
        val fields = readPlanFields() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            planId = viewModel.savePlan(
                id = if (planId > 0) planId else 0L,
                name = fields.first,
                durationDays = fields.second,
                icon = selectedIcon
            )
            selectedDays.clear()
            updateRepeatButtonState()
            binding.buttonDeletePlan.visibility = View.VISIBLE
            showScheduleUi(hasSchedule = true)
            observeDays()
            Toast.makeText(requireContext(), R.string.plan_schedule_built, Toast.LENGTH_SHORT).show()
        }
    }

    private fun savePlan() {
        if (planId <= 0) {
            Toast.makeText(requireContext(), R.string.plan_build_first, Toast.LENGTH_SHORT).show()
            return
        }
        val fields = readPlanFields() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            planId = viewModel.savePlan(
                id = planId,
                name = fields.first,
                durationDays = fields.second,
                icon = selectedIcon
            )
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
            Toast.makeText(requireContext(), R.string.plan_build_first, Toast.LENGTH_SHORT).show()
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
            updateRepeatButtonState()
            dayAdapter.notifyDataSetChanged()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class PlanDayAdapter(
    private val selectedDays: MutableSet<Int>,
    private val templateName: (Long) -> String?,
    private val templateIcon: (Long) -> TrainingIcon?,
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
            val templateId = day.templateId
            if (templateId != null) {
                binding.textViewAssignment.text = templateName(templateId)
                    ?: ctx.getString(R.string.plan_day_rest)
                val icon = templateIcon(templateId)
                if (icon != null) {
                    binding.imageViewDayIcon.visibility = View.VISIBLE
                    binding.imageViewDayIcon.setImageResource(icon.drawableRes())
                } else {
                    binding.imageViewDayIcon.visibility = View.GONE
                }
            } else {
                binding.textViewAssignment.text = ctx.getString(R.string.plan_day_rest)
                binding.imageViewDayIcon.visibility = View.GONE
            }
            binding.checkBoxSelect.setOnCheckedChangeListener(null)
            binding.checkBoxSelect.isChecked = selectedDays.contains(day.dayIndex)
            binding.checkBoxSelect.setOnCheckedChangeListener { _, checked ->
                onToggleSelect(day.dayIndex, checked)
            }
            binding.textViewAssignment.setOnClickListener { onAssignClick(day.dayIndex) }
        }
    }
}
