package com.runner.academy.util

/**
 * When the screens show moving time and elapsed time apart (design §3): next to the pace the
 * time is the moving one; the elapsed time comes second, only when it differs, so an old or
 * auto-pause-free workout shows one time, not two equal ones.
 */
object MovingTimeDisplay {

    /** Details: "Moving time" + an "Elapsed time" tile from this difference on. */
    const val DETAIL_MIN_DIFF_MS = 5_000L

    /** Statistics: the "Moving time" row above this difference of the sums. */
    const val STATISTICS_MIN_DIFF_MS = 60_000L

    fun detailShowsElapsed(durationMs: Long, movingMs: Long): Boolean =
        durationMs - movingMs >= DETAIL_MIN_DIFF_MS

    fun statisticsShowsMoving(totalDurationMs: Long, totalMovingMs: Long): Boolean =
        totalDurationMs - totalMovingMs > STATISTICS_MIN_DIFF_MS
}
