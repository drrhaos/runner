package com.runner.academy.ui.trainingplan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runner.academy.R
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.TrainingIcon
import com.runner.academy.data.TrainingPlanRepository
import com.runner.academy.data.WorkoutDatabase
import com.runner.academy.data.WorkoutType
import com.runner.academy.data.defaultTrainingIcon
import com.runner.academy.data.displayName
import com.runner.academy.data.isDefaultTitle
import com.runner.academy.data.parseTrainingIcon
import com.runner.academy.databinding.FragmentTemplateEditBinding
import com.runner.academy.databinding.ItemTemplateSegmentBinding
import kotlinx.coroutines.launch

class TemplateEditFragment : Fragment() {
    private var _binding: FragmentTemplateEditBinding? = null
    private val binding get() = _binding!!

    private val templateId: Long by lazy {
        arguments?.getLong("templateId", -1L) ?: -1L
    }

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

    private val drafts = mutableListOf<SegmentDraft>()
    private var selectedType: WorkoutType = WorkoutType.INTERVAL_TRAINING
    private var selectedIcon: TrainingIcon = TrainingIcon.INTERVAL
    private var iconChosenManually = false
    private lateinit var segmentAdapter: SegmentDraftAdapter
    private lateinit var iconAdapter: TrainingIconPickerAdapter

    private val templateIcons = listOf(
        TrainingIcon.RECOVERY,
        TrainingIcon.EASY,
        TrainingIcon.LONG,
        TrainingIcon.TEMPO,
        TrainingIcon.INTERVAL,
        TrainingIcon.RACE
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTemplateEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val types = WorkoutType.entries
        val typeNames = types.map { it.displayName(requireContext()) }
        binding.autoCompleteWorkoutType.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, typeNames)
        )
        binding.autoCompleteWorkoutType.setText(selectedType.displayName(requireContext()), false)
        binding.autoCompleteWorkoutType.setOnItemClickListener { _, _, position, _ ->
            selectedType = types[position]
            if (!iconChosenManually) {
                selectedIcon = selectedType.defaultTrainingIcon()
                iconAdapter.selected = selectedIcon
                updateIconHint()
            }
        }

        iconAdapter = TrainingIconPickerAdapter(templateIcons) { icon ->
            selectedIcon = icon
            iconChosenManually = true
            updateIconHint()
        }
        iconAdapter.selected = selectedIcon
        binding.recyclerViewIcons.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerViewIcons.adapter = iconAdapter
        updateIconHint()

        segmentAdapter = SegmentDraftAdapter(drafts) {
            segmentAdapter.notifyDataSetChanged()
        }
        binding.recyclerViewSegments.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewSegments.adapter = segmentAdapter
        binding.recyclerViewSegments.setHasFixedSize(false)
        binding.recyclerViewSegments.isNestedScrollingEnabled = false

        binding.buttonAddSegment.setOnClickListener {
            drafts.add(SegmentDraft(kind = SegmentKind.WORK))
            val index = drafts.lastIndex
            segmentAdapter.notifyItemInserted(index)
            scrollToNewestSegment()
        }
        binding.buttonSaveTemplate.setOnClickListener { save() }
        binding.buttonDeleteTemplate.setOnClickListener { confirmDelete() }

        if (templateId > 0) {
            binding.buttonDeleteTemplate.visibility = View.VISIBLE
            viewLifecycleOwner.lifecycleScope.launch {
                val loaded = viewModel.loadTemplate(templateId) ?: return@launch
                binding.editTextTemplateName.setText(loaded.template.name)
                selectedType = loaded.template.workoutType
                selectedIcon = parseTrainingIcon(
                    loaded.template.iconKey,
                    selectedType.defaultTrainingIcon()
                )
                iconChosenManually = true
                iconAdapter.selected = selectedIcon
                updateIconHint()
                binding.autoCompleteWorkoutType.setText(
                    selectedType.displayName(requireContext()),
                    false
                )
                drafts.clear()
                drafts.addAll(loaded.segments.map { SegmentDraft.fromEntity(it) })
                if (drafts.isEmpty()) {
                    drafts.add(SegmentDraft(title = getString(R.string.segment_kind_warmup)))
                }
                segmentAdapter.notifyDataSetChanged()
            }
        } else if (drafts.isEmpty()) {
            selectedIcon = selectedType.defaultTrainingIcon()
            iconAdapter.selected = selectedIcon
            updateIconHint()
            binding.root.visibility = View.INVISIBLE
            TemplateCreateWizard.start(
                context = requireContext(),
                onComplete = { config ->
                    if (!isAdded) return@start
                    applyGeneratedTemplate(config)
                },
                onSkip = {
                    if (!isAdded) return@start
                    applySkippedTemplate()
                },
                onCancel = {
                    if (isAdded) findNavController().navigateUp()
                }
            )
        }
    }

    private fun applyGeneratedTemplate(config: TemplateCreateConfig) {
        drafts.clear()
        drafts.addAll(TemplateCreateWizard.buildSegments(requireContext(), config))
        binding.editTextTemplateName.setText(
            TemplateCreateWizard.suggestedName(requireContext(), config)
        )
        selectedType = WorkoutType.INTERVAL_TRAINING
        selectedIcon = selectedType.defaultTrainingIcon()
        iconChosenManually = false
        iconAdapter.selected = selectedIcon
        binding.autoCompleteWorkoutType.setText(
            selectedType.displayName(requireContext()),
            false
        )
        updateIconHint()
        segmentAdapter.notifyDataSetChanged()
        binding.root.visibility = View.VISIBLE
    }

    private fun applySkippedTemplate() {
        drafts.clear()
        drafts.add(SegmentDraft(kind = SegmentKind.WORK))
        selectedType = WorkoutType.INTERVAL_TRAINING
        selectedIcon = selectedType.defaultTrainingIcon()
        iconChosenManually = false
        iconAdapter.selected = selectedIcon
        binding.autoCompleteWorkoutType.setText(
            selectedType.displayName(requireContext()),
            false
        )
        updateIconHint()
        segmentAdapter.notifyDataSetChanged()
        binding.root.visibility = View.VISIBLE
    }

    private fun updateIconHint() {
        binding.textViewIconHint.text = getString(
            R.string.intensity_icon_selected,
            selectedIcon.displayName(requireContext())
        )
    }

    private fun scrollToNewestSegment() {
        val recycler = binding.recyclerViewSegments
        val scroll = binding.scrollTemplateEdit
        recycler.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    recycler.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        )
        recycler.requestLayout()
    }

    private fun save() {
        binding.root.clearFocus()
        flushSegmentDrafts()
        val name = binding.editTextTemplateName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty() || drafts.isEmpty()) {
            Toast.makeText(requireContext(), R.string.fill_required_fields, Toast.LENGTH_SHORT).show()
            return
        }
        val ctx = requireContext()
        drafts.forEach { draft ->
            if (draft.kind.isDefaultTitle(ctx, draft.title)) {
                draft.title = ""
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val id = if (templateId > 0) templateId else 0L
            viewModel.saveTemplate(
                id = id,
                name = name,
                type = selectedType,
                icon = selectedIcon,
                segments = drafts.mapIndexed { index, draft -> draft.toEntity(0, index) }
            )
            Toast.makeText(requireContext(), R.string.template_saved, Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
        }
    }

    private fun flushSegmentDrafts() {
        for (i in 0 until binding.recyclerViewSegments.childCount) {
            val child = binding.recyclerViewSegments.getChildAt(i)
            val holder = binding.recyclerViewSegments.getChildViewHolder(child) as? SegmentDraftAdapter.VH
            holder?.flushToDraft()
        }
    }

    private fun confirmDelete() {
        if (templateId <= 0) return
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.template_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.loadTemplate(templateId)?.template?.let { viewModel.deleteTemplate(it) }
                    findNavController().navigateUp()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class SegmentDraftAdapter(
    private val drafts: MutableList<SegmentDraft>,
    private val onChanged: () -> Unit
) : RecyclerView.Adapter<SegmentDraftAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTemplateSegmentBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount() = drafts.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(drafts[position], position)
    }

    inner class VH(private val binding: ItemTemplateSegmentBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private var boundDraft: SegmentDraft? = null

        fun flushToDraft() {
            val draft = boundDraft ?: return
            draft.title = binding.editTextSegmentTitle.text?.toString().orEmpty()
        }

        fun bind(draft: SegmentDraft, position: Int) {
            boundDraft = draft
            val ctx = binding.root.context
            val kinds = SegmentKind.entries
            val kindLabels = kinds.map { it.displayName(ctx) }
            binding.autoCompleteSegmentKind.setAdapter(
                ArrayAdapter(ctx, android.R.layout.simple_list_item_1, kindLabels)
            )
            binding.autoCompleteSegmentKind.setText(
                kindLabels[kinds.indexOf(draft.kind)],
                false
            )
            binding.autoCompleteSegmentKind.setOnItemClickListener { _, _, i, _ ->
                val previousKind = draft.kind
                val newKind = kinds[i]
                val titleNow = binding.editTextSegmentTitle.text?.toString().orEmpty()
                if (previousKind.isDefaultTitle(ctx, titleNow)) {
                    val localized = newKind.displayName(ctx)
                    binding.editTextSegmentTitle.setText(localized)
                    draft.title = localized
                }
                draft.kind = newKind
            }

            val goals = SegmentGoalType.entries
            val goalLabels = listOf(
                ctx.getString(R.string.segment_goal_duration),
                ctx.getString(R.string.segment_goal_distance)
            )
            binding.autoCompleteSegmentGoal.setAdapter(
                ArrayAdapter(ctx, android.R.layout.simple_list_item_1, goalLabels)
            )
            binding.autoCompleteSegmentGoal.setText(
                if (draft.goalType == SegmentGoalType.DURATION) goalLabels[0] else goalLabels[1],
                false
            )
            fun updateGoalVisibility() {
                val duration = draft.goalType == SegmentGoalType.DURATION
                binding.layoutDuration.visibility = if (duration) View.VISIBLE else View.GONE
                binding.layoutDistance.visibility = if (duration) View.GONE else View.VISIBLE
            }
            updateGoalVisibility()
            binding.autoCompleteSegmentGoal.setOnItemClickListener { _, _, i, _ ->
                draft.goalType = goals[i]
                updateGoalVisibility()
            }

            val titleForUi = if (draft.kind.isDefaultTitle(ctx, draft.title)) {
                draft.kind.displayName(ctx)
            } else {
                draft.title
            }
            binding.editTextSegmentTitle.setText(titleForUi)
            bindValueFields(draft)

            binding.editTextSegmentTitle.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) draft.title = binding.editTextSegmentTitle.text?.toString().orEmpty()
            }
            binding.editTextDuration.setOnClickListener {
                showDurationValuePicker(
                    context = ctx,
                    titleRes = R.string.segment_dialog_duration_title,
                    initialSeconds = draft.durationTotalSeconds,
                    onPicked = { seconds ->
                        draft.durationTotalSeconds = seconds
                        bindValueFields(draft)
                    }
                )
            }
            binding.editTextDistance.setOnClickListener {
                showDistanceValuePicker(
                    context = ctx,
                    titleRes = R.string.segment_dialog_distance_title,
                    initialMeters = draft.distanceTotalMeters,
                    onPicked = { meters ->
                        draft.distanceTotalMeters = meters
                        bindValueFields(draft)
                    }
                )
            }
            binding.editTextPace.setOnClickListener {
                showPaceValuePicker(
                    context = ctx,
                    titleRes = R.string.segment_dialog_pace_title,
                    initialPaceSeconds = draft.paceTotalSeconds,
                    onPicked = { pace ->
                        draft.paceTotalSeconds = pace
                        bindValueFields(draft)
                    }
                )
            }

            binding.buttonRemoveSegment.setOnClickListener {
                flushToDraft()
                val pos = drafts.indexOf(draft)
                if (pos >= 0) {
                    drafts.removeAt(pos)
                    notifyItemRemoved(pos)
                    onChanged()
                }
            }
        }

        private fun bindValueFields(draft: SegmentDraft) {
            val ctx = binding.root.context
            binding.editTextDuration.setText(formatSegmentDuration(draft.durationTotalSeconds))
            binding.editTextDistance.setText(formatSegmentDistance(ctx, draft.distanceTotalMeters))
            binding.editTextPace.setText(formatSegmentPace(ctx, draft.paceTotalSeconds))
        }
    }
}
