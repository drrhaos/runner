package com.runner.academy.ui.trainingplan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.TrainingIcon
import com.runner.academy.data.WorkoutType
import com.runner.academy.data.defaultTrainingIcon
import com.runner.academy.data.displayName
import com.runner.academy.data.isDefaultTitle
import com.runner.academy.data.parseTrainingIcon
import com.runner.academy.databinding.FragmentTemplateEditBinding
import com.runner.academy.databinding.ItemTemplateSegmentBinding
import com.runner.academy.util.HrZones
import kotlinx.coroutines.launch

class TemplateEditFragment : Fragment() {
    private var _binding: FragmentTemplateEditBinding? = null
    private val binding get() = _binding!!

    private val templateId: Long by lazy {
        arguments?.getLong("templateId", -1L) ?: -1L
    }

    private val viewModel: TrainingPlanViewModel by viewModels {
        TrainingPlanViewModelFactory(requireContext().appContainer().trainingPlanRepository)
    }

    /** Unsaved drafts, type and icon: kept across a rotation and the trip to the settings. */
    private val editor: TemplateEditorViewModel by viewModels()

    private val drafts: MutableList<SegmentDraft> get() = editor.drafts
    private var selectedType: WorkoutType
        get() = editor.selectedType
        set(value) { editor.selectedType = value }
    private var selectedIcon: TrainingIcon
        get() = editor.selectedIcon
        set(value) { editor.selectedIcon = value }
    private var iconChosenManually: Boolean
        get() = editor.iconChosenManually
        set(value) { editor.iconChosenManually = value }
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

        val zones = readZones()
        segmentAdapter = SegmentDraftAdapter(
            drafts = drafts,
            zones = zones,
            onChanged = { segmentAdapter.notifyDataSetChanged() }
        )
        updateZonesOffHint(zones)
        binding.buttonOpenSettings.setOnClickListener {
            findNavController().navigate(R.id.nav_settings_from_editor)
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
            // A rebuilt view (rotation, back from the settings) keeps the unsaved drafts.
            if (editor.initialized) return
            viewLifecycleOwner.lifecycleScope.launch {
                val loaded = viewModel.loadTemplate(templateId) ?: return@launch
                editor.initialized = true
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
        } else if (!editor.initialized) {
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
        editor.initialized = true
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
        editor.initialized = true
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

    override fun onResume() {
        super.onResume()
        // Zones are re-read on every resume (back from the settings among others): when the
        // maximum heart rate was set, changed or cleared, the cards redraw the ranges and the
        // zone button, and the zones-off hint follows.
        val zones = readZones()
        updateZonesOffHint(zones)
        if (zones != segmentAdapter.zones) {
            segmentAdapter.zones = zones
            segmentAdapter.notifyItemRangeChanged(0, segmentAdapter.itemCount)
        }
    }

    private fun readZones(): HrZones? = requireContext().appContainer().userPreferences.hrZones()

    /** «Zones are off…» and «Open settings» once above the segment list, not on every card. */
    private fun updateZonesOffHint(zones: HrZones?) {
        val visibility = if (zones == null) View.VISIBLE else View.GONE
        binding.textViewZoneDisabledHint.visibility = visibility
        binding.buttonOpenSettings.visibility = visibility
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
    /** The zones by the current maximum heart rate; null — the zones are off. */
    var zones: HrZones?,
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

        init {
            // The title goes straight into the draft: nothing to flush before a save, a removal
            // or the view going away (rotation, the trip to the settings).
            binding.editTextSegmentTitle.doAfterTextChanged { text ->
                boundDraft?.title = text?.toString().orEmpty()
            }
            // TalkBack: «Target pace or zone» labels this card's toggle group. Card views share
            // the layout ids, so the group gets an id of its own for the label to point at.
            val groupId = View.generateViewId()
            binding.toggleSegmentTarget.id = groupId
            binding.textViewTargetLabel.labelFor = groupId
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
                        // 0:00 clears the pace, and with it the target.
                        if (pace <= 0) draft.targetKind = TargetKind.NONE
                        bindValueFields(draft)
                    }
                )
            }
            binding.editTextHrZone.setOnClickListener {
                val pickerZones = ZoneTargetUi.of(draft.targetKind, zones).pickerZones
                if (pickerZones == null) {
                    Snackbar.make(binding.root, R.string.segment_target_zone_disabled, Snackbar.LENGTH_LONG).show()
                } else {
                    showZoneValuePicker(ctx, pickerZones, draft.hrZone, onPicked = { zone ->
                        draft.hrZone = zone
                        bindValueFields(draft)
                    })
                }
            }

            binding.buttonRemoveSegment.setOnClickListener {
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
            binding.editTextHrZone.setText(formatSegmentZone(ctx, draft.hrZone, zones))
            bindTarget(draft)
        }

        /** «None / Pace / Zone» and the field of the selected one (design §2). */
        private fun bindTarget(draft: SegmentDraft) {
            val ui = ZoneTargetUi.of(draft.targetKind, zones)
            val group = binding.toggleSegmentTarget
            group.clearOnButtonCheckedListeners()
            // Enable first: a saved zone with the zones off must be checkable.
            binding.buttonTargetZone.isEnabled = ui.zoneButtonEnabled
            group.check(buttonId(draft.targetKind))
            binding.layoutPace.visibility = if (draft.targetKind == TargetKind.PACE) View.VISIBLE else View.GONE
            binding.layoutHrZone.visibility = if (draft.targetKind == TargetKind.ZONE) View.VISIBLE else View.GONE
            group.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) onTargetChosen(draft, kindOf(checkedId))
            }
        }

        private fun onTargetChosen(draft: SegmentDraft, kind: TargetKind) {
            if (kind == draft.targetKind) return
            val ctx = binding.root.context
            val ui = ZoneTargetUi.of(draft.targetKind, zones)
            when {
                kind == TargetKind.NONE -> select(draft, kind)
                kind == TargetKind.PACE && draft.paceTotalSeconds > 0 -> select(draft, kind)
                kind == TargetKind.PACE -> showPaceValuePicker(
                    context = ctx,
                    titleRes = R.string.segment_dialog_pace_title,
                    initialPaceSeconds = 0,
                    onPicked = { pace ->
                        if (pace > 0) draft.paceTotalSeconds = pace
                        if (pace > 0) select(draft, kind) else bindTarget(draft)
                    },
                    onCancel = { bindTarget(draft) }
                )
                // «Zone» is only enabled from another kind when the zones are on: the picker opens.
                else -> ui.pickerZones?.let { pickerZones ->
                    showZoneValuePicker(
                        context = ctx,
                        zones = pickerZones,
                        initialZone = draft.hrZone,
                        onPicked = { zone ->
                            draft.hrZone = zone
                            select(draft, kind)
                        },
                        onCancel = { bindTarget(draft) }
                    )
                } ?: bindTarget(draft)
            }
        }

        private fun select(draft: SegmentDraft, kind: TargetKind) {
            draft.targetKind = kind
            bindValueFields(draft)
        }

        private fun buttonId(kind: TargetKind): Int = when (kind) {
            TargetKind.NONE -> R.id.button_target_none
            TargetKind.PACE -> R.id.button_target_pace
            TargetKind.ZONE -> R.id.button_target_zone
        }

        private fun kindOf(buttonId: Int): TargetKind = when (buttonId) {
            R.id.button_target_pace -> TargetKind.PACE
            R.id.button_target_zone -> TargetKind.ZONE
            else -> TargetKind.NONE
        }
    }
}
