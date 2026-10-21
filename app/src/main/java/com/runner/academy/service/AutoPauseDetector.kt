package com.runner.academy.service

/** Thresholds of the auto-pause (decisions «Автопауза»: cautious ones). */
data class AutoPauseConfig(
    /** With steps: no step growth this long (and GPS agrees, see [AutoPauseDetector]) pauses. */
    val stillStepsMs: Long = 10_000L,
    /** A fix slower than this is "standing". */
    val slowSpeedMps: Float = 1f,
    /** With steps: a fix older than this no longer says the runner moves. */
    val fixFreshMs: Long = 5_000L,
    /** With steps: this many steps within [resumeStepsWindowMs] resume. */
    val resumeSteps: Int = 3,
    val resumeStepsWindowMs: Long = 3_000L,
    /** Without steps: slow fixes this long pause. */
    val gpsStillMs: Long = 10_000L,
    /** Without steps: fixes faster than this for [gpsResumeMs] resume. */
    val gpsResumeSpeedMps: Float = 1.5f,
    val gpsResumeMs: Long = 2_000L
)

/** An auto-pause transition; [atMono] is backdated to when it really happened. */
sealed class AutoPauseEvent {
    abstract val atMono: Long

    data class Pause(override val atMono: Long) : AutoPauseEvent()
    data class Resume(override val atMono: Long) : AutoPauseEvent()
}

/**
 * Decides when the runner stands still and runs again. Pure, on the monotonic clock
 * (`elapsedRealtime`; fixes by `elapsedRealtimeNanos`, so a late batch is placed right).
 *
 * The service feeds [onFix] every processed fix (near-duplicates and dropped ones included,
 * `usable = false` for an outlier or a false signal) and calls [tick] on its 1 s timer with the
 * step tracker's count (null without steps) and whether GPS is lost.
 *
 * With steps:
 *  - pause: the steps did not grow for [AutoPauseConfig.stillStepsMs] **and** GPS does not say
 *    "moving" (the last fix is slower than [AutoPauseConfig.slowSpeedMps], older than
 *    [AutoPauseConfig.fixFreshMs] or unusable). It starts at the last step growth, or at the
 *    first slow fix if GPS still showed running after it.
 *  - resume: [AutoPauseConfig.resumeSteps] steps within [AutoPauseConfig.resumeStepsWindowMs],
 *    from the first of them.
 *
 * Without steps (GPS only):
 *  - pause: usable fixes slower than [AutoPauseConfig.slowSpeedMps] spanning
 *    [AutoPauseConfig.gpsStillMs], from the first of them;
 *  - resume: usable fixes faster than [AutoPauseConfig.gpsResumeSpeedMps] for
 *    [AutoPauseConfig.gpsResumeMs], from the first of them;
 *  - GPS lost: standing cannot be told from a lost signal, so it does not pause, and an
 *    auto-pause ends now (the time counts).
 *
 * Fixes without a speed and unusable fixes are no evidence either way.
 */
class AutoPauseDetector(private val cfg: AutoPauseConfig = AutoPauseConfig(), paused: Boolean = false) {

    var paused: Boolean = paused
        private set

    // GPS evidence
    private var lastFixAt: Long? = null
    /** The last fix had a usable speed. */
    private var lastFixEvidence = false
    /** Last usable fix at or over the slow speed: the runner moved then. */
    private var movingUntil: Long? = null
    /** First usable slow fix after [movingUntil], and the last one of that streak. */
    private var slowSince: Long? = null
    private var slowUntil: Long? = null
    /** First usable fast fix (over the GPS resume speed) of the current streak, and its last. */
    private var fastSince: Long? = null
    private var fastUntil: Long? = null

    // Steps
    private var lastSteps: Int? = null
    private var lastStepGrowthAt: Long? = null
    /** Recent ticks (time, steps), oldest first, for the resume window. */
    private val stepHistory = ArrayDeque<Pair<Long, Int>>()

    /** Forgets everything (start, resume after a manual pause, restore); [paused] as given. */
    fun reset(paused: Boolean = false) {
        this.paused = paused
        lastFixAt = null
        lastFixEvidence = false
        movingUntil = null
        slowSince = null
        slowUntil = null
        fastSince = null
        fastUntil = null
        lastSteps = null
        lastStepGrowthAt = null
        stepHistory.clear()
    }

    fun onFix(atMono: Long, speedMps: Float?, usable: Boolean) {
        lastFixAt = atMono
        lastFixEvidence = usable && speedMps != null
        if (!lastFixEvidence || speedMps == null) return
        if (speedMps < cfg.slowSpeedMps) {
            if (slowSince == null) slowSince = atMono
            slowUntil = atMono
        } else {
            movingUntil = atMono
            slowSince = null
            slowUntil = null
        }
        if (speedMps > cfg.gpsResumeSpeedMps) {
            if (fastSince == null) fastSince = atMono
            fastUntil = atMono
        } else {
            fastSince = null
            fastUntil = null
        }
    }

    fun tick(nowMono: Long, steps: Int?, gpsLost: Boolean): AutoPauseEvent? {
        if (gpsLost) {
            slowSince = null
            slowUntil = null
        }
        if (steps != null) recordSteps(nowMono, steps) else forgetSteps()

        val event = when {
            paused && steps != null -> resumeBySteps(steps)
            paused -> if (gpsLost) AutoPauseEvent.Resume(nowMono) else resumeByGps()
            steps != null -> pauseBySteps(nowMono)
            gpsLost -> null
            else -> pauseByGps()
        }
        when (event) {
            is AutoPauseEvent.Pause -> {
                paused = true
                fastSince = null
                fastUntil = null
            }
            is AutoPauseEvent.Resume -> paused = false
            null -> Unit
        }
        return event
    }

    private fun recordSteps(now: Long, steps: Int) {
        val previous = lastSteps
        if (previous == null || steps < previous) {
            // First count (or a restarted counter): the baseline, not a growth
            stepHistory.clear()
            if (lastStepGrowthAt == null) lastStepGrowthAt = now
        } else if (steps > previous) {
            lastStepGrowthAt = now
        }
        lastSteps = steps
        stepHistory.addLast(now to steps)
        // Keep the newest tick at or before the window start as the base
        while (stepHistory.size > 1 && stepHistory[1].first <= now - cfg.resumeStepsWindowMs) {
            stepHistory.removeFirst()
        }
    }

    private fun forgetSteps() {
        lastSteps = null
        lastStepGrowthAt = null
        stepHistory.clear()
    }

    private fun pauseBySteps(now: Long): AutoPauseEvent? {
        val lastGrowth = lastStepGrowthAt ?: return null
        if (now - lastGrowth < cfg.stillStepsMs) return null
        val gpsStillSince = gpsStillSince(now) ?: return null
        return AutoPauseEvent.Pause(maxOf(lastGrowth, gpsStillSince))
    }

    /** Since when GPS does not say "moving"; null while it does. */
    private fun gpsStillSince(now: Long): Long? {
        val fixAt = lastFixAt ?: return Long.MIN_VALUE
        val fresh = now - fixAt <= cfg.fixFreshMs
        val slow = slowSince
        return when {
            fresh && lastFixEvidence -> slow
            else -> slow ?: movingUntil ?: Long.MIN_VALUE
        }
    }

    private fun resumeBySteps(steps: Int): AutoPauseEvent? {
        val base = stepHistory.firstOrNull() ?: return null
        if (steps - base.second < cfg.resumeSteps) return null
        val firstStep = stepHistory.first { it.second > base.second }
        return AutoPauseEvent.Resume(firstStep.first)
    }

    /**
     * The slow fixes themselves must span [AutoPauseConfig.gpsStillMs]: one slow fix followed
     * by silence is no evidence of standing. A lost GPS breaks the streak, so standing with
     * fixes rarer than the lost timeout never enters (no "Auto-pause → Resuming" swing).
     */
    private fun pauseByGps(): AutoPauseEvent? {
        val since = slowSince ?: return null
        val until = slowUntil ?: return null
        return if (until - since >= cfg.gpsStillMs) AutoPauseEvent.Pause(since) else null
    }

    private fun resumeByGps(): AutoPauseEvent? {
        val since = fastSince ?: return null
        val until = fastUntil ?: return null
        return if (until - since >= cfg.gpsResumeMs) AutoPauseEvent.Resume(since) else null
    }
}
