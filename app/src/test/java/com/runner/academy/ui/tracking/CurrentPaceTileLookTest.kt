package com.runner.academy.ui.tracking

import com.runner.academy.data.LivePace
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CurrentPaceTileLookTest {

    private val context get() = RuntimeEnvironment.getApplication()

    /** 4:52.5 per km: the seconds are cut, not rounded. */
    private val pace = 292.5f / 60f

    @Test
    fun `a GPS pace is the plain digits, said as minutes and seconds per kilometre`() {
        val look = CurrentPaceTileLook.of(LivePace(pace, approximate = false), context)

        assertEquals("4:52", look.text)
        assertEquals("Current pace: 4 minutes 52 seconds per kilometer", look.contentDescription)
    }

    @Test
    fun `a steps pace has the approximately-equal sign and says it is from steps`() {
        val look = CurrentPaceTileLook.of(LivePace(pace, approximate = true), context)

        assertEquals("≈ 4:52", look.text)
        assertEquals("Current pace, approximate from steps: 4 minutes 52 seconds per kilometer", look.contentDescription)
    }

    @Test
    fun `no pace is the dashes and says there is no data`() {
        val look = CurrentPaceTileLook.of(null, context)

        assertEquals("--:--", look.text)
        assertEquals("Current pace: no data", look.contentDescription)
    }

    @Test
    fun `in miles the same pace is per mile`() {
        val look = CurrentPaceTileLook.of(LivePace(5f, approximate = false), context, metric = false)

        // 5:00 per km is 8:02.8 per mile
        assertEquals("8:02", look.text)
        assertEquals("Current pace: 8 minutes 2 seconds per mile", look.contentDescription)
    }

    @Test
    @Config(qualifiers = "ru")
    fun `in Russian the description is Russian, the sign the same`() {
        val look = CurrentPaceTileLook.of(LivePace(pace, approximate = true), context)

        assertEquals("≈ 4:52", look.text)
        assertEquals("Текущий темп приблизительно, по шагам: 4 минуты 52 секунды на километр", look.contentDescription)
    }
}
