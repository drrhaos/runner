package com.runner.academy.ui.trainingplan

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.data.ScheduledWorkout
import com.runner.academy.data.ScheduledWorkoutStatus
import com.runner.academy.data.ScheduledWorkoutWithTemplate
import com.runner.academy.data.TrainingIcon
import com.runner.academy.data.TrainingPlanRepository
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.defaultTrainingIcon
import com.runner.academy.data.displayName
import com.runner.academy.data.drawableRes
import com.runner.academy.data.parseTrainingIcon
import com.runner.academy.databinding.FragmentMyPlanBinding
import com.runner.academy.databinding.ItemCalendarDayBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MyPlanFragment : Fragment() {
    private var _binding: FragmentMyPlanBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TrainingPlanViewModel by viewModels {
        TrainingPlanViewModelFactory(requireContext().appContainer().trainingPlanRepository)
    }

    private var selectedDayMillis: Long =
        TrainingPlanRepository.startOfDay(System.currentTimeMillis())
    private var visibleMonth: Calendar = Calendar.getInstance().apply {
        timeInMillis = selectedDayMillis
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    private var scheduledByDate: Map<Long, ScheduledWorkout> = emptyMap()

    private lateinit var calendarAdapter: PlanCalendarAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMyPlanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        calendarAdapter = PlanCalendarAdapter { dayMillis ->
            selectedDayMillis = dayMillis
            calendarAdapter.selectedDayMillis = selectedDayMillis
            calendarAdapter.notifyDataSetChanged()
            loadSelectedDay()
        }
        binding.recyclerViewCalendar.layoutManager = GridLayoutManager(requireContext(), 7)
        binding.recyclerViewCalendar.adapter = calendarAdapter
        binding.recyclerViewCalendar.itemAnimator = null

        binding.buttonPrevMonth.setOnClickListener {
            visibleMonth.add(Calendar.MONTH, -1)
            refreshCalendar()
        }
        binding.buttonNextMonth.setOnClickListener {
            visibleMonth.add(Calendar.MONTH, 1)
            refreshCalendar()
        }

        binding.buttonApplyPlan.setOnClickListener { startApplyPlanFlow() }
        binding.buttonDeactivatePlan.setOnClickListener { confirmDeactivate() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.activeSchedule.collectLatest { schedule ->
                if (schedule == null) {
                    binding.buttonDeactivatePlan.visibility = View.GONE
                    binding.textViewActivePlan.text = getString(R.string.plan_no_active)
                } else {
                    binding.buttonDeactivatePlan.visibility = View.VISIBLE
                    val plan = viewModel.getActivePlan()
                    binding.textViewActivePlan.text = if (plan != null) {
                        getString(R.string.plan_active_name, plan.name)
                    } else {
                        getString(R.string.plan_no_active)
                    }
                }
                refreshCalendar()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.activeScheduledWorkouts.collectLatest { list ->
                scheduledByDate = list.associateBy { it.dateMillis }
                refreshCalendar()
                loadSelectedDay()
            }
        }

        refreshCalendar()
        loadSelectedDay()
    }

    private fun refreshCalendar() {
        val locale = appLocale()
        val monthTitleFormat = SimpleDateFormat("LLLL yyyy", locale)
        binding.textViewMonthTitle.text = monthTitleFormat.format(visibleMonth.time)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        calendarAdapter.submit(
            buildMonthCells(visibleMonth),
            selectedDayMillis,
            scheduledByDate
        )
        updateMonthSummary(
            visibleMonth.get(Calendar.YEAR),
            visibleMonth.get(Calendar.MONTH)
        )
    }

    private fun appLocale(): Locale {
        val locales = requireContext().resources.configuration.locales
        return if (locales.isEmpty) Locale.getDefault() else locales[0]
    }

    private fun buildMonthCells(monthStart: Calendar): List<CalendarDayCell> {
        val cells = mutableListOf<CalendarDayCell>()
        val cal = monthStart.clone() as Calendar
        val currentMonth = cal.get(Calendar.MONTH)

        // Monday-first grid
        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val offset = (firstDayOfWeek + 5) % 7 // Mon=0 ... Sun=6
        cal.add(Calendar.DAY_OF_MONTH, -offset)

        repeat(42) {
            val dayStart = TrainingPlanRepository.startOfDay(cal.timeInMillis)
            cells.add(
                CalendarDayCell(
                    dayMillis = dayStart,
                    dayOfMonth = cal.get(Calendar.DAY_OF_MONTH),
                    inCurrentMonth = cal.get(Calendar.MONTH) == currentMonth
                )
            )
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return cells
    }

    private fun loadSelectedDay() {
        viewLifecycleOwner.lifecycleScope.launch {
            val day = viewModel.getScheduledForDate(selectedDayMillis)
            bindDayDetail(day)
        }
    }

    private fun bindDayDetail(day: ScheduledWorkoutWithTemplate?) {
        val dateLabel = DateFormat.getDateInstance(DateFormat.FULL, appLocale())
            .format(Date(selectedDayMillis))
        binding.textViewDayDate.text = dateLabel

        if (day == null) {
            binding.imageViewDayIcon.visibility = View.GONE
            binding.textViewDayTitle.text = getString(R.string.plan_day_outside)
            binding.textViewDayStatus.text = ""
            clearDayScheme()
            return
        }

        when (day.scheduled.status) {
            ScheduledWorkoutStatus.REST -> {
                binding.imageViewDayIcon.visibility = View.GONE
                binding.textViewDayTitle.text = getString(R.string.plan_day_rest)
                binding.textViewDayStatus.text = statusLabel(day.scheduled.status)
                clearDayScheme()
            }
            else -> {
                val template = day.template
                val icon = parseTrainingIcon(
                    template?.iconKey,
                    template?.workoutType?.defaultTrainingIcon() ?: TrainingIcon.EASY
                )
                binding.imageViewDayIcon.visibility = View.VISIBLE
                binding.imageViewDayIcon.setImageResource(icon.drawableRes())
                binding.textViewDayTitle.text = template?.name
                    ?: getString(R.string.plan_day_rest)
                val typeLine = template?.workoutType?.displayName(requireContext()).orEmpty()
                binding.textViewDayStatus.text = buildString {
                    append(statusLabel(day.scheduled.status))
                    if (typeLine.isNotEmpty()) {
                        append(" · ")
                        append(typeLine)
                    }
                    append(" · ")
                    append(getString(R.string.plan_day_label, day.scheduled.dayIndex + 1))
                }
                bindDayScheme(day.segments)
            }
        }
    }

    private fun clearDayScheme() {
        binding.progressDayScheme.visibility = View.GONE
        binding.progressDayScheme.setSegments(emptyList())
    }

    private fun bindDayScheme(segments: List<WorkoutTemplateSegment>) {
        if (segments.isEmpty()) {
            clearDayScheme()
            return
        }
        binding.progressDayScheme.visibility = View.VISIBLE
        binding.progressDayScheme.setSegments(segments)
        binding.progressDayScheme.setProgress(segments.size, 1f)
    }

    private fun statusLabel(status: ScheduledWorkoutStatus): String = when (status) {
        ScheduledWorkoutStatus.PLANNED -> getString(R.string.plan_status_planned)
        ScheduledWorkoutStatus.DONE -> getString(R.string.plan_status_done)
        ScheduledWorkoutStatus.SKIPPED -> getString(R.string.plan_status_skipped)
        ScheduledWorkoutStatus.REST -> getString(R.string.plan_status_rest)
    }

    private fun updateMonthSummary(year: Int, month: Int) {
        val cal = Calendar.getInstance()
        cal.set(year, month, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val end = cal.timeInMillis
        val trainingDays = scheduledByDate.values.count { item ->
            item.dateMillis in start until end &&
                item.status != ScheduledWorkoutStatus.REST &&
                item.templateId != null
        }
        binding.textViewMonthSummary.text = if (scheduledByDate.isEmpty()) {
            getString(R.string.plan_month_no_schedule)
        } else {
            getString(R.string.plan_month_training_days, trainingDays)
        }
    }

    private fun startApplyPlanFlow() {
        viewLifecycleOwner.lifecycleScope.launch {
            val plans = viewModel.listPlans()
            if (plans.isEmpty()) {
                Toast.makeText(requireContext(), R.string.plans_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = plans.map { it.name }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.plan_pick_to_apply)
                .setItems(labels) { _, which ->
                    pickStartDateAndApply(plans[which].id)
                }
                .show()
        }
    }

    private fun pickStartDateAndApply(planId: Long) {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                cal.set(y, m, d, 0, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.applyToCalendar(planId, cal.timeInMillis)
                    selectedDayMillis = cal.timeInMillis
                    visibleMonth.timeInMillis = selectedDayMillis
                    visibleMonth.set(Calendar.DAY_OF_MONTH, 1)
                    Toast.makeText(requireContext(), R.string.plan_applied, Toast.LENGTH_LONG).show()
                    refreshCalendar()
                    loadSelectedDay()
                }
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).apply {
            setTitle(getString(R.string.plan_apply_pick_start))
        }.show()
    }

    private fun confirmDeactivate() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.plan_deactivate_confirm)
            .setPositiveButton(R.string.plan_deactivate) { _, _ ->
                viewModel.deactivateSchedule()
                Toast.makeText(requireContext(), R.string.plan_no_active, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private data class CalendarDayCell(
    val dayMillis: Long,
    val dayOfMonth: Int,
    val inCurrentMonth: Boolean
)

private enum class CalendarDayMark {
    NONE,
    TRAINING,
    DONE
}

private class PlanCalendarAdapter(
    private val onDayClick: (Long) -> Unit
) : RecyclerView.Adapter<PlanCalendarAdapter.VH>() {

    private var cells: List<CalendarDayCell> = emptyList()
    private var scheduledByDate: Map<Long, ScheduledWorkout> = emptyMap()
    var selectedDayMillis: Long = 0L

    fun submit(
        newCells: List<CalendarDayCell>,
        selected: Long,
        schedule: Map<Long, ScheduledWorkout>
    ) {
        cells = newCells
        selectedDayMillis = selected
        scheduledByDate = schedule
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCalendarDayBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount() = cells.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(cells[position])
    }

    inner class VH(private val binding: ItemCalendarDayBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(cell: CalendarDayCell) {
            val ctx = binding.root.context
            val label = binding.textViewDayNumber
            label.text = cell.dayOfMonth.toString()

            val scheduled = scheduledByDate[cell.dayMillis]
            val mark = when {
                scheduled == null ||
                    scheduled.status == ScheduledWorkoutStatus.REST ||
                    scheduled.templateId == null -> CalendarDayMark.NONE
                scheduled.status == ScheduledWorkoutStatus.DONE -> CalendarDayMark.DONE
                else -> CalendarDayMark.TRAINING
            }
            val selected = cell.dayMillis == selectedDayMillis

            when {
                selected -> {
                    label.setBackgroundResource(R.drawable.bg_calendar_day_selected)
                    label.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                }
                mark == CalendarDayMark.TRAINING -> {
                    label.setBackgroundResource(R.drawable.bg_calendar_day_training)
                    label.setTextColor(ContextCompat.getColor(ctx, R.color.blue_900))
                }
                mark == CalendarDayMark.DONE -> {
                    label.setBackgroundResource(R.drawable.bg_calendar_day_done)
                    label.setTextColor(ContextCompat.getColor(ctx, R.color.green_500))
                }
                else -> {
                    label.background = null
                    val attrs = intArrayOf(android.R.attr.textColorPrimary)
                    val ta = ctx.obtainStyledAttributes(attrs)
                    val primaryText = ta.getColor(0, ContextCompat.getColor(ctx, android.R.color.black))
                    ta.recycle()
                    label.setTextColor(
                        if (cell.inCurrentMonth) primaryText
                        else ContextCompat.getColor(ctx, android.R.color.darker_gray)
                    )
                }
            }

            label.alpha = if (cell.inCurrentMonth) 1f else 0.35f
            binding.root.setOnClickListener { onDayClick(cell.dayMillis) }
        }
    }
}
