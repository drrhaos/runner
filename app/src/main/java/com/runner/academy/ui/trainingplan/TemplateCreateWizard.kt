package com.runner.academy.ui.trainingplan

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runner.academy.R
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind

/** Answers collected by the new-base-workout wizard. */
data class TemplateCreateConfig(
    val intervalCount: Int = 4,
    val intervalGoal: SegmentGoalType = SegmentGoalType.DURATION,
    val intervalDurationSeconds: Int = 3 * 60,
    val intervalDistanceMeters: Int = 400,
    val paceTotalSeconds: Int = 0,
    val recoveryGoal: SegmentGoalType = SegmentGoalType.DURATION,
    val recoveryDurationSeconds: Int = 2 * 60,
    val recoveryDistanceMeters: Int = 200,
    val recoveryPaceTotalSeconds: Int = 0,
    val includeWarmup: Boolean = true,
    val warmupGoal: SegmentGoalType = SegmentGoalType.DURATION,
    val warmupDurationSeconds: Int = 10 * 60,
    val warmupDistanceMeters: Int = 1000,
    val warmupPaceTotalSeconds: Int = 0,
    val includeCooldown: Boolean = true,
    val cooldownGoal: SegmentGoalType = SegmentGoalType.DURATION,
    val cooldownDurationSeconds: Int = 10 * 60,
    val cooldownDistanceMeters: Int = 1000,
    val cooldownPaceTotalSeconds: Int = 0
)

object TemplateCreateWizard {

    private const val MAX_INTERVALS = 100

    /**
     * Builds: [warmup?] - Interval1 - [recovery - Interval]* - [cooldown?]
     */
    fun buildSegments(context: Context, config: TemplateCreateConfig): List<SegmentDraft> {
        val segments = mutableListOf<SegmentDraft>()
        if (config.includeWarmup) {
            segments += segmentDraft(
                kind = SegmentKind.WARMUP,
                goal = config.warmupGoal,
                durationSeconds = config.warmupDurationSeconds,
                distanceMeters = config.warmupDistanceMeters,
                paceSeconds = config.warmupPaceTotalSeconds
            )
        }
        for (index in 1..config.intervalCount.coerceAtLeast(0)) {
            if (index > 1) {
                segments += segmentDraft(
                    kind = SegmentKind.RECOVERY,
                    goal = config.recoveryGoal,
                    durationSeconds = config.recoveryDurationSeconds,
                    distanceMeters = config.recoveryDistanceMeters,
                    paceSeconds = config.recoveryPaceTotalSeconds
                )
            }
            segments += SegmentDraft(
                title = context.getString(R.string.segment_interval_name, index),
                kind = SegmentKind.WORK,
                goalType = config.intervalGoal,
                durationTotalSeconds = config.intervalDurationSeconds.coerceAtLeast(1),
                distanceTotalMeters = config.intervalDistanceMeters.coerceAtLeast(1),
                paceTotalSeconds = config.paceTotalSeconds.coerceAtLeast(0)
            )
        }
        if (config.includeCooldown) {
            segments += segmentDraft(
                kind = SegmentKind.COOLDOWN,
                goal = config.cooldownGoal,
                durationSeconds = config.cooldownDurationSeconds,
                distanceMeters = config.cooldownDistanceMeters,
                paceSeconds = config.cooldownPaceTotalSeconds
            )
        }
        return segments
    }

    fun suggestedName(context: Context, config: TemplateCreateConfig): String {
        return context.getString(R.string.template_wizard_default_name, config.intervalCount)
    }

    fun start(
        context: Context,
        onComplete: (TemplateCreateConfig) -> Unit,
        onSkip: () -> Unit,
        onCancel: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.template_wizard_intro_title)
            .setMessage(R.string.template_wizard_intro_message)
            .setPositiveButton(R.string.template_wizard_generate) { _, _ ->
                askIntervalCount(context, TemplateCreateConfig(), onComplete, onCancel)
            }
            .setNegativeButton(R.string.template_wizard_skip) { _, _ -> onSkip() }
            .setNeutralButton(R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun segmentDraft(
        kind: SegmentKind,
        goal: SegmentGoalType,
        durationSeconds: Int,
        distanceMeters: Int,
        paceSeconds: Int
    ): SegmentDraft {
        return SegmentDraft(
            kind = kind,
            goalType = goal,
            durationTotalSeconds = durationSeconds.coerceAtLeast(1),
            distanceTotalMeters = distanceMeters.coerceAtLeast(1),
            paceTotalSeconds = paceSeconds.coerceAtLeast(0)
        )
    }

    private fun askIntervalCount(
        context: Context,
        config: TemplateCreateConfig,
        onComplete: (TemplateCreateConfig) -> Unit,
        onCancel: () -> Unit
    ) {
        showCountPicker(
            context = context,
            titleRes = R.string.template_wizard_intervals_title,
            min = 0,
            max = MAX_INTERVALS,
            initial = config.intervalCount.coerceIn(0, MAX_INTERVALS),
            onPicked = { count ->
                val next = config.copy(intervalCount = count)
                if (count == 0) {
                    askWarmup(context, next, onComplete, onCancel)
                } else {
                    askGoalType(
                        context = context,
                        titleRes = R.string.template_wizard_goal_title,
                        onPicked = { goal ->
                            askGoalParams(
                                context = context,
                                goal = goal,
                                durationTitleRes = R.string.template_wizard_interval_duration_title,
                                distanceTitleRes = R.string.template_wizard_interval_distance_title,
                                initialDurationSeconds = next.intervalDurationSeconds,
                                initialDistanceMeters = next.intervalDistanceMeters,
                                onPicked = { duration, distance ->
                                    askPace(
                                        context,
                                        next.copy(
                                            intervalGoal = goal,
                                            intervalDurationSeconds = duration,
                                            intervalDistanceMeters = distance
                                        ),
                                        onComplete,
                                        onCancel
                                    )
                                },
                                onCancel = onCancel
                            )
                        },
                        onCancel = onCancel
                    )
                }
            },
            onCancel = onCancel
        )
    }

    private fun askPace(
        context: Context,
        config: TemplateCreateConfig,
        onComplete: (TemplateCreateConfig) -> Unit,
        onCancel: () -> Unit
    ) {
        showPaceValuePicker(
            context = context,
            titleRes = R.string.template_wizard_pace_title,
            initialPaceSeconds = config.paceTotalSeconds,
            onPicked = { pace ->
                val next = config.copy(paceTotalSeconds = pace)
                if (next.intervalCount > 1) {
                    askRecoveryGoal(context, next, onComplete, onCancel)
                } else {
                    askWarmup(context, next, onComplete, onCancel)
                }
            },
            onCancel = onCancel
        )
    }

    private fun askRecoveryGoal(
        context: Context,
        config: TemplateCreateConfig,
        onComplete: (TemplateCreateConfig) -> Unit,
        onCancel: () -> Unit
    ) {
        askGoalType(
            context = context,
            titleRes = R.string.template_wizard_recovery_goal_title,
            onPicked = { goal ->
                askGoalParams(
                    context = context,
                    goal = goal,
                    durationTitleRes = R.string.template_wizard_recovery_duration_title,
                    distanceTitleRes = R.string.template_wizard_recovery_distance_title,
                    initialDurationSeconds = config.recoveryDurationSeconds,
                    initialDistanceMeters = config.recoveryDistanceMeters,
                    onPicked = { duration, distance ->
                        showPaceValuePicker(
                            context = context,
                            titleRes = R.string.template_wizard_recovery_pace_title,
                            initialPaceSeconds = config.recoveryPaceTotalSeconds,
                            onPicked = { pace ->
                                askWarmup(
                                    context,
                                    config.copy(
                                        recoveryGoal = goal,
                                        recoveryDurationSeconds = duration,
                                        recoveryDistanceMeters = distance,
                                        recoveryPaceTotalSeconds = pace,
                                        warmupPaceTotalSeconds = pace,
                                        cooldownPaceTotalSeconds = pace
                                    ),
                                    onComplete,
                                    onCancel
                                )
                            },
                            onCancel = onCancel
                        )
                    },
                    onCancel = onCancel
                )
            },
            onCancel = onCancel
        )
    }

    private fun askWarmup(
        context: Context,
        config: TemplateCreateConfig,
        onComplete: (TemplateCreateConfig) -> Unit,
        onCancel: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.template_wizard_warmup_title)
            .setMessage(R.string.template_wizard_warmup_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                askGoalType(
                    context = context,
                    titleRes = R.string.template_wizard_warmup_goal_title,
                    onPicked = { goal ->
                        askGoalParams(
                            context = context,
                            goal = goal,
                            durationTitleRes = R.string.template_wizard_warmup_duration_title,
                            distanceTitleRes = R.string.template_wizard_warmup_distance_title,
                            initialDurationSeconds = config.warmupDurationSeconds,
                            initialDistanceMeters = config.warmupDistanceMeters,
                            onPicked = { duration, distance ->
                                showPaceValuePicker(
                                    context = context,
                                    titleRes = R.string.template_wizard_warmup_pace_title,
                                    initialPaceSeconds = config.recoveryPaceTotalSeconds,
                                    onPicked = { pace ->
                                        askCooldown(
                                            context,
                                            config.copy(
                                                includeWarmup = true,
                                                warmupGoal = goal,
                                                warmupDurationSeconds = duration,
                                                warmupDistanceMeters = distance,
                                                warmupPaceTotalSeconds = pace
                                            ),
                                            onComplete,
                                            onCancel
                                        )
                                    },
                                    onCancel = onCancel
                                )
                            },
                            onCancel = onCancel
                        )
                    },
                    onCancel = onCancel
                )
            }
            .setNegativeButton(R.string.no) { _, _ ->
                askCooldown(
                    context,
                    config.copy(includeWarmup = false),
                    onComplete,
                    onCancel
                )
            }
            .setNeutralButton(R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun askCooldown(
        context: Context,
        config: TemplateCreateConfig,
        onComplete: (TemplateCreateConfig) -> Unit,
        onCancel: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.template_wizard_cooldown_title)
            .setMessage(R.string.template_wizard_cooldown_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                askGoalType(
                    context = context,
                    titleRes = R.string.template_wizard_cooldown_goal_title,
                    onPicked = { goal ->
                        askGoalParams(
                            context = context,
                            goal = goal,
                            durationTitleRes = R.string.template_wizard_cooldown_duration_title,
                            distanceTitleRes = R.string.template_wizard_cooldown_distance_title,
                            initialDurationSeconds = config.cooldownDurationSeconds,
                            initialDistanceMeters = config.cooldownDistanceMeters,
                            onPicked = { duration, distance ->
                                showPaceValuePicker(
                                    context = context,
                                    titleRes = R.string.template_wizard_cooldown_pace_title,
                                    initialPaceSeconds = config.recoveryPaceTotalSeconds,
                                    onPicked = { pace ->
                                        onComplete(
                                            config.copy(
                                                includeCooldown = true,
                                                cooldownGoal = goal,
                                                cooldownDurationSeconds = duration,
                                                cooldownDistanceMeters = distance,
                                                cooldownPaceTotalSeconds = pace
                                            )
                                        )
                                    },
                                    onCancel = onCancel
                                )
                            },
                            onCancel = onCancel
                        )
                    },
                    onCancel = onCancel
                )
            }
            .setNegativeButton(R.string.no) { _, _ ->
                onComplete(config.copy(includeCooldown = false))
            }
            .setNeutralButton(R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun askGoalType(
        context: Context,
        titleRes: Int,
        onPicked: (SegmentGoalType) -> Unit,
        onCancel: () -> Unit
    ) {
        val options = arrayOf(
            context.getString(R.string.segment_goal_duration),
            context.getString(R.string.segment_goal_distance)
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setItems(options) { _, which ->
                onPicked(if (which == 0) SegmentGoalType.DURATION else SegmentGoalType.DISTANCE)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun askGoalParams(
        context: Context,
        goal: SegmentGoalType,
        durationTitleRes: Int,
        distanceTitleRes: Int,
        initialDurationSeconds: Int,
        initialDistanceMeters: Int,
        onPicked: (durationSeconds: Int, distanceMeters: Int) -> Unit,
        onCancel: () -> Unit
    ) {
        when (goal) {
            SegmentGoalType.DURATION -> showDurationValuePicker(
                context = context,
                titleRes = durationTitleRes,
                initialSeconds = initialDurationSeconds,
                onPicked = { seconds -> onPicked(seconds, initialDistanceMeters) },
                onCancel = onCancel
            )
            SegmentGoalType.DISTANCE -> showDistanceValuePicker(
                context = context,
                titleRes = distanceTitleRes,
                initialMeters = initialDistanceMeters,
                onPicked = { meters -> onPicked(initialDurationSeconds, meters) },
                onCancel = onCancel
            )
        }
    }
}
