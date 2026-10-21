package com.runner.academy.ui.workout

/** The secondary tiles of the details, under distance, time and pace, in the order they are read. */
enum class DetailTile { AVG_SPEED, HEART_RATE, CADENCE, CALORIES, ELAPSED, ELEVATION_GAIN, ELEVATION_LOSS }

/**
 * How the shown secondary tiles fill rows of at most [COLUMNS], the columns of the first row: in
 * [DetailTile] order with the hidden ones skipped, in as few rows as fit, balanced — four tiles
 * are two and two, not three and a lone one (a short row is centred on screen). Gain and loss
 * come last, so they are never split: balanced, the last row always holds at least two.
 */
object DetailTileRows {

    const val COLUMNS = 3

    fun of(shown: Set<DetailTile>): List<List<DetailTile>> = balanced(DetailTile.entries.filter { it in shown })

    /** [tiles] in the fewest rows of at most [COLUMNS], the longer rows first: 4 is 2+2, 7 is 3+2+2. */
    private fun balanced(tiles: List<DetailTile>): List<List<DetailTile>> {
        if (tiles.isEmpty()) return emptyList()
        val count = (tiles.size + COLUMNS - 1) / COLUMNS
        val base = tiles.size / count
        val longer = tiles.size % count
        var from = 0
        return List(count) { row ->
            val size = base + if (row < longer) 1 else 0
            tiles.subList(from, from + size).also { from += size }
        }
    }
}
