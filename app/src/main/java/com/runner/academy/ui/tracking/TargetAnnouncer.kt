package com.runner.academy.ui.tracking

import com.runner.academy.data.IntervalLive

/**
 * Whether TalkBack announces the segment target (design §5.3, architect's answer 6). The rule
 * of when is the voice's own ([IntervalLive.hintDue]: off the target, held, at most every 30 s);
 * here only: TalkBack is on, the voice did not already say it ([IntervalLive.hintVoiced]: the
 * voice or its target cues are off), and this tick's hint was not announced yet (the screen gets
 * the same session more than once a second: with every fix and every tick).
 */
class TargetAnnouncer {

    private var announced: IntervalLive? = null

    fun shouldAnnounce(live: IntervalLive?, talkBackOn: Boolean): Boolean {
        if (live == null || !live.hintDue || live.hintVoiced || !talkBackOn) return false
        if (live == announced) return false
        announced = live
        return true
    }
}
