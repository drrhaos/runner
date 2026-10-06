package com.runner.academy.service

import com.runner.academy.service.AutoPauseEvent.Pause
import com.runner.academy.service.AutoPauseEvent.Resume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPauseDetectorTest {

    private fun at(sec: Int) = sec * 1_000L
    private fun at(sec: Double) = (sec * 1_000).toLong()

    private val detector = AutoPauseDetector(AutoPauseConfig())

    /** One second of the service: a fix (if any) then the timer tick; returns the tick's event. */
    private fun second(
        sec: Int,
        steps: Int?,
        speed: Float? = null,
        usable: Boolean = true,
        gpsLost: Boolean = false
    ): AutoPauseEvent? {
        if (speed != null) detector.onFix(at(sec - 0.5), speed, usable)
        return detector.tick(at(sec), steps, gpsLost)
    }

    /** Running with steps (3 steps/s) and GPS at 3 m/s for seconds [from, to]; asserts no event. */
    private fun runWithSteps(from: Int, to: Int, stepsAtFrom: Int = from * 3): Int {
        var steps = stepsAtFrom
        for (sec in from..to) {
            assertNull("no event at $sec", second(sec, steps, speed = 3f))
            if (sec < to) steps += 3
        }
        return steps
    }

    private fun events(range: IntProgression, block: (Int) -> AutoPauseEvent?): List<AutoPauseEvent> =
        range.mapNotNull(block)

    // Case 1

    @Test
    fun `10 s without steps at 0,3 m per s pauses at the last step`() {
        var steps = runWithSteps(0, 57)
        // Slowing down to the light: GPS is slow before the last steps
        for (sec in 58..60) {
            steps += 3
            assertNull(second(sec, steps, speed = 0.3f))
        }

        val got = events(61..80) { second(it, steps, speed = 0.3f) }

        assertEquals(listOf(Pause(at(60))), got)
        assertTrue(detector.paused)
    }

    @Test
    fun `no pause while the steps stop but GPS still shows running`() {
        val steps = runWithSteps(0, 60)

        assertTrue(events(61..90) { second(it, steps, speed = 2.5f) }.isEmpty())
    }

    @Test
    fun `steps stop and GPS goes silent - the stale fix counts as standing`() {
        val steps = runWithSteps(0, 60)

        val got = events(61..80) { second(it, steps, speed = null) }

        assertEquals(listOf(Pause(at(60))), got)
    }

    @Test
    fun `steps stop and the fixes are unusable - pause`() {
        val steps = runWithSteps(0, 60)

        val got = events(61..80) { second(it, steps, speed = 3f, usable = false) }

        assertEquals(listOf(Pause(at(60))), got)
    }

    @Test
    fun `GPS slows after the steps stopped - the pause starts at the first slow fix`() {
        val steps = runWithSteps(0, 60)
        events(61..70) { second(it, steps, speed = 3f) }

        val got = events(71..90) { second(it, steps, speed = 0.2f) }

        assertEquals(listOf(Pause(at(70.5))), got)
    }

    // Case 2

    @Test
    fun `2 steps in 3 s do not resume, 3 steps resume at the first of them`() {
        var steps = runWithSteps(0, 60)
        events(61..100) { second(it, steps, speed = 0f) }
        assertTrue(detector.paused)

        // Shuffling at the light: 2 steps
        assertNull(second(101, ++steps, speed = 0f))
        assertNull(second(102, ++steps, speed = 0f))
        assertNull(second(103, steps, speed = 0f))
        assertNull(second(104, steps, speed = 0f))
        assertNull(second(105, steps, speed = 0f))
        assertTrue(detector.paused)

        // Off again: steps at 106, 107, 108
        assertNull(second(106, ++steps, speed = 0.5f))
        assertNull(second(107, ++steps, speed = 1f))
        assertEquals(Resume(at(106)), second(108, ++steps, speed = 2f))
        assertFalse(detector.paused)
    }

    @Test
    fun `a fast start resumes on the tick after the first steps`() {
        var steps = runWithSteps(0, 60)
        events(61..100) { second(it, steps, speed = 0f) }

        steps += 3
        assertEquals(Resume(at(101)), second(101, steps, speed = 0f))
    }

    @Test
    fun `with steps GPS speed alone does not resume`() {
        val steps = runWithSteps(0, 60)
        events(61..100) { second(it, steps, speed = 0f) }

        assertTrue(events(101..110) { second(it, steps, speed = 3f) }.isEmpty())
    }

    @Test
    fun `a pause after a resume needs 10 more seconds without steps`() {
        var steps = runWithSteps(0, 60)
        events(61..100) { second(it, steps, speed = 0f) }
        steps += 3
        assertEquals(Resume(at(101)), second(101, steps, speed = 0.5f))

        assertTrue(events(102..110) { second(it, steps, speed = 0f) }.isEmpty())
        assertEquals(Pause(at(101)), second(111, steps, speed = 0f))
    }

    // Without steps

    @Test
    fun `without steps under 1 m per s for 10 s pauses at the first slow fix`() {
        events(0..60) { second(it, null, speed = 3f) }

        val got = events(61..80) { second(it, null, speed = 0.4f) }

        assertEquals(listOf(Pause(at(60.5))), got)
    }

    @Test
    fun `without steps the slow fixes themselves must cover 10 s`() {
        events(0..60) { second(it, null, speed = 3f) }

        // One slow fix, then nothing (not LOST yet): no evidence of 10 s of standing
        assertNull(second(61, null, speed = 0.2f))
        assertTrue(events(62..75) { second(it, null) }.isEmpty())
        // The next slow fix 10 s after the first one completes the evidence
        assertEquals(Pause(at(60.5)), second(76, null, speed = 0.2f))
    }

    @Test
    fun `without steps a 1,2 m per s walk never pauses`() {
        assertTrue(events(0..120) { second(it, null, speed = 1.2f) }.isEmpty())
    }

    @Test
    fun `without steps over 1,5 m per s for 2 s resumes at the first fast fix`() {
        events(0..60) { second(it, null, speed = 3f) }
        events(61..80) { second(it, null, speed = 0f) }

        assertNull(second(81, null, speed = 1.6f))
        assertNull(second(82, null, speed = 1.6f))
        assertEquals(Resume(at(80.5)), second(83, null, speed = 1.6f))
    }

    @Test
    fun `without steps a single fast fix does not resume`() {
        events(0..60) { second(it, null, speed = 3f) }
        events(61..80) { second(it, null, speed = 0f) }

        assertNull(second(81, null, speed = 2f))
        assertNull(second(82, null, speed = 0.3f))
        assertNull(second(83, null, speed = 2f))
        assertNull(second(84, null, speed = 1.2f))
        assertTrue(detector.paused)
    }

    @Test
    fun `without steps unusable fixes neither pause nor resume`() {
        events(0..60) { second(it, null, speed = 3f) }

        assertTrue(events(61..90) { second(it, null, speed = 0f, usable = false) }.isEmpty())
    }

    // Case 3

    @Test
    fun `without steps and LOST it does not pause`() {
        events(0..60) { second(it, null, speed = 3f) }
        events(61..65) { second(it, null, speed = 0.2f) }

        assertTrue(events(66..120) { second(it, null, gpsLost = true) }.isEmpty())
        assertFalse(detector.paused)
    }

    @Test
    fun `without steps LOST during an auto-pause resumes now`() {
        events(0..60) { second(it, null, speed = 3f) }
        events(61..80) { second(it, null, speed = 0f) }
        assertTrue(detector.paused)

        assertEquals(Resume(at(90)), second(90, null, gpsLost = true))
    }

    @Test
    fun `after LOST the slow fixes before it do not backdate a pause`() {
        events(0..60) { second(it, null, speed = 3f) }
        events(61..65) { second(it, null, speed = 0.2f) }
        events(66..100) { second(it, null, gpsLost = true) }

        val got = events(101..120) { second(it, null, speed = 0.2f) }

        assertEquals(listOf(Pause(at(100.5))), got)
    }

    @Test
    fun `with steps LOST does not stop a pause by steps`() {
        val steps = runWithSteps(0, 60)

        val got = events(61..80) { second(it, steps, gpsLost = true) }

        assertEquals(listOf(Pause(at(60))), got)
    }

    // Restore (case 5, the detector's side)

    @Test
    fun `restored in an auto-pause it waits for fresh steps`() {
        val restored = AutoPauseDetector(AutoPauseConfig(), paused = true)

        assertNull(restored.tick(at(200), 900, gpsLost = false))
        assertNull(restored.tick(at(201), 900, gpsLost = false))
        assertEquals(Resume(at(202)), restored.tick(at(202), 903, gpsLost = false))
    }

    @Test
    fun `restored in an auto-pause without steps waits for speed`() {
        val restored = AutoPauseDetector(AutoPauseConfig(), paused = true)

        assertNull(restored.tick(at(200), null, gpsLost = false))
        restored.onFix(at(201), 2f, usable = true)
        restored.onFix(at(202), 2f, usable = true)
        assertNull(restored.tick(at(202), null, gpsLost = false))
        restored.onFix(at(203), 2f, usable = true)
        assertEquals(Resume(at(201)), restored.tick(at(203), null, gpsLost = false))
    }

    @Test
    fun `reset starts a fresh 10 s window`() {
        val steps = runWithSteps(0, 60)
        detector.reset()

        assertTrue(events(70..79) { second(it, steps, speed = 0f) }.isEmpty())
        assertEquals(Pause(at(70)), second(80, steps, speed = 0f))
    }
}
