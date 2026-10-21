package com.runner.academy.ui.tracking

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.R
import com.runner.academy.data.SegmentTarget
import com.runner.academy.ui.tracking.TargetIndicatorLook.Tone
import com.runner.academy.voice.TargetState
import com.runner.academy.voice.TargetVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The target indicator's words, glyph and tone (design §5.2), English and Russian. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TargetIndicatorLookTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val pace = SegmentTarget.Pace(4.5f)
    private val zone = SegmentTarget.HrZone(4)

    private fun look(state: TargetState?, target: SegmentTarget = pace, zonesOn: Boolean = true) =
        TargetIndicatorLook.of(state, target, zonesOn, context)

    @Test
    fun `no target or no state - no indicator`() {
        assertNull(look(TargetState.NONE))
        assertNull(look(null))
        assertNull(look(TargetState(TargetVerdict.IN, value = 4.5f), target = SegmentTarget.None))
    }

    @Test
    fun `pace verdicts - word, value, glyph by what to do, tone`() {
        assertEquals(
            TargetIndicatorLook("On target · 4:31", "On target, 4 minutes 31 seconds per kilometer", Tone.IN, R.drawable.ic_target_in, outlined = false, stateDescription = "On target"),
            look(TargetState(TargetVerdict.IN, value = 4.5f + 1f / 60f))
        )
        val slow = look(TargetState(TargetVerdict.TOO_SLOW, value = 4.75f))!!
        assertEquals("Speed up · 4:45", slow.text)
        assertEquals(Tone.OFF, slow.tone)
        assertEquals(R.drawable.ic_target_up, slow.icon)
        assertEquals(R.drawable.ic_target_down, look(TargetState(TargetVerdict.TOO_FAST, value = 4.25f))!!.icon)
        assertEquals("Slow down · 4:15", look(TargetState(TargetVerdict.TOO_FAST, value = 4.25f))!!.text)
    }

    @Test
    fun `a steps pace - the approximate sign, an outlined circle, said as approximate`() {
        val approx = look(TargetState(TargetVerdict.TOO_SLOW, approximate = true, value = 4.75f))!!
        assertEquals("≈ Speed up · 4:45", approx.text)
        assertEquals("Speed up, 4 minutes 45 seconds per kilometer, approximate from steps", approx.description)
        assertEquals(true, approx.outlined)
        assertEquals("Speed up, approximate from steps", approx.stateDescription)
    }

    @Test
    fun `zone verdicts - the heart rate, up to raise it, down to ease`() {
        val above = look(TargetState(TargetVerdict.ABOVE_ZONE, value = 172f), target = zone)!!
        assertEquals("Above zone · 172", above.text)
        assertEquals("Above zone, 172 beats per minute", above.description)
        assertEquals(R.drawable.ic_target_down, above.icon)
        assertEquals(R.drawable.ic_target_up, look(TargetState(TargetVerdict.BELOW_ZONE, value = 120f), target = zone)!!.icon)
    }

    @Test
    fun `neutral states - by the kind of target`() {
        val wait = look(TargetState(TargetVerdict.PENDING))!!
        assertEquals(TargetIndicatorLook("Measuring pace…", "Measuring pace…", Tone.NEUTRAL, R.drawable.ic_target_wait, false, "Measuring pace…"), wait)
        assertEquals("Heart rate catching up…", look(TargetState(TargetVerdict.PENDING), target = zone)!!.text)
        assertEquals("No pace", look(TargetState(TargetVerdict.NO_DATA))!!.text)
        assertEquals("No heart rate", look(TargetState(TargetVerdict.NO_DATA), target = zone)!!.text)
        assertEquals("Zones are off", look(TargetState(TargetVerdict.NO_DATA), target = zone, zonesOn = false)!!.text)
    }

    @Test
    @Config(qualifiers = "ru")
    fun `in russian`() {
        assertEquals("Быстрее · 4:45", look(TargetState(TargetVerdict.TOO_SLOW, value = 4.75f))!!.text)
        assertEquals("≈ Медленнее · 4:15", look(TargetState(TargetVerdict.TOO_FAST, approximate = true, value = 4.25f))!!.text)
        assertEquals("В цели · 160", look(TargetState(TargetVerdict.IN, value = 160f), target = zone)!!.text)
        assertEquals("Ниже зоны, 120 ударов в минуту", look(TargetState(TargetVerdict.BELOW_ZONE, value = 120f), target = zone)!!.description)
    }
}
