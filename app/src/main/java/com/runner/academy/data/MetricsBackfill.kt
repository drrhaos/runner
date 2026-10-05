package com.runner.academy.data

import android.database.sqlite.SQLiteBlobTooBigException
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import com.runner.academy.util.DerivationInput
import com.runner.academy.util.Derived
import com.runner.academy.util.WorkoutDerivation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

sealed interface BackfillState {
    data object Idle : BackfillState
    data class Running(val done: Int, val total: Int) : BackfillState

    /** [changedDistances]: record distances whose current record changed (for "Records updated: N"). */
    data class Done(val changedDistances: Int) : BackfillState
}

data class BackfillReport(
    /** Rows whose metrics were written by this pass. */
    val computed: Int,
    /**
     * Rows that could not be read (too big, out of memory) or derived (the derivation failed):
     * marked computed with nothing derived.
     */
    val unreadable: Int,
    /** Rows saved by an edit meanwhile: their own metrics were kept. */
    val skippedEdited: Int,
    /** The pass stopped because a workout is being recorded; rows are left for later. */
    val pausedForWorkout: Boolean,
    val changedDistances: Int = 0
)

/**
 * Computes derived metrics of rows saved without them ([Workout.metricsVersion] below
 * [version]): after the 6→7 migration, a backup import, or a raised
 * [WorkoutDerivation.CURRENT_METRICS_VERSION]. One row at a time (one track in memory), newest
 * first; the version on each row makes the pass resumable after the process dies, with no
 * other state. An app coroutine, not WorkManager: an unfinished pass simply continues on the
 * next start.
 */
class MetricsBackfill(
    private val database: WorkoutDatabase,
    private val scope: CoroutineScope,
    /** True while a workout is recorded: the pass waits (CPU and battery go to the run). */
    private val isWorkoutActive: suspend () -> Boolean = { false },
    private val derive: (DerivationInput) -> Derived = WorkoutDerivation::derive,
    private val version: Int = WorkoutDerivation.CURRENT_METRICS_VERSION
) {
    private val _progress = MutableStateFlow<BackfillState>(BackfillState.Idle)
    val progress: StateFlow<BackfillState> = _progress.asStateFlow()

    private val mutex = Mutex()
    private val lock = Any()
    private var job: Job? = null
    private var rerun = false

    /**
     * Runs the pass in [scope] unless it is running; a call during a pass makes it look for
     * new rows once more (an import while the pass finishes is not missed).
     */
    fun start() {
        synchronized(lock) {
            if (job != null) {
                rerun = true
                return
            }
            job = scope.launch { runUntilDone() }
        }
    }

    private suspend fun runUntilDone() {
        val self = coroutineContext[Job]
        try {
            while (true) {
                val report = runOnce()
                if (report.pausedForWorkout) {
                    delay(WORKOUT_POLL_MS)
                    continue
                }
                synchronized(lock) {
                    if (!rerun) return
                    rerun = false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failure outside a row's derivation (e.g. the database): rows not reached keep
            // their version and the next start continues from them
            Log.e(TAG, "Metrics pass failed", e)
        } finally {
            synchronized(lock) {
                if (job === self) job = null
            }
        }
    }

    /** One pass over every row below [version], including rows added while it runs. */
    suspend fun runOnce(): BackfillReport = mutex.withLock {
        val dao = database.workoutDao()
        var computed = 0
        var unreadable = 0
        var skipped = 0
        var done = 0
        fun report(paused: Boolean) = BackfillReport(computed, unreadable, skipped, paused)

        try {
            while (true) {
                val ids = dao.idsWithMetricsBelow(version, BATCH_SIZE)
                if (ids.isEmpty()) break
                val total = done + dao.countWithMetricsBelow(version)
                _progress.value = BackfillState.Running(done, total)
                for (id in ids) {
                    if (isWorkoutActive()) return@withLock report(paused = true)
                    when (recompute(id)) {
                        Outcome.COMPUTED -> computed++
                        Outcome.UNREADABLE -> unreadable++
                        Outcome.SKIPPED -> skipped++
                    }
                    done++
                    _progress.value = BackfillState.Running(done, maxOf(total, done))
                    yield()
                }
            }
        } catch (e: Throwable) {
            // Stopped or failed: nothing runs until the next start
            _progress.value = BackfillState.Idle
            throw e
        }
        // A pass with nothing to compute changed no records: no "Records updated" signal
        // TODO(r3-records-core): count distances whose current record changed during the pass
        _progress.value = if (done == 0) BackfillState.Idle else BackfillState.Done(changedDistances = 0)
        report(paused = false)
    }

    private enum class Outcome { COMPUTED, UNREADABLE, SKIPPED }

    private suspend fun recompute(id: Long): Outcome {
        val row = try {
            database.workoutDao().getMetricsSource(id) ?: return Outcome.SKIPPED // deleted meanwhile
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // Any other read error (closed database, locked file) leaves the row for a later pass
            if (!isRowTooBig(e, Build.VERSION.SDK_INT)) throw e
            return markUnreadable(id, e)
        } catch (e: OutOfMemoryError) {
            return markUnreadable(id, e)
        }
        val metrics = try {
            withContext(Dispatchers.Default) {
                derive(DerivationInput(row.trackData, row.type, row.duration))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A row the derivation fails on would stop the pass before every older row, forever
            return markUnreadable(id, e)
        } catch (e: OutOfMemoryError) {
            return markUnreadable(id, e)
        }
        return if (write(id, metrics)) Outcome.COMPUTED else Outcome.SKIPPED
    }

    /** Marks the row computed with nothing derived, so it is never retried. */
    private suspend fun markUnreadable(id: Long, error: Throwable): Outcome {
        Log.w(TAG, "Workout $id unreadable, marked computed without metrics", error)
        return if (write(id, Derived())) Outcome.UNREADABLE else Outcome.SKIPPED
    }

    /** False when an edit saved the row meanwhile: its metrics and efforts stay. */
    private suspend fun write(id: Long, metrics: Derived): Boolean = database.withTransaction {
        val updated = database.workoutDao().updateMetricsIfOlder(
            id = id,
            version = version,
            elevationGain = metrics.elevationGain,
            elevationLoss = metrics.elevationLoss,
            elevationSource = metrics.elevationSource,
            avgCadence = metrics.avgCadence,
            routePreview = metrics.routePreview
        )
        if (updated > 0) {
            database.bestEffortDao().replaceForWorkout(id, metrics.efforts.map { it.toBestEffort(id) })
        }
        updated > 0
    }

    companion object {
        private const val TAG = "MetricsBackfill"
        private const val BATCH_SIZE = 50
        private const val WORKOUT_POLL_MS = 60_000L

        /** Native message of a row that does not fit the cursor window before API 28. */
        private const val CURSOR_ROW_UNREADABLE = "Couldn't read row"

        /**
         * True when reading the row failed because it is too big for the cursor window: from API
         * 28 [SQLiteBlobTooBigException], before it an [IllegalStateException] "Couldn't read
         * row …" from the cursor window. Never a cancellation, which is an IllegalStateException too.
         */
        internal fun isRowTooBig(e: Throwable, sdkInt: Int): Boolean = when {
            e is CancellationException -> false
            sdkInt >= Build.VERSION_CODES.P -> e is SQLiteBlobTooBigException
            else -> e is IllegalStateException && e.message?.startsWith(CURSOR_ROW_UNREADABLE) == true
        }
    }
}
