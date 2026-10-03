package com.runner.academy.ui.tracking

import com.runner.academy.data.GpsStatus
import com.runner.academy.data.GpsStatus.DENIED
import com.runner.academy.data.GpsStatus.FOUND
import com.runner.academy.data.GpsStatus.LOST
import com.runner.academy.data.GpsStatus.MEDIUM
import com.runner.academy.data.GpsStatus.SEARCHING
import com.runner.academy.data.GpsStatus.STRONG
import com.runner.academy.data.GpsStatus.UNRELIABLE
import com.runner.academy.data.GpsStatus.WEAK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpsVoiceTransitionTest {

    private fun say(current: GpsStatus, previous: GpsStatus?) =
        GpsVoiceTransition.announcement(current, previous)

    @Test
    fun same_status_is_never_announced_again() {
        GpsStatus.entries.forEach { assertNull(it.name, say(it, it)) }
    }

    @Test
    fun entering_unreliable_is_announced() {
        listOf(SEARCHING, FOUND, STRONG, MEDIUM, WEAK, LOST, DENIED).forEach {
            assertEquals(it.name, GpsAnnouncement.UNRELIABLE, say(UNRELIABLE, it))
        }
    }

    @Test
    fun unreliable_on_restore_is_not_announced() {
        // Like LOST: after a service restore there is no previous status to compare with.
        assertNull(say(UNRELIABLE, null))
    }

    @Test
    fun good_status_after_unreliable_says_recovered() {
        listOf(FOUND, STRONG, MEDIUM, WEAK).forEach {
            assertEquals(it.name, GpsAnnouncement.RECOVERED, say(it, UNRELIABLE))
        }
    }

    @Test
    fun leaving_unreliable_for_a_bad_status_does_not_say_recovered() {
        assertNull(say(SEARCHING, UNRELIABLE))
        assertEquals(GpsAnnouncement.LOST, say(LOST, UNRELIABLE))
    }

    @Test
    fun lost_and_recovered_behave_as_before() {
        assertEquals(GpsAnnouncement.LOST, say(LOST, FOUND))
        assertEquals(GpsAnnouncement.LOST, say(LOST, SEARCHING))
        assertNull(say(LOST, null))
        assertEquals(GpsAnnouncement.RECOVERED, say(FOUND, LOST))
        assertNull(say(FOUND, SEARCHING))
        assertNull(say(FOUND, null))
    }
}
