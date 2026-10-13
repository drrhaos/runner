package com.runner.academy.ui.workout

import com.runner.academy.util.HrMax
import com.runner.academy.util.HrZones

/**
 * The "Time in zones" card of the details: the track's time in each zone by the current HRmax,
 * computed when the details are read (a change of HRmax recolours old workouts).
 */
sealed interface HrZonesDisplay {

    /** Zones are off, no heart rate or under a minute of it: no card. */
    data object None : HrZonesDisplay

    /**
     * @param hrMax what the zones are built on, shown under the title.
     * @param rows Z5 down to Z1, then below Z1: all six, an empty one with 0:00 and 0 %.
     */
    data class Value(val hrMax: HrMax, val rows: List<Row>) : HrZonesDisplay

    /**
     * One row of the card.
     *
     * @param zone 1..5, or 0 below Z1.
     * @param range the zone's beats; below Z1 `0 until` the Z1 bound ([belowBpm]).
     * @param percent of the covered time (the sum of the six), the six sum up to 100.
     * @param fraction the bar: the share of the covered time.
     */
    data class Row(val zone: Int, val range: IntRange, val ms: Long, val percent: Int, val fraction: Float) {
        /** Below Z1: under this many beats. */
        val belowBpm: Int get() = range.last + 1
    }

    companion object {
        /** [ms] from [com.runner.academy.util.TrackHeartRate.timeInZones]: index 0 below Z1, 1..5 the zones. */
        fun of(hrMax: HrMax?, ms: LongArray?): HrZonesDisplay {
            if (hrMax == null || ms == null) return None
            val zones = hrMax.zones
            val total = ms.sum()
            if (total <= 0L) return None
            val percents = largestRemainderPercents(ms)
            val rows = (HrZones.COUNT downTo HrZones.BELOW).map { zone ->
                Row(
                    zone = zone,
                    range = if (zone == HrZones.BELOW) 0 until zones.lowerBounds[0] else zones.range(zone),
                    ms = ms[zone],
                    percent = percents[zone],
                    fraction = ms[zone].toFloat() / total
                )
            }
            return Value(hrMax, rows)
        }

        /**
         * Whole percents of the sum of [values] that add up to exactly 100: each its floor, and
         * the points left over go to the largest remainders (ties: the earlier one). All zeros
         * when the sum is 0.
         */
        fun largestRemainderPercents(values: LongArray): IntArray {
            val total = values.sum()
            val percents = IntArray(values.size)
            if (total <= 0L) return percents
            val remainders = LongArray(values.size)
            for (i in values.indices) {
                percents[i] = (values[i] * 100 / total).toInt()
                remainders[i] = values[i] * 100 % total
            }
            val left = 100 - percents.sum()
            values.indices
                .sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { it })
                .take(left)
                .forEach { percents[it] += 1 }
            return percents
        }
    }
}
