package com.runner.academy.util

import com.runner.academy.data.Lap
import com.runner.academy.data.PauseInterval
import com.runner.academy.data.TrackPoint

/**
 * What a track file (GPX, TCX, FIT) says about one activity, before it becomes a workout: every
 * format gives this, and [TrackFileImport.workoutOf] alone turns it into a
 * [com.runner.academy.data.Workout] (the sport, the totals, laps and device in one place).
 */
data class ParsedActivity(
    val points: List<TrackPoint>,
    /** Some point had no time of its own (made up). */
    val timeMissing: Boolean = false,
    val pauses: List<PauseInterval>? = null,
    /** When the activity began, for a file without a track (the first lap, the activity id). */
    val startTime: Long? = null,
    val sport: ActivitySport = ActivitySport.UNKNOWN,
    val title: String? = null,
    val description: String? = null,
    val device: String? = null,
    val laps: List<Lap> = emptyList(),
    /** What the device counted (TCX laps, FIT sessions); null — the points tell. */
    val totals: ActivityTotals? = null
)

/** The totals a device wrote; each null when the file has none. */
data class ActivityTotals(
    val distanceM: Double? = null,
    /** The time the device counted, its pauses left out. */
    val timerMs: Long? = null,
    val calories: Int? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    /** Steps per minute. */
    val avgCadence: Float? = null
)

/**
 * The sport a file declares. Only [OTHER] is skipped: a file that does not say (or says
 * something unknown) is imported as a run — losing a run is worse than one to delete.
 */
enum class ActivitySport {
    RUNNING,
    OTHER,
    UNKNOWN;

    companion object {

        /** GPX `trk/type`: numeric codes some services write (9 run; 1 ride, 4 hike, 10 walk) or words, any case. */
        fun ofGpxType(raw: String?): ActivitySport {
            val type = raw?.lowercase()?.filter { it.isLetterOrDigit() }.orEmpty()
            return when (type) {
                in GPX_RUNS -> RUNNING
                in GPX_OTHERS -> OTHER
                else -> UNKNOWN
            }
        }

        /** TCX `Activity Sport`: Running, Biking or Other. */
        fun ofTcxSport(raw: String?): ActivitySport = when (raw?.trim()?.lowercase()) {
            "running" -> RUNNING
            "biking" -> OTHER
            else -> UNKNOWN
        }

        /** FIT `sport`: 1 running (any sub-sport), 0 generic or none unknown, the rest another sport. */
        fun ofFitSport(raw: Int?): ActivitySport = when (raw) {
            1 -> RUNNING
            0, null -> UNKNOWN
            else -> OTHER
        }

        /** Activities of one file: any other sport makes it no run (a multisport file). */
        fun of(sports: List<ActivitySport>): ActivitySport = when {
            OTHER in sports -> OTHER
            RUNNING in sports -> RUNNING
            else -> UNKNOWN
        }

        // Lower case, letters and digits only: "trail_running", "TrailRun" alike
        private val GPX_RUNS = setOf(
            "9", "run", "running", "trailrun", "trailrunning", "treadmill", "treadmillrunning",
            "virtualrun", "virtualrunning", "trackrunning", "streetrunning", "indoorrunning"
        )
        private val GPX_OTHERS = setOf(
            "1", "4", "10",
            "ride", "cycling", "biking", "bike", "roadbiking", "mountainbiking", "gravelride",
            "virtualride", "ebikeride", "ebikeriding", "handcycle", "velomobile",
            "walk", "walking", "hike", "hiking",
            "swim", "swimming", "openwaterswimming", "lapswimming",
            "rowing", "kayaking", "canoeing", "sailing", "surfing", "kitesurf", "windsurf",
            "alpineski", "backcountryski", "nordicski", "skiing", "crosscountryskiing", "snowboard",
            "snowboarding", "snowshoe", "iceskate", "inlineskate", "rollerski", "skateboard",
            "strengthtraining", "weighttraining", "yoga", "elliptical", "stairstepper", "rockclimbing"
        )
    }
}

/** The file is a workout of another sport than running: skipped, counted apart from errors. */
class NotRunningException(fileName: String?) :
    IllegalArgumentException("Not a run${fileName?.let { " ($it)" } ?: ""}")
