package com.myrunsync.health

import com.myrunsync.model.RawRunningTelemetry
import java.time.Instant

/**
 * Common abstraction for reading running/walking workout telemetry from Health Connect
 * or directly from Samsung Health Data SDK.
 */
interface RunningTelemetryReader {
    suspend fun readRunningSessions(
        startTime: Instant,
        endTime: Instant
    ): List<RawRunningTelemetry>
}
