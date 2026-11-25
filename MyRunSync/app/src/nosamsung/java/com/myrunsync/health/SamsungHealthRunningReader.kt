package com.myrunsync.health

import android.app.Activity
import android.content.Context
import com.myrunsync.model.RawRunningTelemetry
import java.time.Instant

/**
 * No-op stub compiled when Samsung Health Data SDK AAR is not present (e.g., public CI without SDK).
 */
class SamsungHealthRunningReader(
    @Suppress("UNUSED_PARAMETER") context: Context
) : RunningTelemetryReader {

    suspend fun hasRequiredPermissions(): Boolean = false

    suspend fun hasLocationPermission(): Boolean = false

    suspend fun hasHeartRatePermission(): Boolean = false

    suspend fun requestPermissions(@Suppress("UNUSED_PARAMETER") activity: Activity): Set<String> =
        emptySet()

    override suspend fun readRunningSessions(
        startTime: Instant,
        endTime: Instant
    ): List<RawRunningTelemetry> = emptyList()

    companion object {
        fun isSupported(): Boolean = false

        fun isSamsungHealthInstalled(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

        fun formatErrorMessage(error: Throwable): String =
            error.localizedMessage ?: error::class.java.simpleName
    }
}
