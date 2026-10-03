package com.runner.academy.util

import android.os.PowerManager
import com.runner.academy.util.PowerSaveCheck.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSaveCheckTest {

    private val allModes = listOf(
        PowerSaveCheck.LOCATION_MODE_NO_CHANGE,
        PowerSaveCheck.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF,
        PowerSaveCheck.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF,
        PowerSaveCheck.LOCATION_MODE_FOREGROUND_ONLY,
        PowerSaveCheck.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF,
        null,
        99
    )

    @Test
    fun saverOff_neverAffectsTracking() {
        allModes.forEach { mode ->
            assertFalse("mode $mode", PowerSaveCheck.affectsScreenOffTracking(Status(false, mode)))
        }
    }

    @Test
    fun saverOn_modesThatCutOrThrottleGpsWithScreenOff_affectTracking() {
        listOf(
            PowerSaveCheck.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF,
            PowerSaveCheck.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF,
            PowerSaveCheck.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF
        ).forEach { mode ->
            assertTrue("mode $mode", PowerSaveCheck.affectsScreenOffTracking(Status(true, mode)))
        }
    }

    @Test
    fun saverOn_noChangeAndForegroundOnly_doNotAffectTracking() {
        // A location foreground service counts as foreground
        listOf(
            PowerSaveCheck.LOCATION_MODE_NO_CHANGE,
            PowerSaveCheck.LOCATION_MODE_FOREGROUND_ONLY
        ).forEach { mode ->
            assertFalse("mode $mode", PowerSaveCheck.affectsScreenOffTracking(Status(true, mode)))
        }
    }

    @Test
    fun saverOn_unreportedOrUnknownMode_isTreatedAsAffecting() {
        // API < 28: the old battery saver turned GPS off with the screen off
        assertTrue(PowerSaveCheck.affectsScreenOffTracking(Status(true, null)))
        // A future mode: warn rather than miss it
        assertTrue(PowerSaveCheck.affectsScreenOffTracking(Status(true, 99)))
    }

    @Test
    fun locationModeName_isStableWireName() {
        assertEquals(
            listOf(
                "no_change",
                "gps_disabled_when_screen_off",
                "all_disabled_when_screen_off",
                "foreground_only",
                "throttle_requests_when_screen_off",
                "unknown",
                "unknown"
            ),
            allModes.map { PowerSaveCheck.locationModeName(it) }
        )
    }

    @Test
    fun constants_matchPlatformValues() {
        // Compile-time constants of android.jar, inlined, so no Android runtime is needed
        assertEquals(PowerManager.LOCATION_MODE_NO_CHANGE, PowerSaveCheck.LOCATION_MODE_NO_CHANGE)
        assertEquals(
            PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF,
            PowerSaveCheck.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF
        )
        assertEquals(
            PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF,
            PowerSaveCheck.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF
        )
        assertEquals(PowerManager.LOCATION_MODE_FOREGROUND_ONLY, PowerSaveCheck.LOCATION_MODE_FOREGROUND_ONLY)
        assertEquals(
            PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF,
            PowerSaveCheck.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF
        )
    }
}
