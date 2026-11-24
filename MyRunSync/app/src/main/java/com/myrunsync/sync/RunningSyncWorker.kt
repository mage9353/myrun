package com.myrunsync.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.myrunsync.config.SupabaseConfigStore
import com.myrunsync.config.SyncPreferencesStore
import com.myrunsync.health.HealthConnectRunningReader
import com.myrunsync.model.HeartRateZone2Config
import com.myrunsync.supabase.SupabaseRunningRepository
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Periodic WorkManager worker that syncs recent Health Connect runs to Supabase in the background.
 */
class RunningSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val syncPrefs = SyncPreferencesStore(applicationContext)
        val config = SupabaseConfigStore(applicationContext).load()
        if (!config.isComplete()) {
            syncPrefs.setLastSyncSummary("Background sync skipped: Supabase settings not configured.")
            return Result.success()
        }

        if (!HealthConnectRunningReader.isAvailable(applicationContext)) {
            return Result.success()
        }

        val healthClient = HealthConnectClient.getOrCreate(applicationContext)
        val healthReader = HealthConnectRunningReader(healthClient)
        if (!healthReader.hasAllPermissions() || !healthReader.hasBackgroundPermission()) {
            syncPrefs.setLastSyncSummary(
                "Background sync skipped: Health Connect background permission missing."
            )
            return Result.success()
        }

        val timeStr = TIME_FORMATTER.format(Instant.now())
        val supabaseClient = SupabaseRunningRepository.createSupabaseClient(
            supabaseUrl = config.url,
            supabaseAnonKey = config.anonKey
        )
        return try {
            val repository = SupabaseRunningRepository(supabaseClient)
            val syncManager = RunningSyncManager(healthReader, repository)
            val endTime = Instant.now()
            val startTime = endTime.minus(Duration.ofDays(30))
            val zone2 = HeartRateZone2Config(
                minBpm = config.zone2MinBpm,
                maxBpm = config.zone2MaxBpm
            )

            val result = syncManager.syncRuns(
                startTime = startTime,
                endTime = endTime,
                zone2Config = zone2
            )

            val summary = if (result.sessionsSynced > 0) {
                "$timeStr · Background: synced ${result.sessionsSynced} run(s)"
            } else {
                "$timeStr · Background: no runs in last 30 days"
            }
            syncPrefs.setLastSyncSummary(summary)
            Result.success()
        } catch (e: Exception) {
            val message = e.localizedMessage ?: e::class.java.simpleName
            syncPrefs.setLastSyncSummary("$timeStr · Background failed: $message")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            runCatching { supabaseClient.close() }
        }
    }

    companion object {
        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
    }
}
