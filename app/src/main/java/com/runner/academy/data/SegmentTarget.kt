package com.runner.academy.data

import com.runner.academy.util.HrZones

/**
 * The one target of a segment: a pace, a heart rate zone or none. Stored as two nullable
 * columns ([WorkoutTemplateSegment.targetPaceMinPerKm], [WorkoutTemplateSegment.targetHrZone]);
 * writers keep them exclusive, and when both are set (a hand-edited backup) the zone wins.
 */
sealed interface SegmentTarget {

    data object None : SegmentTarget

    data class Pace(val minPerKm: Float) : SegmentTarget

    /** [zone] is 1..5. */
    data class HrZone(val zone: Int) : SegmentTarget

    companion object {
        /** [zone] when it is a real zone 1..5, else null. */
        fun validZoneOrNull(zone: Int?): Int? = zone?.takeIf { it in 1..HrZones.COUNT }
    }
}

/** A zone 1..5 → [SegmentTarget.HrZone]; else a pace > 0 → [SegmentTarget.Pace]; else none. */
val WorkoutTemplateSegment.target: SegmentTarget
    get() {
        SegmentTarget.validZoneOrNull(targetHrZone)?.let { return SegmentTarget.HrZone(it) }
        targetPaceMinPerKm?.takeIf { it > 0f }?.let { return SegmentTarget.Pace(it) }
        return SegmentTarget.None
    }
