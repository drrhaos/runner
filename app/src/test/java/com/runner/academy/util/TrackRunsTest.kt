package com.runner.academy.util

import com.runner.academy.data.LocationSource
import com.runner.academy.data.TrackPoint
import com.runner.academy.util.TrackRun.Bridge
import com.runner.academy.util.TrackRun.Gap
import com.runner.academy.util.TrackRun.Solid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackRunsTest {

    private fun point(
        lat: Double,
        afterGap: Boolean = false,
        bridgeMeters: Float? = null,
        source: LocationSource = LocationSource.GPS
    ) = TrackPoint(
        latitude = lat,
        longitude = 37.0,
        timestamp = (lat * 1_000_000).toLong(),
        accuracy = 5f,
        speed = 3f,
        altitude = null,
        afterGap = afterGap,
        source = source.name,
        bridgeMeters = bridgeMeters
    )

    private fun lats(run: TrackRun): List<Double> = run.points.map { it.latitude }

    @Test
    fun empty_track_has_no_runs() {
        assertTrue(TrackRuns.split(emptyList()).isEmpty())
    }

    @Test
    fun single_point_is_one_solid_run() {
        val runs = TrackRuns.split(listOf(point(1.0)))
        assertEquals(1, runs.size)
        assertTrue(runs[0] is Solid)
        assertEquals(listOf(1.0), lats(runs[0]))
    }

    @Test
    fun unbroken_track_is_one_solid_run() {
        val runs = TrackRuns.split(listOf(point(1.0), point(2.0), point(3.0)))
        assertEquals(1, runs.size)
        assertEquals(listOf(1.0, 2.0, 3.0), lats(runs.single() as Solid))
    }

    @Test
    fun plain_gap_becomes_a_gap_run_between_solids() {
        val runs = TrackRuns.split(
            listOf(point(1.0), point(2.0), point(3.0, afterGap = true), point(4.0))
        )
        assertEquals(3, runs.size)
        assertEquals(listOf(1.0, 2.0), lats(runs[0] as Solid))
        val gap = runs[1] as Gap
        assertEquals(2.0, gap.from.latitude, 0.0)
        assertEquals(3.0, gap.to.latitude, 0.0)
        assertEquals(listOf(3.0, 4.0), lats(runs[2] as Solid))
    }

    @Test
    fun bridge_step_becomes_a_dashed_run_between_solids() {
        val runs = TrackRuns.split(
            listOf(point(1.0), point(2.0), point(3.0, afterGap = true, bridgeMeters = 120f), point(4.0))
        )
        assertEquals(3, runs.size)
        assertEquals(listOf(1.0, 2.0), lats(runs[0] as Solid))
        val bridge = runs[1] as Bridge
        assertEquals(2.0, bridge.from.latitude, 0.0)
        assertEquals(3.0, bridge.to.latitude, 0.0)
        assertEquals(listOf(3.0, 4.0), lats(runs[2] as Solid))
    }

    @Test
    fun bridge_from_pedometer_is_flagged() {
        val runs = TrackRuns.split(
            listOf(
                point(1.0),
                point(2.0, afterGap = true, bridgeMeters = 80f, source = LocationSource.PEDOMETER),
                point(3.0, afterGap = true, bridgeMeters = 80f)
            )
        )
        val bridges = runs.filterIsInstance<Bridge>()
        assertEquals(2, bridges.size)
        assertTrue(bridges[0].fromPedometer)
        assertFalse(bridges[1].fromPedometer)
    }

    @Test
    fun bridge_on_the_last_point_ends_with_a_one_point_solid() {
        val runs = TrackRuns.split(listOf(point(1.0), point(2.0, afterGap = true, bridgeMeters = 50f)))
        assertEquals(3, runs.size)
        assertTrue(runs[1] is Bridge)
        assertEquals(listOf(2.0), lats(runs[2] as Solid))
    }

    @Test
    fun break_flags_on_the_first_point_are_ignored() {
        val runs = TrackRuns.split(listOf(point(1.0, afterGap = true, bridgeMeters = 30f), point(2.0)))
        assertEquals(1, runs.size)
        assertEquals(listOf(1.0, 2.0), lats(runs.single() as Solid))
    }

    @Test
    fun consecutive_breaks_keep_every_point() {
        val runs = TrackRuns.split(
            listOf(
                point(1.0),
                point(2.0, afterGap = true),
                point(3.0, afterGap = true, bridgeMeters = 10f),
                point(4.0, afterGap = true)
            )
        )
        assertEquals(
            listOf(Solid::class, Gap::class, Solid::class, Bridge::class, Solid::class, Gap::class, Solid::class),
            runs.map { it::class }
        )
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), runs.filterIsInstance<Solid>().flatMap { lats(it) })
    }

    @Test
    fun solids_and_bridges_helpers_partition_the_runs() {
        val runs = TrackRuns.split(
            listOf(point(1.0), point(2.0, afterGap = true, bridgeMeters = 10f), point(3.0, afterGap = true))
        )
        assertEquals(3, runs.solids().size)
        assertEquals(1, runs.bridges().size)
        assertEquals(1, runs.gaps().size)
    }

    @Test
    fun downsample_keeps_every_break_and_both_ends_of_each_run() {
        val points = buildList {
            for (i in 0 until 100) add(point(i.toDouble()))
            add(point(100.0, afterGap = true, bridgeMeters = 200f))
            for (i in 101 until 200) add(point(i.toDouble()))
            add(point(200.0, afterGap = true))
            for (i in 201 until 300) add(point(i.toDouble()))
        }
        val runs = TrackRuns.downsample(TrackRuns.split(points), maxPoints = 30)

        assertEquals(listOf(Solid::class, Bridge::class, Solid::class, Gap::class, Solid::class), runs.map { it::class })
        val solids = runs.filterIsInstance<Solid>()
        assertEquals(listOf(0.0, 99.0), listOf(solids[0].points.first().latitude, solids[0].points.last().latitude))
        assertEquals(listOf(100.0, 199.0), listOf(solids[1].points.first().latitude, solids[1].points.last().latitude))
        assertEquals(listOf(200.0, 299.0), listOf(solids[2].points.first().latitude, solids[2].points.last().latitude))
        val bridge = runs[1] as Bridge
        assertEquals(99.0, bridge.from.latitude, 0.0)
        assertEquals(100.0, bridge.to.latitude, 0.0)
        val gap = runs[3] as Gap
        assertEquals(199.0, gap.from.latitude, 0.0)
        assertEquals(200.0, gap.to.latitude, 0.0)
        assertTrue(solids.sumOf { it.points.size } <= 30)
    }

    @Test
    fun downsample_leaves_small_tracks_untouched() {
        val runs = TrackRuns.split(listOf(point(1.0), point(2.0), point(3.0)))
        assertEquals(runs, TrackRuns.downsample(runs, maxPoints = 10))
    }

    @Test
    fun all_points_lists_every_drawn_point_once() {
        val runs = TrackRuns.split(
            listOf(point(1.0), point(2.0, afterGap = true, bridgeMeters = 10f), point(3.0, afterGap = true))
        )
        assertEquals(listOf(1.0, 2.0, 3.0), runs.allPoints().map { it.latitude })
    }

    @Test
    fun lead_in_on_the_first_point_is_not_drawn() {
        val runs = TrackRuns.split(listOf(point(55.0, bridgeMeters = 120f), point(55.001)))
        assertEquals(1, runs.size)
        assertTrue(runs.single() is Solid)
    }
}
