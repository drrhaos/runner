package com.runner.academy.util

import java.io.File

/**
 * Where GPS diagnostics files live (app-internal storage, outside DB and backups).
 *
 * A recording is written to `session-<startTime>.jsonl` while the workout runs; when the
 * workout is saved it becomes `workout-<id>.jsonl` and lives as long as the workout.
 * Sessions that were never saved are removed by [cleanupOrphans].
 */
class GpsDiagnosticsStore(private val dir: File) {

    fun sessionFile(startTimeMs: Long): File = File(dir, "session-$startTimeMs.jsonl")

    /** The saved workout's recording, or null when diagnostics were off for that run. */
    fun workoutFile(workoutId: Long): File? =
        File(dir, "workout-$workoutId.jsonl").takeIf { it.isFile }

    /** Returns false when the session was not recorded. */
    fun attachToWorkout(startTimeMs: Long, workoutId: Long): Boolean {
        val session = sessionFile(startTimeMs)
        if (!session.isFile) return false
        return session.renameTo(File(dir, "workout-$workoutId.jsonl"))
    }

    fun delete(workoutId: Long) {
        File(dir, "workout-$workoutId.jsonl").delete()
    }

    /** Drops unsaved recordings older than [maxAgeMs]; fresh ones may still be in progress. */
    fun cleanupOrphans(nowMs: Long, maxAgeMs: Long = ORPHAN_MAX_AGE_MS) {
        dir.listFiles { f -> f.isFile && f.name.startsWith("session-") }
            ?.filter { nowMs - it.lastModified() > maxAgeMs }
            ?.forEach { it.delete() }
    }

    companion object {
        const val DIRECTORY = "gps-diagnostics"
        const val ORPHAN_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
