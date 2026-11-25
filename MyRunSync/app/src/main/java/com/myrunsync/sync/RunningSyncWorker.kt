package com.myrunsync.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.myrunsync.config.SupabaseConfigStore
import com.myrunsync.config.SyncDataSource
import com.myrunsync.config.SyncPreferencesStore
import com.myrunsync.health.HealthConnectRunningReader
import com.myrunsync.health.RunningTelemetryReader
import com.myrunsync.health.SamsungHealthRunningReader
import com.myrunsync.model.HeartRateZone2Config
import com.myrunsync.supabase.SupabaseRunningRepository
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Periodic WorkManager worker that syncs recent workouts from Samsung Health (direct SDK)
 * or Health Connect to Supabase in the background.
 */
class RunningSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val syncPrefs = SyncPreferencesStore(applicationContext)
        val syncSettings = syncPrefs.load()
        val config = SupabaseConfigStore(applicationContext).load()
        if (!config.isComplete()) {
            syncPrefs.setLastSyncSummary("Background sync skipped: Supabase settings not configured.")
            return Result.success()
        }

        val timeStr = TIME_FORMATTER.format(Instant.now())
        val reader: RunningTelemetryReader
        val sourceTag: String

        if (syncSettings.dataSource == SyncDataSource.SAMSUNG_HEALTH && SamsungHealthRunningReader.isSupported()) {
            if (!SamsungHealthRunningReader.isSamsungHealthInstalled(applicationContext)) {
                syncPrefs.setLastSyncSummary("$timeStr · Background skipped: Samsung Health not installed.")
                return Result.success()
            }
            val samsungReader = SamsungHealthRunningReader(applicationContext)
            val hasPerms = try {
                samsungReader.hasRequiredPermissions()
            } catch (e: Exception) {
                syncPrefs.setLastSyncSummary(
                    "$timeStr · Background failed: ${SamsungHealthRunningReader.formatErrorMessage(e)}"
                )
                return if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
            if (!hasPerms) {
                syncPrefs.setLastSyncSummary("$timeStr · Background skipped: Samsung Health permission missing.")
                return Result.success()
            }
            reader = samsungReader
            sourceTag = "Samsung Health BG"
        } else {
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
            reader = healthReader
            sourceTag = "Background"
        }

        val supabaseClient = SupabaseRunningRepository.createSupabaseClient(
            supabaseUrl = config.url,
            supabaseAnonKey = config.anonKey
        )
        return try {
            val repository = SupabaseRunningRepository(supabaseClient)
            val syncManager = RunningSyncManager(reader, repository)
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
                "$timeStr · $sourceTag: synced ${result.sessionsSynced} run(s)"
            } else {
                "$timeStr · $sourceTag: no runs in last 30 days"
            }
            syncPrefs.setLastSyncSummary(summary)
            Result.success()
        } catch (e: Exception) {
            val message = if (syncSettings.dataSource == SyncDataSource.SAMSUNG_HEALTH) {
                SamsungHealthRunningReader.formatErrorMessage(e)
            } else {
                e.localizedMessage ?: e::class.java.simpleName
            }
            syncPrefs.setLastSyncSummary("$timeStr · $sourceTag failed: $message")
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
