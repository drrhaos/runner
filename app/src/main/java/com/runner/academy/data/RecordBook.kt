package com.runner.academy.data

import java.util.Date

/**
 * Records and their history, derived when read from the best efforts of the workouts that
 * count ([BestEffortDao.observeEligibleEfforts]). Pure: deleting, excluding or re-dating a
 * workout only changes the rows it is built from.
 */
class RecordBook private constructor(
    /** Per distance: the efforts that improved it, by workout date ascending. */
    private val chains: Map<RecordDistance, List<EffortRow>>
) {

    /** The record on [distance], or null when no workout covered it. */
    fun current(distance: RecordDistance): EffortRow? = chains[distance]?.lastOrNull()

    /**
     * Every effort that was the record on [distance] when it was run: by workout date, each
     * faster than the one before. An equal time is no improvement: the earlier date keeps it.
     */
    fun history(distance: RecordDistance): List<EffortRow> = chains[distance].orEmpty()

    /**
     * What workout [workoutId] means for the records, one entry per distance it set a record
     * on, shortest first; null when it set none. [justSaved]: opened right after saving, when a
     * record still standing is a congratulation ([RecordStatus.New], or [RecordStatus.First]
     * on a distance never run before); otherwise it is [RecordStatus.Current].
     */
    fun cardFor(workoutId: Long, justSaved: Boolean): RecordCard? {
        val entries = RecordDistance.entries.mapNotNull { distance ->
            val chain = history(distance)
            val index = chain.indexOfFirst { it.effort.workoutId == workoutId }
            if (index < 0) return@mapNotNull null
            val row = chain[index]
            val status = when {
                index < chain.lastIndex -> RecordStatus.Former(until = chain[index + 1].date)
                !justSaved -> RecordStatus.Current
                index == 0 -> RecordStatus.First
                else -> RecordStatus.New(improvementMs = chain[index - 1].effort.elapsedMs - row.effort.elapsedMs)
            }
            RecordCard.Entry(distance, row.effort, status)
        }
        return entries.takeIf { it.isNotEmpty() }?.let(::RecordCard)
    }

    /**
     * Distances whose current record belongs to one of [workoutIds]: the N of "Records updated:
     * N" for a batch (an import, a background pass), whatever else was saved meanwhile.
     */
    fun distancesHeldBy(workoutIds: Set<Long>): Int =
        RecordDistance.entries.count { current(it)?.effort?.workoutId in workoutIds }

    companion object {
        fun from(rows: List<EffortRow>): RecordBook {
            val chains = rows
                .groupBy { RecordDistance.ofMeters(it.effort.distanceM) }
                .mapNotNull { (distance, efforts) -> distance?.let { it to chainOf(efforts) } }
                .toMap()
            return RecordBook(chains)
        }

        private fun chainOf(efforts: List<EffortRow>): List<EffortRow> {
            val chain = mutableListOf<EffortRow>()
            for (row in efforts.sortedWith(compareBy({ it.date }, { it.effort.workoutId }))) {
                val best = chain.lastOrNull()
                if (best == null || row.effort.elapsedMs < best.effort.elapsedMs) chain += row
            }
            return chain
        }
    }
}

/** The records of one workout, for the card on its details screen. */
data class RecordCard(val entries: List<Entry>) {
    data class Entry(val distance: RecordDistance, val effort: BestEffort, val status: RecordStatus)
}

sealed interface RecordStatus {
    /** Just saved and faster than the record before it by [improvementMs]: a congratulation. */
    data class New(val improvementMs: Long) : RecordStatus

    /** Just saved and the first result on the distance: "First result", not a congratulation. */
    data object First : RecordStatus

    /** Still the record. */
    data object Current : RecordStatus

    /** Was the record until a faster run on [until]. */
    data class Former(val until: Date) : RecordStatus
}
