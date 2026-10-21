package com.runner.academy.util

import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter
import java.util.Date

/** [WorkoutBackupFormat.BackupWriter] and the size cap of [WorkoutBackupFormat.backupBatches]. */
class WorkoutBackupStreamingTest {

    private fun track(start: Long, points: Int) = TrackDataJson.toJson(
        TrackData(
            points = (0 until points).map { i ->
                TrackPoint(55.75 + i * 1e-4, 37.61, start + i * 1_000L, 5f, 3f, 150.0 + i)
            },
            totalDistance = points * 11f,
            totalDuration = points * 1_000L,
            avgSpeed = 3f,
            maxSpeed = 3f,
            startTime = start,
            endTime = start + points * 1_000L
        )
    )

    private fun workout(day: Int, trackPoints: Int? = null): Workout {
        val start = day * 86_400_000L
        return Workout(
            date = Date(start), distance = 5f, duration = 1_800_000L, movingDuration = 1_800_000L,
            avgPace = 6f, calories = null, notes = "day $day", type = WorkoutType.EASY_RUN,
            trackData = trackPoints?.let { track(start, it) }
        )
    }

    private fun written(workouts: List<Workout>, finish: Boolean = true): String {
        val out = StringWriter()
        // Not closed: closing an unfinished document throws, the cut text is what is left
        val writer = WorkoutBackupFormat.BackupWriter(out, "test", workouts.size)
        workouts.forEach(writer::write)
        if (finish) writer.finish()
        return out.toString()
    }

    @Test
    fun writtenRowByRow_readsBackTheSameWorkouts_countFirst() {
        val workouts = listOf(workout(1, trackPoints = 50), workout(2), workout(3, trackPoints = 10))
        val events = mutableListOf<String>()

        val read = WorkoutBackupFormat.backupBatches(StringReader(written(workouts)), batchSize = 2) {
            events += "count $it"
        }.onEach { events += "batch ${it.size}" }.flatten().toList()

        assertEquals("the total is known before the rows", listOf("count 3", "batch 2", "batch 1"), events)
        assertEquals(workouts.map { it.date }, read.map { it.date })
        assertEquals(workouts.map { it.notes }, read.map { it.notes })
        assertEquals(
            workouts.map { TrackDataJson.parse(it.trackData)?.points },
            read.map { TrackDataJson.parse(it.trackData)?.points }
        )
    }

    @Test
    fun aWriterClosedWithoutFinish_leavesAFileTheImportRejects() {
        val cut = written(listOf(workout(1, trackPoints = 5)), finish = false)

        assertThrows(IllegalArgumentException::class.java) {
            WorkoutBackupFormat.backupBatches(StringReader(cut), batchSize = 10).toList()
        }
    }

    @Test
    fun batches_areCutByTheSizeOfTheirTracks_rowsWithoutTracksByCountOnly() {
        val oneTrack = track(0L, 40).length.toLong()
        val workouts = listOf(
            workout(1, trackPoints = 40), workout(2, trackPoints = 40), workout(3, trackPoints = 40),
            workout(4), workout(5), workout(6)
        )

        val sizes = WorkoutBackupFormat.backupBatches(
            StringReader(written(workouts)), batchSize = 100, maxBatchChars = 2 * oneTrack
        ).map { it.size }.toList()

        assertEquals("two tracks fill a batch; manual rows add nothing to the size", listOf(2, 4), sizes)
    }
}
