package com.runner.academy.util

import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind

/** Reads the pause intervals of a saved track ([com.runner.academy.data.TrackData.pauses]). */
object TrackPauses {

    /**
     * Time of [from]..[to] (wall-clock ms) that falls inside pauses of any kind, overlapping
     * pauses (a manual one on top of an auto-pause) counted once. Null pauses (an older track)
     * = no pauses; a reversed span overlaps nothing.
     */
    fun overlapMs(pauses: List<PauseInterval>?, from: Long, to: Long): Long {
        if (pauses.isNullOrEmpty() || to <= from) return 0L
        // The union of the pauses clipped to the span, swept in start order
        var total = 0L
        var coveredTo = from
        for (pause in pauses.sortedBy { it.start }) {
            val start = maxOf(pause.start, coveredTo)
            val end = minOf(pause.end, to)
            if (end > start) {
                total += end - start
                coveredTo = end
            }
        }
        return total
    }

    /** True when [timeMs] falls strictly inside a pause of any kind (its ends are not). */
    fun isInside(pauses: List<PauseInterval>?, timeMs: Long): Boolean =
        pauses.orEmpty().any { timeMs > it.start && timeMs < it.end }

    /** Sum of the intervals of [kind]. */
    fun totalMs(pauses: List<PauseInterval>?, kind: PauseKind): Long =
        pauses.orEmpty().filter { it.kind == kind }.sumOf { (it.end - it.start).coerceAtLeast(0L) }
}
