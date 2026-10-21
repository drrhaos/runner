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
import com.runner.academy.util.NotAnActivityException
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
 * or a session. Messages may come in any order (summary first or last): all are gathered, then
 * points and laps are put in time order.
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

    // file_id fields
    private const val FILE_ID_TYPE = 0
    private const val FILE_ID_TIME_CREATED = 4
    private const val FILE_ID_PRODUCT_NAME = 8

    // device_info fields
    private const val DEVICE_INDEX = 0
    private const val DEVICE_PRODUCT_NAME = 27

    // sport fields
    private const val SPORT_SPORT = 0

    // activity fields
    private const val ACTIVITY_TYPE = 2

    // record fields; speeds /1000 m/s, altitudes /5 − 500 m, distance /100 m
    private const val RECORD_LAT = 0
    private const val RECORD_LON = 1
    private const val RECORD_ALTITUDE = 2
    private const val RECORD_HEART_RATE = 3
    private const val RECORD_CADENCE = 4
    private const val RECORD_DISTANCE = 5
    private const val RECORD_SPEED = 6
    private const val RECORD_GPS_ACCURACY = 31
    private const val RECORD_FRACTIONAL_CADENCE = 53
    private const val RECORD_ENHANCED_SPEED = 73
    private const val RECORD_ENHANCED_ALTITUDE = 78

    // event fields
    private const val EVENT_EVENT = 0
    private const val EVENT_TYPE = 1
    private const val EVENT_DATA = 3

    // lap fields; times in ms, distance /100 m
    private const val LAP_START_TIME = 2
    private const val LAP_ELAPSED_TIME = 7
    private const val LAP_TIMER_TIME = 8
    private const val LAP_DISTANCE = 9
    private const val LAP_AVG_HEART_RATE = 15
    private const val LAP_MAX_HEART_RATE = 16
    private const val LAP_AVG_CADENCE = 17
    private const val LAP_SPORT = 25

    // session fields; times in ms, distance /100 m
    private const val SESSION_START_TIME = 2
    private const val SESSION_SPORT = 5
    private const val SESSION_TIMER_TIME = 8
    private const val SESSION_DISTANCE = 9
    private const val SESSION_CALORIES = 11
    private const val SESSION_AVG_HEART_RATE = 16
    private const val SESSION_MAX_HEART_RATE = 17
    private const val SESSION_AVG_CADENCE = 18

    private const val FILE_TYPE_ACTIVITY = 4
    private const val ACTIVITY_AUTO_MULTISPORT = 1

    // Timer events and their types
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

    fun parseFit(input: InputStream, fileName: String? = null): Workout =
        TrackFileImport.workoutOf(parse(input, fileName), fileName)

    /**
     * Throws [NotAnActivityException] for another FIT file (a course, settings), and
     * IllegalArgumentException for an activity with neither records nor a session.
     */
    fun parse(input: InputStream, fileName: String? = null): ParsedActivity {
        val decoded = FitDecoder.decode(input, READ)
        val byType = decoded.messages.groupBy { it.global }
        fun of(global: Int) = byType[global].orEmpty()
        val named = fileName?.let { " ($it)" } ?: ""

        val fileIds = of(FILE_ID)
        if (fileIds.none { it.int(FILE_ID_TYPE) == FILE_TYPE_ACTIVITY }) {
            throw NotAnActivityException("Not a FIT activity$named")
        }
        val records = of(RECORD).sortedBy { it.long(FitDecoder.TIMESTAMP) ?: Long.MAX_VALUE }
        val sessions = of(SESSION)
        if (records.isEmpty() && sessions.isEmpty()) {
            throw IllegalArgumentException("FIT activity without data$named")
        }
        if (!decoded.intact) Log.w(TAG, "Damaged FIT file$named: importing what was read")

        val (points, timeMissing) = pointsOf(records)
        val pauses = pausesOf(of(EVENT))
        val laps = of(LAP).mapNotNull(::lapOf).sortedBy { it.start }

        return ParsedActivity(
            points = points,
            timeMissing = timeMissing,
            pauses = pauses,
            startTime = sessions.mapNotNull { timeOf(it.long(SESSION_START_TIME)) }.minOrNull()
                ?: laps.firstOrNull()?.start
                ?: records.firstNotNullOfOrNull { timeOf(it.long(FitDecoder.TIMESTAMP)) }
                ?: fileIds.firstNotNullOfOrNull { timeOf(it.long(FILE_ID_TIME_CREATED)) },
            sport = sportOf(sessions, of(SPORT), of(LAP), of(ACTIVITY)),
            device = deviceOf(fileIds, of(DEVICE_INFO)),
            laps = laps,
            totals = totalsOf(sessions, laps, records, pauses)
        )
    }

    /** Unix ms of a FIT date_time; null without one or for a time since power-on. */
    private fun timeOf(raw: Long?): Long? =
        raw?.takeIf { it >= MIN_DATE_TIME }?.let { (it + FIT_EPOCH_S) * 1000 }

    /**
     * The track of the [records] (in time order) that have a position; a record without a time
     * follows the previous point by a second (made up: true in the pair).
     */
    private fun pointsOf(records: List<FitMessage>): Pair<List<TrackPoint>, Boolean> {
        var timeMissing = false
        val points = ArrayList<TrackPoint>()
        for (record in records) {
            val lat = degreesOf(record.long(RECORD_LAT)) ?: continue
            val lon = degreesOf(record.long(RECORD_LON)) ?: continue
            if ((lat == 0.0 && lon == 0.0) || abs(lat) > 90.0 || abs(lon) > 180.0) continue
            val time = timeOf(record.long(FitDecoder.TIMESTAMP))
                ?: points.lastOrNull()?.timestamp?.plus(1000L)?.also { timeMissing = true }
                ?: continue
            points += TrackPoint(
                latitude = lat,
                longitude = lon,
                timestamp = time,
                accuracy = record.long(RECORD_GPS_ACCURACY)?.toFloat(),
                speed = (record.double(RECORD_ENHANCED_SPEED) ?: record.double(RECORD_SPEED))
                    ?.let { (it / 1000).toFloat() },
                altitude = (record.double(RECORD_ENHANCED_ALTITUDE) ?: record.double(RECORD_ALTITUDE))
                    ?.let { it / 5 - 500 },
                afterGap = false,
                source = LocationSource.GPS.name,
                cadence = cadenceOf(record.double(RECORD_CADENCE), record.double(RECORD_FRACTIONAL_CADENCE)),
                heartRate = HeartRates.valid(record.int(RECORD_HEART_RATE))
            )
        }
        return points to timeMissing
    }

    private fun degreesOf(semicircles: Long?): Double? = semicircles?.let { it * SEMICIRCLE_DEG }

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
            .filter { it.int(EVENT_EVENT) == EVENT_TIMER }
            .mapNotNull { event -> timeOf(event.long(FitDecoder.TIMESTAMP))?.let { it to event } }
            .sortedBy { it.first }
        for ((time, event) in timers) {
            when (event.int(EVENT_TYPE)) {
                TIMER_STOP, TIMER_STOP_ALL -> if (stoppedAt == null) {
                    stoppedAt = time
                    kind = if (event.int(EVENT_DATA) == TRIGGER_AUTO) PauseKind.AUTO else PauseKind.MANUAL
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
        val start = timeOf(lap.long(LAP_START_TIME))
            ?: timeOf(lap.long(FitDecoder.TIMESTAMP))?.let { end ->
                val elapsedMs = lap.long(LAP_ELAPSED_TIME) ?: return null
                end - elapsedMs
            }
            ?: return null
        return Lap(
            start = start,
            timerMs = lap.long(LAP_TIMER_TIME),
            distanceM = lap.double(LAP_DISTANCE)?.let { (it / 100).toFloat() },
            avgHr = HeartRates.valid(lap.int(LAP_AVG_HEART_RATE)),
            maxHr = HeartRates.valid(lap.int(LAP_MAX_HEART_RATE)),
            avgCadence = cadenceOf(lap.double(LAP_AVG_CADENCE), null)
        )
    }

    /**
     * The sessions' sports; without a session the sport message, then the laps. An automatic
     * multisport activity, or any session of another known sport, makes the whole file no run
     * (a generic session next to a running one does not).
     */
    private fun sportOf(
        sessions: List<FitMessage>,
        sports: List<FitMessage>,
        laps: List<FitMessage>,
        activities: List<FitMessage>
    ): ActivitySport {
        if (activities.any { it.int(ACTIVITY_TYPE) == ACTIVITY_AUTO_MULTISPORT }) return ActivitySport.OTHER
        val declared = sessions.mapNotNull { it.int(SESSION_SPORT) }.ifEmpty {
            sports.mapNotNull { it.int(SPORT_SPORT) }.ifEmpty { laps.mapNotNull { it.int(LAP_SPORT) } }
        }
        return ActivitySport.of(declared.map(ActivitySport::ofFitSport))
    }

    /** The product name of the file or of the recording device (index 0); never a maker number. */
    private fun deviceOf(fileIds: List<FitMessage>, devices: List<FitMessage>): String? {
        val creator = devices.filter { it.int(DEVICE_INDEX) == 0 }
        val name = fileIds.firstNotNullOfOrNull { it.string(FILE_ID_PRODUCT_NAME) }
            ?: creator.firstNotNullOfOrNull { it.string(DEVICE_PRODUCT_NAME) }
        return name?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun totalsOf(
        sessions: List<FitMessage>,
        laps: List<Lap>,
        records: List<FitMessage>,
        pauses: List<PauseInterval>?
    ): ActivityTotals {
        val sessionTimers = sessions.map { it.long(SESSION_TIMER_TIME) }
        val distanceM = sessions.mapNotNull { it.double(SESSION_DISTANCE) }
            .takeIf { it.isNotEmpty() }?.sum()?.div(100)
            ?: records.lastOrNull { it.long(RECORD_DISTANCE) != null }?.double(RECORD_DISTANCE)?.div(100)
        val recordTimes = records.mapNotNull { timeOf(it.long(FitDecoder.TIMESTAMP)) }
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
            calories = sessions.mapNotNull { it.int(SESSION_CALORIES) }
                .takeIf { it.isNotEmpty() }?.sum()?.takeIf { it > 0 },
            avgHeartRate = (
                timeWeighted(sessions.map { HeartRates.valid(it.int(SESSION_AVG_HEART_RATE))?.toDouble() }, sessionWeights)
                    ?: timeWeighted(laps.map { it.avgHr?.toDouble() }, lapWeights)
                )?.roundToInt(),
            maxHeartRate = sessions.mapNotNull { HeartRates.valid(it.int(SESSION_MAX_HEART_RATE)) }.maxOrNull()
                ?: laps.mapNotNull { it.maxHr }.maxOrNull(),
            avgCadence = (
                timeWeighted(sessions.map { cadenceOf(it.double(SESSION_AVG_CADENCE), null)?.toDouble() }, sessionWeights)
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
