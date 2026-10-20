package com.runner.academy.ui.workout

/** The secondary tiles of the details, under distance, time and pace, in the order they are read. */
enum class DetailTile { AVG_SPEED, HEART_RATE, CADENCE, CALORIES, ELAPSED, ELEVATION_GAIN, ELEVATION_LOSS }

/**
 * How the shown secondary tiles fill rows of [COLUMNS], the columns of the first row: in
 * [DetailTile] order with the hidden ones skipped, so no row has a hole in the middle. Gain and
 * loss are never split: they end the last row when both fit in it, else take a row of their own.
 */
object DetailTileRows {

    const val COLUMNS = 3

    private val elevation = setOf(DetailTile.ELEVATION_GAIN, DetailTile.ELEVATION_LOSS)

    fun of(shown: Set<DetailTile>): List<List<DetailTile>> {
        val (pair, rest) = DetailTile.entries.filter { it in shown }.partition { it in elevation }
        val rows = rest.chunked(COLUMNS)
        if (pair.isEmpty()) return rows
        val last = rows.lastOrNull()
        return if (last != null && last.size + pair.size <= COLUMNS) rows.dropLast(1) + listOf(last + pair) else rows + listOf(pair)
    }
}
