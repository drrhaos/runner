package com.runner.academy.util

import com.runner.academy.util.GpsDiagnostics.DiagFix
import com.runner.academy.util.GpsDiagnostics.DiagRecord
import com.runner.academy.util.GpsDiagnostics.DiagSatellite
import com.runner.academy.util.GpsDiagnostics.FixResult
import com.runner.academy.util.GpsDiagnostics.GnssSummary
import com.runner.academy.util.GpsDiagnostics.SystemStats
import com.runner.academy.util.PowerSaveCheck.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsDiagnosticsFormatTest {

    private val fix = DiagFix(
        elapsedMs = 12_345L,
        timeMs = 1_760_000_000_000L,
        latitude = 55.7558,
        longitude = 37.6173,
        accuracy = 4.5f,
        speed = 3.1f,
        bearing = 90f,
        altitude = 152.0,
        provider = "gps",
        isMock = false
    )

    @Test
    fun gnssSummary_groupsSatellitesBySystem() {
        val summary = GnssSummary.of(
            listOf(
                DiagSatellite(GpsDiagnostics.CONSTELLATION_GPS, cn0DbHz = 30f, usedInFix = true),
                DiagSatellite(GpsDiagnostics.CONSTELLATION_GPS, cn0DbHz = 40f, usedInFix = false),
                DiagSatellite(GpsDiagnostics.CONSTELLATION_GLONASS, cn0DbHz = 25f, usedInFix = true),
                DiagSatellite(99, cn0DbHz = 10f, usedInFix = false)
            )
        )
        assertEquals(SystemStats(visible = 2, used = 1, meanCn0 = 35f, maxCn0 = 40f), summary.systems["GPS"])
        assertEquals(SystemStats(visible = 1, used = 1, meanCn0 = 25f, maxCn0 = 25f), summary.systems["GLONASS"])
        assertEquals(1, summary.systems["UNKNOWN"]!!.visible)
        assertEquals(4, summary.visible)
        assertEquals(2, summary.used)
    }

    @Test
    fun gnssSummary_namesEveryPlatformConstellation() {
        // Values of android.location.GnssStatus.CONSTELLATION_*
        val names = (1..7).map { GpsDiagnostics.constellationName(it) }
        assertEquals(listOf("GPS", "SBAS", "GLONASS", "QZSS", "BEIDOU", "GALILEO", "IRNSS"), names)
    }

    @Test
    fun lines_roundTripThroughParse() {
        val summary = GnssSummary.of(
            listOf(DiagSatellite(GpsDiagnostics.CONSTELLATION_GALILEO, 33.5f, true))
        )
        val lines = listOf(
            GpsDiagnostics.headerLine(startTimeMs = 1_760_000_000_000L, appVersion = "0.1.5", sdkInt = 35),
            GpsDiagnostics.fixLine(fix, FixResult.ACCEPTED),
            GpsDiagnostics.fixLine(fix.copy(accuracy = null, speed = null, bearing = null, altitude = null), FixResult.REJECTED),
            GpsDiagnostics.gnssLine(elapsedMs = 12_400L, summary = summary),
            GpsDiagnostics.eventLine(elapsedMs = 13_000L, event = GpsDiagnostics.Event.PAUSE)
        )

        val records = GpsDiagnostics.parse(lines)

        assertEquals(
            listOf(
                DiagRecord.Header(GpsDiagnostics.FORMAT_VERSION, 1_760_000_000_000L, "0.1.5", 35),
                DiagRecord.Fix(fix, FixResult.ACCEPTED),
                DiagRecord.Fix(fix.copy(accuracy = null, speed = null, bearing = null, altitude = null), FixResult.REJECTED),
                DiagRecord.Gnss(12_400L, summary),
                DiagRecord.Event(13_000L, "pause")
            ),
            records
        )
    }

    @Test
    fun powerLine_roundTripsThroughParse() {
        val lines = listOf(
            GpsDiagnostics.powerLine(
                elapsedMs = 14_000L,
                status = Status(powerSaveMode = true, locationMode = PowerSaveCheck.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF)
            ),
            GpsDiagnostics.powerLine(elapsedMs = 15_000L, status = Status(powerSaveMode = false, locationMode = null))
        )

        assertEquals(
            listOf(
                DiagRecord.Power(14_000L, powerSaveMode = true, locationMode = "gps_disabled_when_screen_off"),
                DiagRecord.Power(15_000L, powerSaveMode = false, locationMode = "unknown")
            ),
            GpsDiagnostics.parse(lines)
        )
    }

    @Test
    fun powerLine_wireFormat() {
        val line = GpsDiagnostics.powerLine(
            elapsedMs = 14_000L,
            status = Status(powerSaveMode = true, locationMode = PowerSaveCheck.LOCATION_MODE_FOREGROUND_ONLY)
        )
        assertEquals("""{"type":"power","t":14000,"saver":true,"location":"foreground_only"}""", line)
    }

    @Test
    fun each_record_is_a_single_json_line() {
        val line = GpsDiagnostics.fixLine(fix, FixResult.NEAR_DUPLICATE)
        assertTrue(!line.contains('\n'))
        assertTrue(line.startsWith("{") && line.endsWith("}"))
    }

    @Test
    fun parse_skipsBlankAndCorruptLines() {
        // A crash can leave a half-written last line; the rest of the file must stay readable
        val good = GpsDiagnostics.eventLine(1L, GpsDiagnostics.Event.START)
        val records = GpsDiagnostics.parse(listOf(good, "", "{\"type\":\"fix\",\"lat\":5", "not json"))
        assertEquals(listOf(DiagRecord.Event(1L, "start")), records)
    }
}
