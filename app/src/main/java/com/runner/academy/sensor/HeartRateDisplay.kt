package com.runner.academy.sensor

/**
 * What the heart-rate tile shows: the value, how fresh it is and the state of the link. Built
 * once a second by the service during a workout and by the workout screen before the start.
 *
 * - Up to 5 s old: the value ([Freshness.FRESH]).
 * - 5–15 s: the last value greyed ([Freshness.STALE]): an unsteady radio does not blink.
 * - Older, no contact, nothing yet: no value ([Freshness.NONE], `--`).
 *
 * @param noContact the newest sample says "no skin contact" while connected: the strap is off,
 *   the link is fine.
 */
data class HeartRateDisplay(
    val bpm: Int?,
    val freshness: Freshness,
    val link: LinkIcon,
    val noContact: Boolean = false
) {

    enum class Freshness { FRESH, STALE, NONE }

    /** The icon next to the tile's label. */
    enum class LinkIcon { CONNECTED, SEARCHING, LOST }

    /** A valid value up to 5 s old. */
    val isFresh: Boolean get() = freshness == Freshness.FRESH

    companion object {
        const val FRESH_NANOS = 5_000_000_000L
        const val STALE_NANOS = 15_000_000_000L

        /** [latest] is the newest sample, "no contact" ones included; null before the first. */
        fun of(latest: HeartRateSample?, link: SensorLink, nowNanos: Long): HeartRateDisplay {
            val age = latest?.let { nowNanos - it.elapsedNanos }
            val recent = age != null && age <= STALE_NANOS
            val bpm = latest?.bpm?.takeIf { recent }
            val freshness = when {
                bpm == null -> Freshness.NONE
                age!! <= FRESH_NANOS -> Freshness.FRESH
                else -> Freshness.STALE
            }
            val noContact = link == SensorLink.CONNECTED && recent && latest?.bpm == null
            val icon = when (link) {
                SensorLink.CONNECTING, SensorLink.SEARCHING -> LinkIcon.SEARCHING
                SensorLink.BLUETOOTH_OFF, SensorLink.NO_PERMISSION, SensorLink.IDLE -> LinkIcon.LOST
                // Connected but silent for longer than the grey value lasts: the data is lost
                SensorLink.CONNECTED -> if (latest != null && !recent) LinkIcon.LOST else LinkIcon.CONNECTED
            }
            return HeartRateDisplay(bpm, freshness, icon, noContact)
        }

        /**
         * [of] for a holder of a lease: [SensorLink.IDLE] there is the moment before the sensor
         * takes the lease up (it works on its own thread), not a lost link — searching, not a red
         * flash at the start.
         */
        fun ofLeased(latest: HeartRateSample?, link: SensorLink, nowNanos: Long): HeartRateDisplay =
            of(latest, if (link == SensorLink.IDLE) SensorLink.CONNECTING else link, nowNanos)

        /**
         * The sensor is paired but this workout cannot use it (the system refused the
         * connected-device service type): no value, the lost icon.
         */
        val UNAVAILABLE = HeartRateDisplay(null, Freshness.NONE, LinkIcon.LOST)
    }
}
