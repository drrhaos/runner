package com.runner.academy.data

/** New rows of a file import and the record distances they hold now ("Records updated: N"). */
data class FileImport(val ids: List<Long>, val changedRecordDistances: Int)

/** [workouts] imported in one batch as files' rows (metrics computed before saving). */
suspend fun WorkoutRepository.importFiles(workouts: List<Workout>): FileImport {
    val ids = importBatch(workouts.map { ImportCandidate(it) }, WorkoutStore.Mode.INLINE).insertedIds
    return FileImport(ids, recordDistancesHeldBy(ids))
}

/** [workouts] imported in one batch as a backup's rows (derived by the background pass). */
suspend fun WorkoutRepository.importBackupRows(workouts: List<Workout>): List<Long> =
    importBatch(workouts.map { ImportCandidate(it) }, WorkoutStore.Mode.DEFERRED).insertedIds
