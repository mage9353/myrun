package com.myrunsync.sync

import com.myrunsync.domain.RunningTelemetryAggregator
import com.myrunsync.health.HealthConnectRunningReader
import com.myrunsync.model.HeartRateZone2Config
import com.myrunsync.model.RunningLogDto
import com.myrunsync.supabase.SupabaseRunningRepository
import java.time.Instant

data class SyncResult(
    val sessionsFound: Int,
    val sessionsSynced: Int,
    val syncedRecords: List<RunningLogDto>
)

/**
 * Coordinates reading running sessions from Health Connect, aggregating 1-minute buckets,
 * and upserting the results into Supabase `public.running_logs`.
 */
class RunningSyncManager(
    private val healthReader: HealthConnectRunningReader,
    private val supabaseRepository: SupabaseRunningRepository
) {
    suspend fun syncRuns(
        startTime: Instant,
        endTime: Instant = Instant.now(),
        zone2Config: HeartRateZone2Config
    ): SyncResult {
        val rawSessions = healthReader.readRunningSessions(startTime = startTime, endTime = endTime)
        if (rawSessions.isEmpty()) {
            return SyncResult(sessionsFound = 0, sessionsSynced = 0, syncedRecords = emptyList())
        }

        val now = Instant.now()
        val dtos = rawSessions.map { raw ->
            RunningTelemetryAggregator.aggregate(
                telemetry = raw,
                zone2Config = zone2Config,
                syncedAt = now
            )
        }

        supabaseRepository.upsertRunningLogs(dtos)
        return SyncResult(
            sessionsFound = rawSessions.size,
            sessionsSynced = dtos.size,
            syncedRecords = dtos
        )
    }
}
