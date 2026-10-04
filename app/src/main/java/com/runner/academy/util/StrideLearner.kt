package com.runner.academy.util

/**
 * Feeds a run's good GPS stretches to the [StrideModel]: accepted, ordinary steps (no gap, no
 * bridge) with steps and a reliable signal are summed into a stretch; once it spans
 * [minDurationMs] and [minDistanceM] it becomes one [StrideModel.learn] sample (distance,
 * steps, mean cadence) and a new stretch begins. Anything else (a gap, a bridge, a fix
 * without steps, an unreliable signal, [reset] on pause / standing still) restarts it.
 *
 * [onLearned] gets the model after every accepted sample, to persist it at once: a process
 * death mid-run then keeps what was learned.
 *
 * Not thread-safe, like the model: use it on the thread that processes fixes.
 */
class StrideLearner(
    private val model: StrideModel,
    private val minDurationMs: Long = DEFAULT_MIN_DURATION_MS,
    private val minDistanceM: Float = DEFAULT_MIN_DISTANCE_M,
    private val onLearned: (StrideModel) -> Unit = {}
) {
    private var startSteps: Int? = null
    private var startTimeMs = 0L
    private var distanceM = 0f

    /** @return true when this fix completed a stretch that the model used. */
    fun onAccepted(
        segmentMeters: Float,
        afterGap: Boolean,
        bridged: Boolean,
        steps: Int?,
        timeMs: Long,
        reliable: Boolean
    ): Boolean {
        val from = startSteps
        if (steps == null || !reliable) {
            reset()
            return false
        }
        if (from == null || afterGap || bridged || steps < from || timeMs <= startTimeMs) {
            begin(steps, timeMs)
            return false
        }
        distanceM += segmentMeters
        val elapsedMs = timeMs - startTimeMs
        if (elapsedMs < minDurationMs || distanceM < minDistanceM) return false
        val stepCount = steps - from
        val cadence = stepCount * 60_000f / elapsedMs
        val used = model.learn(distanceM, stepCount, cadence)
        begin(steps, timeMs)
        if (used) onLearned(model)
        return used
    }

    fun reset() {
        startSteps = null
        distanceM = 0f
    }

    private fun begin(steps: Int, timeMs: Long) {
        startSteps = steps
        startTimeMs = timeMs
        distanceM = 0f
    }

    companion object {
        const val DEFAULT_MIN_DURATION_MS = 30_000L
        const val DEFAULT_MIN_DISTANCE_M = 100f
    }
}
