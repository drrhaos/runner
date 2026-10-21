package com.runner.academy.data

import com.runner.academy.voice.TargetState

/**
 * The current segment as the service's interval engine sees it on the 1 s tick: the one engine
 * of a workout, so the segment bar, the voice and the target indicator never disagree.
 *
 * @param segmentIndex the current segment (the last one once [completed]).
 * @param segmentProgress 0..1 of its goal (time or distance).
 * @param target the target check's verdict; [TargetState.NONE] without a target.
 * @param hintDue a target hint came due on this tick and was given: by the voice ([hintVoiced])
 *   or, with the voice or its target cues off, left to the screen (TalkBack announces it).
 * @param hintVoiced the voice said this tick's hint.
 */
data class IntervalLive(
    val segmentIndex: Int,
    val segmentProgress: Float,
    val segmentElapsedMs: Long,
    val completed: Boolean,
    val target: TargetState,
    val hintDue: Boolean = false,
    val hintVoiced: Boolean = false
)
