package com.runner.academy.ui.tracking

import com.runner.academy.data.GpsStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsIndicatorStatusTest {

    @Test
    fun unreliable_session_wins_over_a_fresh_accurate_fix() {
        // The false fix looks fine by accuracy and freshness; the periodic refresh
        // must not flip the indicator back to "ready".
        assertEquals(
            GpsStatus.UNRELIABLE,
            GpsStatusUiUpdater.indicatorStatus(GpsStatus.UNRELIABLE, GpsStatus.FOUND)
        )
    }

    @Test
    fun other_session_statuses_keep_the_accuracy_based_refresh() {
        GpsStatus.entries.filter { it != GpsStatus.UNRELIABLE }.forEach {
            assertEquals(it.name, GpsStatus.LOST, GpsStatusUiUpdater.indicatorStatus(it, GpsStatus.LOST))
        }
        assertEquals(GpsStatus.FOUND, GpsStatusUiUpdater.indicatorStatus(null, GpsStatus.FOUND))
    }
}
