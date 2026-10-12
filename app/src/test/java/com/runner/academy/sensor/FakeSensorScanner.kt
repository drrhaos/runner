package com.runner.academy.sensor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A [SensorScanner] driven by the test. Each [scan] plays the next of [scans] (or a quiet
 * 20 s scan finding nothing); [probe] waits for [probeResult].
 */
class FakeSensorScanner : SensorScanner {

    var waitMs = 0L
    val scans = ArrayDeque<Flow<List<FoundSensor>>>()
    var scansStarted = 0
        private set

    val probed = mutableListOf<String>()
    var probeResult = CompletableDeferred<ProbeResult>()

    override fun waitBeforeScanMs(): Long = waitMs

    override fun scan(): Flow<List<FoundSensor>> {
        scansStarted++
        return scans.removeFirstOrNull() ?: finding()
    }

    override suspend fun probe(address: String): ProbeResult {
        probed += address
        return probeResult.await()
    }

    companion object {
        /** A full scan: nothing at first, then [found] one by one, 1 s apart; done after 20 s. */
        fun finding(vararg found: FoundSensor): Flow<List<FoundSensor>> = flow {
            var list = emptyList<FoundSensor>()
            emit(list)
            for (sensor in found) {
                delay(1_000L)
                list = list.withFound(sensor)
                emit(list)
            }
            delay(SensorScanner.SCAN_DURATION_MS - found.size * 1_000L)
        }

        fun failing(failure: ScanException): Flow<List<FoundSensor>> = flow { throw failure }
    }
}
