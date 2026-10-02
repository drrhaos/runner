package com.runner.academy.data

import java.io.File

/**
 * Where GPS diagnostics files live (app-internal storage, outside DB and backups).
 *
 * A recording is written to `session-<startTime>.jsonl` while the workout runs; when the
 * workout is saved it becomes `workout-<id>.jsonl` and lives as long as the workout.
 * Sessions that were never saved are removed by [cleanupOrphans]. Copies handed to the
 * share sheet go to [shareDir] (a FileProvider cache path) and are deleted with the workout.
 */
class GpsDiagnosticsStore(
    private val dir: File,
    private val shareDir: File
) {

    fun sessionFile(startTimeMs: Long): File = File(dir, "session-$startTimeMs.jsonl")

    /** The saved workout's recording, or null when diagnostics were off for that run. */
    fun workoutFile(workoutId: Long): File? = workoutPath(workoutId).takeIf { it.isFile }

    /** Returns false when the session was not recorded. */
    fun attachToWorkout(startTimeMs: Long, workoutId: Long): Boolean {
        val session = sessionFile(startTimeMs)
        if (!session.isFile) return false
        return session.renameTo(workoutPath(workoutId))
    }

    /** Copy for the share sheet, or null when the workout has no recording. */
    fun copyForSharing(workoutId: Long): File? {
        val source = workoutFile(workoutId) ?: return null
        shareDir.mkdirs()
        return source.copyTo(sharePath(workoutId), overwrite = true)
    }

    fun delete(workoutId: Long) {
        workoutPath(workoutId).delete()
        sharePath(workoutId).delete()
    }

    /**
     * Drops unsaved recordings untouched for [maxAgeMs]. Generous on purpose: a workout left
     * paused writes nothing, and its recording must survive until it is saved.
     */
    fun cleanupOrphans(nowMs: Long, maxAgeMs: Long = ORPHAN_MAX_AGE_MS) {
        dir.listFiles { f -> f.isFile && f.name.startsWith("session-") }
            ?.filter { nowMs - it.lastModified() > maxAgeMs }
            ?.forEach { it.delete() }
    }

    private fun workoutPath(workoutId: Long) = File(dir, "workout-$workoutId.jsonl")

    private fun sharePath(workoutId: Long) = File(shareDir, "runner_gps_diagnostics_$workoutId.jsonl")

    companion object {
        const val DIRECTORY = "gps-diagnostics"
        const val SHARE_DIRECTORY = "gps-diagnostics-share"
        const val ORPHAN_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
