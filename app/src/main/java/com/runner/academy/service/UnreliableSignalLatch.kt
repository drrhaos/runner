package com.runner.academy.service

/**
 * Hysteresis for [com.runner.academy.data.GpsStatus.UNRELIABLE], so status, banner and voice
 * do not flap while a false signal comes and goes: on at the first fix the filter calls false
 * ([GpsLocationProcessor.inFalseSignal]), off only after [minGoodFixes] good fixes in a row
 * spanning at least [minGoodMs] since the last false one.
 *
 * Times are fix times (ms). Not thread-safe: the service uses it on the main thread.
 */
class UnreliableSignalLatch(
    private val minGoodFixes: Int = DEFAULT_MIN_GOOD_FIXES,
    private val minGoodMs: Long = DEFAULT_MIN_GOOD_MS
) {
    var isUnreliable: Boolean = false
        private set

    private var goodFixes = 0
    private var lastFalseMs = 0L

    /**
     * @param inFalseSignal The filter is in a false-signal episode after this fix.
     * @param good The fix was kept (accepted or a near-duplicate).
     * @return [isUnreliable] after this fix.
     */
    fun onFix(inFalseSignal: Boolean, good: Boolean, timeMs: Long): Boolean {
        when {
            inFalseSignal -> {
                isUnreliable = true
                goodFixes = 0
                lastFalseMs = timeMs
            }
            isUnreliable && good -> {
                goodFixes++
                if (goodFixes >= minGoodFixes && timeMs - lastFalseMs >= minGoodMs) {
                    isUnreliable = false
                    goodFixes = 0
                }
            }
        }
        return isUnreliable
    }

    fun reset() {
        isUnreliable = false
        goodFixes = 0
        lastFalseMs = 0L
    }

    companion object {
        const val DEFAULT_MIN_GOOD_FIXES = 3
        const val DEFAULT_MIN_GOOD_MS = 5_000L
    }
}
