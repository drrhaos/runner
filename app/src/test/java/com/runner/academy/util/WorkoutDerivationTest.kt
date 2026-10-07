package com.runner.academy.util

import com.runner.academy.data.BestEffort
import com.runner.academy.data.ElevationSource
import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutDerivationTest {

    private val trackJson = TrackDataJson.toJson(
        TrackData(
            (0 until 20).map { TrackPoint(55.75, 37.60 + it * 0.00016, 1_000_000L + it * 3_000L, 5f, 3f, 150.0) },
            0f, 0L, 0f, 0f, 1_000_000L, null
        )
    )

    private val nothingDerived = Derived(
        elevationGain = null,
        elevationLoss = null,
        elevationSource = null,
        avgCadence = null,
        routePreview = null,
        efforts = emptyList(),
        metricsVersion = WorkoutDerivation.CURRENT_METRICS_VERSION
    )

    /** A flat track at 150 m: computed, nothing climbed. */
    private val flatElevation = nothingDerived.copy(
        elevationGain = 0f,
        elevationLoss = 0f,
        elevationSource = ElevationSource.GPS
    )

    @Test
    fun `a track is derived with the current version`() {
        val derived = WorkoutDerivation.derive(DerivationInput(trackJson, WorkoutType.EASY_RUN, 57_000L))

        // The other metrics are added by their own release-3 branches
        assertEquals(flatElevation.copy(routePreview = derived.routePreview), derived)
    }

    private fun json(points: List<TrackPoint>, source: ElevationSource? = null) = TrackDataJson.toJson(
        TrackData(points, 0f, 0L, 0f, 0f, points.first().timestamp, null, elevationSource = source)
    )

    /** One fix a second for 10 min, 15 m up over the first 5 min and back down. */
    private val climbPoints = (0..600).map { sec ->
        val alt = 150.0 + 15.0 * minOf(sec, 600 - sec) / 300.0
        TrackPoint(55.75, 37.60 + sec * 0.00005, 1_000_000L + sec * 1_000L, 5f, 3f, alt)
    }

    @Test
    fun `a track with altitudes gets the gain and loss of its display track`() {
        val input = DerivationInput(json(climbPoints), WorkoutType.EASY_RUN, 600_000L)

        val derived = WorkoutDerivation.derive(input)

        val track = DisplayTrack.of(input.trackJson, WorkoutType.EASY_RUN)!!
        val expected = ElevationGain.compute(track.points, ElevationSource.GPS)!!
        // The moving average rounds the sharp top off a little
        assertEquals(15f, derived.elevationGain!!, 2.5f)
        assertEquals(expected.gainM, derived.elevationGain)
        assertEquals(expected.lossM, derived.elevationLoss)
        assertEquals(ElevationSource.GPS, derived.elevationSource)
    }

    @Test
    fun `the source a file declared is kept`() {
        val derived = WorkoutDerivation.derive(DerivationInput(json(climbPoints, ElevationSource.FILE), WorkoutType.EASY_RUN, 600_000L))

        assertEquals(ElevationSource.FILE, derived.elevationSource)
        assertEquals(15f, derived.elevationGain!!, 2.5f)
    }

    @Test
    fun `a track without altitudes has no gain and the source none`() {
        for (alt in listOf(null, 0.0)) {
            val points = climbPoints.map { it.copy(altitude = alt) }

            val derived = WorkoutDerivation.derive(DerivationInput(json(points), WorkoutType.EASY_RUN, 600_000L))

            assertNull(derived.elevationGain)
            assertNull(derived.elevationLoss)
            assertEquals(ElevationSource.NONE, derived.elevationSource)
        }
    }

    @Test
    fun `a track gets the route preview of its display track`() {
        val derived = WorkoutDerivation.derive(DerivationInput(trackJson, WorkoutType.EASY_RUN, 57_000L))

        val points = DisplayTrack.of(trackJson, WorkoutType.EASY_RUN)!!.points
        assertEquals(RoutePreviewCodec.encode(RoutePreviews.of(points)!!), derived.routePreview)
        assertEquals(20, RoutePreviewCodec.decode(derived.routePreview)!!.pointCount)
    }

    @Test
    fun `a track of one point has no route preview`() {
        val onePoint = TrackDataJson.toJson(
            TrackData(listOf(TrackPoint(55.75, 37.60, 1_000_000L, 5f, 3f, 150.0)), 0f, 0L, 0f, 0f, 1_000_000L, null)
        )

        assertEquals(flatElevation, WorkoutDerivation.derive(DerivationInput(onePoint, WorkoutType.EASY_RUN, 0L)))
    }

    @Test
    fun `a track with steps gets the average cadence of its moving time`() {
        // 170 steps/min for 5 min, 1 min standing on an auto-pause, 170 again for 5 min
        val start = 1_000_000L
        val points = (0..660 step 2).map { sec ->
            val moving = if (sec <= 300) sec else maxOf(300, sec - 60)
            TrackPoint(55.75, 37.60 + sec * 0.00004, start + sec * 1_000L, 5f, 3f, 150.0, steps = moving * 170 / 60, cadence = 170f)
        }
        val json = TrackDataJson.toJson(
            TrackData(
                points, 0f, 0L, 0f, 0f, start, null,
                pauses = listOf(PauseInterval(start + 300_000L, start + 360_000L, PauseKind.AUTO))
            )
        )

        val derived = WorkoutDerivation.derive(DerivationInput(json, WorkoutType.EASY_RUN, 660_000L))

        assertEquals(170f, derived.avgCadence!!, 1f)
        val track = DisplayTrack.of(json, WorkoutType.EASY_RUN)!!
        assertEquals(TrackCadence.average(track.points, track.pauses), derived.avgCadence)
    }

    /** 1.5 km east, one fix every 3 s, 9–11 m apart (a recorded run is never at one speed). */
    private val kmTrack = TrackData(
        (0..150).map { TrackPoint(55.75, 37.60 + it * 0.00016 + (it % 2) * 0.000016, 1_000_000L + it * 3_000L, 5f, 3f, null) },
        0f, 0L, 0f, 0f, 1_000_000L, null
    )

    @Test
    fun `a track gets its best efforts, a made-up time none`() {
        val efforts = WorkoutDerivation.derive(DerivationInput(TrackDataJson.toJson(kmTrack), WorkoutType.EASY_RUN, 450_000L)).efforts

        assertEquals(listOf(1_000), efforts.map { it.distanceM })
        assertEquals(300_000.0, efforts.single().elapsedMs.toDouble(), 3_000.0)

        val synthetic = TrackDataJson.toJson(kmTrack.copy(timeSynthetic = true))
        assertEquals(emptyList<Effort>(), WorkoutDerivation.derive(DerivationInput(synthetic, WorkoutType.EASY_RUN, 450_000L)).efforts)
    }

    @Test
    fun `a track without steps has no cadence`() {
        assertNull(WorkoutDerivation.derive(DerivationInput(trackJson, WorkoutType.EASY_RUN, 57_000L)).avgCadence)
    }

    @Test
    fun `no or broken track is marked computed with nothing derived`() {
        assertEquals(5, WorkoutDerivation.CURRENT_METRICS_VERSION)
        for (json in listOf(null, "", "{broken", "[1,2]")) {
            assertEquals(json, nothingDerived, WorkoutDerivation.derive(DerivationInput(json, WorkoutType.EASY_RUN, 0L)))
        }
    }

    @Test
    fun `applying overwrites every derived column and keeps the rest`() {
        val stale = Workout(
            id = 7L,
            date = Date(1_000L),
            distance = 5f,
            duration = 1_800_000L,
            movingDuration = 1_700_000L,
            avgPace = 5.7f,
            calories = 300,
            notes = "n",
            type = WorkoutType.TEMPO_RUN,
            trackData = trackJson,
            isFavorite = true,
            elevationGain = 12f,
            elevationLoss = 11f,
            elevationSource = ElevationSource.GPS,
            avgCadence = 170f,
            routePreview = "old",
            excludeFromRecords = true,
            avgHeartRate = 150,
            metricsVersion = 0
        )
        val derived = Derived(
            elevationGain = 20f,
            elevationLoss = null,
            elevationSource = ElevationSource.FILE,
            avgCadence = null,
            routePreview = "new",
            efforts = emptyList(),
            metricsVersion = 9
        )

        val applied = derived.applyTo(stale)

        assertEquals(
            stale.copy(
                elevationGain = 20f,
                elevationLoss = null,
                elevationSource = ElevationSource.FILE,
                avgCadence = null,
                routePreview = "new",
                metricsVersion = 9
            ),
            applied
        )
    }

    @Test
    fun `an effort becomes a best effort row of the workout`() {
        val effort = Effort(distanceM = 5_000, elapsedMs = 1_500_000L, startTime = 10L, endTime = 1_500_010L, stepsShare = 0.05f)

        assertEquals(BestEffort(3L, 5_000, 1_500_000L, 10L, 1_500_010L, 0.05f), effort.toBestEffort(3L))
    }
}
