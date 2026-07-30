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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runner.academy.R
import com.runner.academy.data.ScheduledWorkoutStatus
import com.runner.academy.data.ScheduledWorkoutWithTemplate
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.TrainingPlanRepository
import com.runner.academy.data.WorkoutDatabase
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.displayName
import com.runner.academy.databinding.FragmentMyPlanBinding
import com.runner.academy.util.FormatUtils
import com.runner.academy.util.SpeedPaceCalculator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

class MyPlanFragment : Fragment() {
    private var _binding: FragmentMyPlanBinding? = null
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

    private var selectedDayMillis: Long = TrainingPlanRepository.startOfDay(System.currentTimeMillis())
    private var scheduledByDate: Map<Long, com.runner.academy.data.ScheduledWorkout> = emptyMap()
    private var scheduleStartMillis: Long? = null
    private var scheduleEndMillis: Long? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMyPlanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.calendarView.date = selectedDayMillis
        binding.calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            val cal = Calendar.getInstance()
            cal.set(year, month, dayOfMonth, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            selectedDayMillis = cal.timeInMillis
            loadSelectedDay()
            updateMonthSummary(year, month)
        }

        binding.buttonApplyPlan.setOnClickListener { startApplyPlanFlow() }
        binding.buttonDeactivatePlan.setOnClickListener { confirmDeactivate() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.activeSchedule.collectLatest { schedule ->
                if (schedule == null) {
                    scheduleStartMillis = null
                    scheduleEndMillis = null
                    clearCalendarBounds()
                    binding.buttonDeactivatePlan.visibility = View.GONE
                    binding.textViewActivePlan.text = getString(R.string.plan_no_active)
                } else {
                    scheduleStartMillis = schedule.startDateMillis
                    binding.buttonDeactivatePlan.visibility = View.VISIBLE
                    val plan = viewModel.getActivePlan()
                    if (plan != null) {
                        scheduleEndMillis = schedule.startDateMillis +
                            TimeUnit.DAYS.toMillis((plan.durationDays - 1).coerceAtLeast(0).toLong())
                        binding.textViewActivePlan.text =
                            getString(R.string.plan_active_name, plan.name)
                        binding.calendarView.minDate = schedule.startDateMillis
                        binding.calendarView.maxDate =
                            scheduleEndMillis ?: schedule.startDateMillis
                    } else {
                        binding.textViewActivePlan.text = getString(R.string.plan_no_active)
                        clearCalendarBounds()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.activeScheduledWorkouts.collectLatest { list ->
                scheduledByDate = list.associateBy { it.dateMillis }
                val cal = Calendar.getInstance().apply { timeInMillis = selectedDayMillis }
                updateMonthSummary(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
                loadSelectedDay()
            }
        }

        loadSelectedDay()
    }

    private fun clearCalendarBounds() {
        val now = System.currentTimeMillis()
        binding.calendarView.minDate = now - TimeUnit.DAYS.toMillis(3650)
        binding.calendarView.maxDate = now + TimeUnit.DAYS.toMillis(3650)
    }

    private fun loadSelectedDay() {
        viewLifecycleOwner.lifecycleScope.launch {
            val day = viewModel.getScheduledForDate(selectedDayMillis)
            bindDayDetail(day)
        }
    }

    private fun bindDayDetail(day: ScheduledWorkoutWithTemplate?) {
        val dateLabel = DateFormat.getDateInstance(DateFormat.FULL).format(Date(selectedDayMillis))
        binding.textViewDayDate.text = dateLabel

        if (day == null) {
            binding.textViewDayTitle.text = getString(R.string.plan_day_outside)
            binding.textViewDayStatus.text = ""
            binding.textViewDaySegments.text = ""
            return
        }

        when (day.scheduled.status) {
            ScheduledWorkoutStatus.REST -> {
                binding.textViewDayTitle.text = getString(R.string.plan_day_rest)
                binding.textViewDayStatus.text = statusLabel(day.scheduled.status)
                binding.textViewDaySegments.text = ""
            }
            else -> {
                val template = day.template
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
                binding.textViewDaySegments.text = formatSegments(day.segments)
            }
        }
    }

    private fun formatSegments(segments: List<WorkoutTemplateSegment>): String {
        if (segments.isEmpty()) return getString(R.string.plan_day_no_intervals)
        return segments.joinToString("\n") { segment ->
            val goal = when (segment.goalType) {
                SegmentGoalType.DURATION -> segment.durationMs?.let { FormatUtils.formatTime(it) }
                    ?: "—"
                SegmentGoalType.DISTANCE -> segment.distanceMeters?.let { meters ->
                    if (meters >= 1000f) String.format("%.2f km", meters / 1000f)
                    else String.format("%.0f m", meters)
                } ?: "—"
            }
            val pace = segment.targetPaceMinPerKm?.let { pace ->
                " @ ${SpeedPaceCalculator.formatPaceMmSs(pace)}"
            }.orEmpty()
            "• ${segment.title}: $goal$pace"
        }
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
                    binding.calendarView.date = selectedDayMillis
                    Toast.makeText(requireContext(), R.string.plan_applied, Toast.LENGTH_LONG).show()
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
