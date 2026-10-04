package com.runner.academy.service

import com.runner.academy.data.GpsStatus

/**
 * The periodic watchdog's GPS status decision during an active workout (permission checked by
 * the caller). Pure, so the rules are unit-tested.
 *
 * - [GpsStatus.UNRELIABLE] stays while fixes (false ones included) keep arriving; it turns
 *   into [GpsStatus.LOST] on real silence, or [GpsStatus.SEARCHING] if no good fix was ever
 *   accepted (false start).
 * - Otherwise release-1 rules: SEARCHING until the first good fix, LOST after three timeouts
 *   without a good fix, FOUND again after a recent good fix.
 */
object GpsStatusWatchdog {

    /**
     * @param lastGoodFixMs Wall time of the last accepted / near-duplicate fix, 0 if none.
     * @param lastAnyFixMs Wall time of the last fix fed to the filter, kept or dropped, 0 if none.
     * @return The new status, or null to keep [current].
     */
    fun resolve(
        current: GpsStatus,
        nowMs: Long,
        lastGoodFixMs: Long,
        lastAnyFixMs: Long,
        lostTimeoutMs: Long
    ): GpsStatus? {
        val lostAfterMs = lostTimeoutMs * LOST_AFTER_TIMEOUTS
        if (current == GpsStatus.UNRELIABLE) {
            if (lastAnyFixMs != 0L && nowMs - lastAnyFixMs <= lostAfterMs) return null
            return if (lastGoodFixMs == 0L) GpsStatus.SEARCHING else GpsStatus.LOST
        }
        if (lastGoodFixMs == 0L) {
            return if (current != GpsStatus.SEARCHING) GpsStatus.SEARCHING else null
        }
        val elapsed = nowMs - lastGoodFixMs
        return when {
            elapsed > lostAfterMs -> if (current != GpsStatus.LOST) GpsStatus.LOST else null
            (current == GpsStatus.LOST || current == GpsStatus.SEARCHING) && elapsed <= lostTimeoutMs ->
                GpsStatus.FOUND
            else -> null
        }
    }

    private const val LOST_AFTER_TIMEOUTS = 3
}
