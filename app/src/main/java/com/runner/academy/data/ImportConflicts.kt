package com.runner.academy.data

import kotlin.math.abs

/**
 * A workout read for import; [source] names where it came from (the file name), null for a
 * backup row.
 */
data class ImportCandidate(val workout: Workout, val source: String? = null)

/** What one batch of an import did with its candidates. */
data class ImportBatch(
    /** Saved as new workouts, in the batch's order. */
    val insertedIds: List<Long>,
    /** Overlapping others: put aside for the user ([PendingImport]). */
    val pending: Int,
    /** Copies of a workout already saved or waiting (an import run again): dropped. */
    val duplicates: Int
)

/** One side of a conflict: a saved workout ([existingId]) or a waiting one ([pendingId]). */
data class ConflictSide(
    val startMs: Long,
    val durationMs: Long,
    val distanceKm: Float,
    val hasTrack: Boolean,
    val existingId: Long? = null,
    val pendingId: Long? = null,
    val source: String? = null
)

/** A waiting workout and what it overlaps now (empty: nothing any more, it can just be imported). */
data class PendingConflict(val incoming: ConflictSide, val others: List<ConflictSide>) {
    val pendingId: Long get() = incoming.pendingId!!
}

enum class ConflictResolution {
    /** The waiting workout is dropped. */
    KEEP_EXISTING,

    /** The waiting workout takes the place of every saved workout it overlaps. */
    REPLACE,

    /** It is saved beside them. */
    KEEP_BOTH
}

/**
 * When two workouts collide. Their times overlap: from [Workout.date] for [Workout.duration]
 * (at least a second, so a workout without a time still has a moment). And when one is a mere
 * copy of the other ([isSame]): the same import run again.
 */
object ImportConflicts {

    /**
     * No workout lasts longer (a week): the look-up of overlapping ones searches from this far
     * before, on the indexed start, instead of the whole history.
     */
    const val MAX_SPAN_MS = 7 * 24 * 60 * 60 * 1000L

    private const val MIN_SPAN_MS = 1000L
    private const val SAME_START_MS = 1000L
    private const val SAME_SHARE = 0.01

    /** The span of a workout, both ends inclusive. */
    fun spanOf(startMs: Long, durationMs: Long): LongRange =
        startMs until startMs + durationMs.coerceAtLeast(MIN_SPAN_MS)

    fun overlap(a: LongRange, b: LongRange): Boolean = a.first <= b.last && b.first <= a.last

    /**
     * [a] and [b] are the same workout: the start within a second, the time and the distance
     * within 1 % (a second, 10 m at least) — a row read again, not another recording of the run.
     */
    fun isSame(a: ConflictSide, b: ConflictSide): Boolean =
        abs(a.startMs - b.startMs) <= SAME_START_MS &&
            close(a.durationMs.toDouble(), b.durationMs.toDouble(), minDelta = 1000.0) &&
            close(a.distanceKm.toDouble(), b.distanceKm.toDouble(), minDelta = 0.01)

    private fun close(a: Double, b: Double, minDelta: Double): Boolean =
        abs(a - b) <= maxOf(minDelta, SAME_SHARE * maxOf(abs(a), abs(b)))

    fun side(workout: Workout, source: String? = null) = ConflictSide(
        workout.date.time, workout.duration, workout.distance, workout.trackData != null, source = source
    )

    fun side(item: WorkoutListItem) =
        ConflictSide(item.date.time, item.duration, item.distance, item.hasTrack, existingId = item.id)

    fun side(pending: PendingImportSummary) = ConflictSide(
        pending.startMs, pending.durationMs, pending.distanceKm, pending.hasTrack,
        pendingId = pending.id, source = pending.source
    )
}
