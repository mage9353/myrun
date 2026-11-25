package com.myrunsync.sync

import com.myrunsync.domain.RunningTelemetryAggregator
import com.myrunsync.drive.DriveSyncGateway
import com.myrunsync.health.RunningTelemetryReader
import com.myrunsync.model.HeartRateZone2Config
import com.myrunsync.model.RunningLogDto
import com.myrunsync.supabase.SupabaseRunningRepository
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

data class SyncResult(
    val sessionsFound: Int,
    val sessionsSynced: Int,
    val syncedRecords: List<RunningLogDto>,
    val driveSynced: Boolean = false,
    val driveError: String? = null
)

/**
 * Coordinates reading running sessions from a telemetry source (Samsung Health or Health Connect),
 * aggregating 1-minute buckets, upserting the results into Supabase `public.running_logs`,
 * and optionally mirroring them to Google Drive via Apps Script webhook.
 */
class RunningSyncManager(
    private val healthReader: RunningTelemetryReader,
    private val supabaseRepository: SupabaseRunningRepository,
    private val driveGateway: DriveSyncGateway? = null
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

        var driveSynced = false
        var driveError: String? = null
        val gateway = driveGateway
        if (gateway != null) {
            try {
                gateway.uploadRunningLogs(
                    logs = dtos,
                    zone2Config = zone2Config,
                    syncedAt = now
                )
                driveSynced = true
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Exception) {
                driveError = err.localizedMessage ?: err::class.java.simpleName
            }
        }

        return SyncResult(
            sessionsFound = rawSessions.size,
            sessionsSynced = dtos.size,
            syncedRecords = dtos,
            driveSynced = driveSynced,
            driveError = driveError
        )
    }
}
