package com.myrunsync.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.myrunsync.config.SyncPreferencesStore
import com.myrunsync.config.SyncScheduleConfig
import java.util.concurrent.TimeUnit

/**
 * Manages scheduling and cancellation of the periodic background running sync job.
 */
object BackgroundSyncScheduler {
    const val UNIQUE_WORK_NAME = "myrunsync_periodic_background_sync"

    fun schedule(context: Context, intervalMinutes: Long) {
        val clampedMinutes = SyncScheduleConfig.clampIntervalMinutes(intervalMinutes)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<RunningSyncWorker>(
            clampedMinutes,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    fun applyFromPreferences(context: Context) {
        val settings = SyncPreferencesStore(context).load()
        if (settings.backgroundAutoSyncEnabled) {
            schedule(context, settings.backgroundIntervalMinutes)
        } else {
            cancel(context)
        }
    }
}
