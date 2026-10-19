package com.runner.academy.util.fit

import android.util.Log
import com.runner.academy.data.Lap
import com.runner.academy.data.LocationSource
import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.Workout
import com.runner.academy.util.ActivitySport
import com.runner.academy.util.ActivityTotals
import com.runner.academy.util.HeartRates
import com.runner.academy.util.ParsedActivity
import com.runner.academy.util.TrackFileImport
import com.runner.academy.util.TrackPauses
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Imports a single workout from a FIT activity file ([FitDecoder] messages → [ParsedActivity]).
 *
 * The distance and time are what the watch counted (`session`; without one, the last record
 * distance and the record times minus the pauses). Points with a position make the track; a
 * file without them (a treadmill, indoor) has none and keeps the session's heart rate and
 * cadence ([TrackFileImport.workoutOf]). Pauses come from the timer events only. A damaged file
 * (bad CRC, cut short, no session) imports what was read, when it is an activity with a record
 * or a session.
 */
object FitImporter {

    private const val TAG = "FitImporter"

    // Global message numbers
    private const val FILE_ID = 0
    private const val SPORT = 12
    private const val SESSION = 18
    private const val LAP = 19
    private const val RECORD = 20
    private const val EVENT = 21
    private const val DEVICE_INFO = 23
    private const val ACTIVITY = 34
    private val READ = setOf(FILE_ID, SPORT, SESSION, LAP, RECORD, EVENT, DEVICE_INFO, ACTIVITY)

    private const val FILE_TYPE_ACTIVITY = 4
    private const val ACTIVITY_AUTO_MULTISPORT = 1

    // Timer events (event 0) and their types
    private const val EVENT_TIMER = 0
    private const val TIMER_START = 0
    private const val TIMER_STOP = 1
    private const val TIMER_STOP_ALL = 4
    private const val TRIGGER_AUTO = 1

    /** Seconds from the Unix epoch to the FIT one, 1989-12-31T00:00:00Z. */
    private const val FIT_EPOCH_S = 631_065_600L

    /** Times below this count seconds since the device was switched on: no date. */
    private const val MIN_DATE_TIME = 0x10000000L

    private const val SEMICIRCLE_DEG = 180.0 / 2_147_483_648.0

    /** The names of makers, for a device that names no product. */
    private val MAKERS = mapOf(
        1 to "Maker A", 13 to "Maker A", 15 to "Maker A", 89 to "Maker B", 23 to "Maker C",
        32 to "Maker D", 123 to "Maker E", 129 to "Maker F", 294 to "Maker F", 260 to "Maker G",
        265 to "Maker H", 289 to "Maker I", 310 to "Maker J", 339 to "Maker K", 348 to "Maker L"
    )

    fun parseFit(input: InputStream, fileName: String? = null): Workout =
        TrackFileImport.workoutOf(parse(input, fileName), fileName)

    /**
     * Throws IllegalArgumentException when [input] is no FIT activity (another FIT file, or one
     * with neither records nor a session).
     */
    fun parse(input: InputStream, fileName: String? = null): ParsedActivity {
        val decoded = FitDecoder.decode(input, READ)
        val byType = decoded.messages.groupBy { it.global }
        fun of(global: Int) = byType[global].orEmpty()
        val named = fileName?.let { " ($it)" } ?: ""

        val fileIds = of(FILE_ID)
        if (fileIds.none { it.int(0) == FILE_TYPE_ACTIVITY }) {
            throw IllegalArgumentException("Not a FIT activity$named")
        }
        val records = of(RECORD)
        val sessions = of(SESSION)
        if (records.isEmpty() && sessions.isEmpty()) {
            throw IllegalArgumentException("FIT activity without data$named")
        }
        if (!decoded.intact) Log.w(TAG, "Damaged FIT file$named: importing what was read")

        var timeMissing = false
        val points = ArrayList<TrackPoint>()
        for (record in records) {
            val position = positionOf(record) ?: continue
            val time = timeOf(record.long(FitDecoder.TIMESTAMP))
                ?: points.lastOrNull()?.timestamp?.plus(1000L)?.also { timeMissing = true }
                ?: continue
            points += TrackPoint(
                latitude = position.first,
                longitude = position.second,
                timestamp = time,
                accuracy = record.long(31)?.toFloat(),
                speed = (record.double(73) ?: record.double(6))?.let { (it / 1000).toFloat() },
                altitude = (record.double(78) ?: record.double(2))?.let { it / 5 - 500 },
                afterGap = false,
                source = LocationSource.GPS.name,
                cadence = cadenceOf(record.double(4), record.double(53)),
                heartRate = HeartRates.valid(record.int(3))
            )
        }

        val pauses = pausesOf(of(EVENT))
        val laps = of(LAP).mapNotNull(::lapOf)
        val recordTimes = records.mapNotNull { timeOf(it.long(FitDecoder.TIMESTAMP)) }

        return ParsedActivity(
            points = points,
            timeMissing = timeMissing,
            pauses = pauses,
            startTime = sessions.mapNotNull { timeOf(it.long(2)) }.minOrNull()
                ?: laps.minOfOrNull { it.start }
                ?: recordTimes.minOrNull()
                ?: fileIds.firstNotNullOfOrNull { timeOf(it.long(4)) },
            sport = sportOf(sessions, of(SPORT), of(LAP), of(ACTIVITY)),
            device = deviceOf(fileIds, of(DEVICE_INFO)),
            laps = laps,
            totals = totalsOf(sessions, laps, records, recordTimes, pauses)
        )
    }

    /** Unix ms of a FIT date_time; null without one or for a time since power-on. */
    private fun timeOf(raw: Long?): Long? =
        raw?.takeIf { it >= MIN_DATE_TIME }?.let { (it + FIT_EPOCH_S) * 1000 }

    /** Degrees of a record position; null without one, for 0/0, or out of range. */
    private fun positionOf(record: FitMessage): Pair<Double, Double>? {
        val lat = record.long(0)?.let { it * SEMICIRCLE_DEG } ?: return null
        val lon = record.long(1)?.let { it * SEMICIRCLE_DEG } ?: return null
        if (lat == 0.0 && lon == 0.0) return null
        if (abs(lat) > 90.0 || abs(lon) > 180.0) return null
        return lat to lon
    }

    /** Steps per minute: a running cadence counts the strides of one foot. */
    private fun cadenceOf(strides: Double?, fraction: Double?): Float? {
        val perFoot = (strides ?: return null) + (fraction ?: 0.0) / 128
        return (perFoot * 2).toFloat().takeIf { it > 0f }
    }

    /** Timer stop → start, AUTO when the stop was the auto-pause; null when there are none. */
    private fun pausesOf(events: List<FitMessage>): List<PauseInterval>? {
        val pauses = ArrayList<PauseInterval>()
        var stoppedAt: Long? = null
        var kind = PauseKind.MANUAL
        val timers = events
            .filter { it.int(0) == EVENT_TIMER }
            .mapNotNull { event -> timeOf(event.long(FitDecoder.TIMESTAMP))?.let { it to event } }
            .sortedBy { it.first }
        for ((time, event) in timers) {
            when (event.int(1)) {
                TIMER_STOP, TIMER_STOP_ALL -> if (stoppedAt == null) {
                    stoppedAt = time
                    kind = if (event.int(3) == TRIGGER_AUTO) PauseKind.AUTO else PauseKind.MANUAL
                }
                TIMER_START -> stoppedAt?.let { stop ->
                    if (time > stop) pauses += PauseInterval(stop, time, kind)
                    stoppedAt = null
                }
            }
        }
        return pauses.ifEmpty { null }
    }

    private fun lapOf(lap: FitMessage): Lap? {
        val start = timeOf(lap.long(2))
            ?: timeOf(lap.long(FitDecoder.TIMESTAMP))?.let { end ->
                val elapsedMs = lap.long(7) ?: return null
                end - elapsedMs
            }
            ?: return null
        return Lap(
            start = start,
            timerMs = lap.long(8),
            distanceM = lap.double(9)?.let { (it / 100).toFloat() },
            avgHr = HeartRates.valid(lap.int(15)),
            maxHr = HeartRates.valid(lap.int(16)),
            avgCadence = cadenceOf(lap.double(17), null)
        )
    }

    /**
     * The sessions' sports; without a session the sport message, then the laps. An automatic
     * multisport activity, or any session of another sport, makes the whole file no run.
     */
    private fun sportOf(
        sessions: List<FitMessage>,
        sports: List<FitMessage>,
        laps: List<FitMessage>,
        activities: List<FitMessage>
    ): ActivitySport {
        if (activities.any { it.int(2) == ACTIVITY_AUTO_MULTISPORT }) return ActivitySport.OTHER
        val declared = sessions.mapNotNull { it.int(5) }.ifEmpty {
            sports.mapNotNull { it.int(0) }.ifEmpty { laps.mapNotNull { it.int(25) } }
        }
        return ActivitySport.of(declared.map(ActivitySport::ofFitSport))
    }

    /** The product name of the file or of the recording device (index 0), else its maker. */
    private fun deviceOf(fileIds: List<FitMessage>, devices: List<FitMessage>): String? {
        val creator = devices.filter { it.int(0) == 0 }
        val name = fileIds.firstNotNullOfOrNull { it.string(8) }
            ?: creator.firstNotNullOfOrNull { it.string(27) }
        if (!name.isNullOrBlank()) return name.trim()
        val maker = fileIds.firstNotNullOfOrNull { it.int(1) } ?: creator.firstNotNullOfOrNull { it.int(2) }
        return maker?.let { MAKERS[it] }
    }

    private fun totalsOf(
        sessions: List<FitMessage>,
        laps: List<Lap>,
        records: List<FitMessage>,
        recordTimes: List<Long>,
        pauses: List<PauseInterval>?
    ): ActivityTotals {
        val sessionTimers = sessions.map { it.long(8) }
        val distanceM = sessions.mapNotNull { it.double(9) }.takeIf { it.isNotEmpty() }?.sum()?.div(100)
            ?: records.lastOrNull { it.long(5) != null }?.double(5)?.div(100)
        val timerMs = sessionTimers.filterNotNull().takeIf { it.isNotEmpty() }?.sum()
            ?: recordTimes.takeIf { it.size >= 2 }?.let { times ->
                val first = times.min()
                val last = times.max()
                (last - first - TrackPauses.overlapMs(pauses, first, last)).coerceAtLeast(0L)
            }
        val sessionWeights = sessionTimers.map { it ?: 0L }
        val lapWeights = laps.map { it.timerMs ?: 0L }
        return ActivityTotals(
            distanceM = distanceM?.takeIf { it > 0.0 },
            timerMs = timerMs?.takeIf { it > 0L },
            calories = sessions.mapNotNull { it.int(11) }.takeIf { it.isNotEmpty() }?.sum()?.takeIf { it > 0 },
            avgHeartRate = (
                timeWeighted(sessions.map { HeartRates.valid(it.int(16))?.toDouble() }, sessionWeights)
                    ?: timeWeighted(laps.map { it.avgHr?.toDouble() }, lapWeights)
                )?.roundToInt(),
            maxHeartRate = sessions.mapNotNull { HeartRates.valid(it.int(17)) }.maxOrNull()
                ?: laps.mapNotNull { it.maxHr }.maxOrNull(),
            avgCadence = (
                timeWeighted(sessions.map { cadenceOf(it.double(18), null)?.toDouble() }, sessionWeights)
                    ?: timeWeighted(laps.map { it.avgCadence?.toDouble() }, lapWeights)
                )?.toFloat()
        )
    }

    /**
     * The average of [values] over their [weights] (time); a plain average when none has a
     * time, as a session without a timer still has its values. Null when no value.
     */
    private fun timeWeighted(values: List<Double?>, weights: List<Long>): Double? {
        val known = values.zip(weights).mapNotNull { (value, weight) -> value?.let { it to weight } }
        if (known.isEmpty()) return null
        val total = known.sumOf { it.second }
        if (total <= 0L) return known.map { it.first }.average()
        return known.sumOf { it.first * it.second } / total
    }
}
