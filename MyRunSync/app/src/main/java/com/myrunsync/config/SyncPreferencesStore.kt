package com.myrunsync.config

import android.content.Context
import com.myrunsync.BuildConfig

enum class SyncDataSource(
    val prefValue: String,
    val label: String
) {
    SAMSUNG_HEALTH("samsung_health", "Samsung Health (직접 연동)"),
    HEALTH_CONNECT("health_connect", "Health Connect");

    companion object {
        fun fromPrefValue(raw: String?, samsungSdkEnabled: Boolean): SyncDataSource {
            val matched = entries.firstOrNull { it.prefValue == raw }
            if (matched == SAMSUNG_HEALTH && !samsungSdkEnabled) {
                return HEALTH_CONNECT
            }
            return matched ?: if (samsungSdkEnabled) SAMSUNG_HEALTH else HEALTH_CONNECT
        }
    }
}

data class SyncIntervalOption(
    val minutes: Long,
    val label: String
)

/**
 * Pure schedule options and clamping rules for WorkManager periodic sync.
 */
object SyncScheduleConfig {
    const val MIN_INTERVAL_MINUTES: Long = 15L
    const val DEFAULT_INTERVAL_MINUTES: Long = 60L

    val INTERVAL_OPTIONS: List<SyncIntervalOption> = listOf(
        SyncIntervalOption(15L, "15분"),
        SyncIntervalOption(30L, "30분"),
        SyncIntervalOption(60L, "1시간"),
        SyncIntervalOption(180L, "3시간"),
        SyncIntervalOption(360L, "6시간"),
        SyncIntervalOption(720L, "12시간"),
        SyncIntervalOption(1440L, "24시간")
    )

    fun clampIntervalMinutes(minutes: Long): Long = minutes.coerceAtLeast(MIN_INTERVAL_MINUTES)

    fun indexForMinutes(minutes: Long): Int {
        val directIndex = INTERVAL_OPTIONS.indexOfFirst { it.minutes == minutes }
        if (directIndex >= 0) return directIndex
        return INTERVAL_OPTIONS.indexOfFirst { it.minutes == DEFAULT_INTERVAL_MINUTES }
            .coerceAtLeast(0)
    }

    fun labelForMinutes(minutes: Long): String {
        val clamped = clampIntervalMinutes(minutes)
        return INTERVAL_OPTIONS.firstOrNull { it.minutes == clamped }?.label ?: "${clamped}분"
    }
}

data class SyncSettings(
    val dataSource: SyncDataSource,
    val autoSyncOnOpen: Boolean,
    val backgroundAutoSyncEnabled: Boolean,
    val backgroundIntervalMinutes: Long,
    val lastSyncSummary: String?
)

/**
 * Persists data source, auto-sync preferences, and latest sync status in private SharedPreferences.
 */
class SyncPreferencesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): SyncSettings {
        val interval = SyncScheduleConfig.clampIntervalMinutes(
            prefs.getLong(KEY_BG_INTERVAL_MINUTES, SyncScheduleConfig.DEFAULT_INTERVAL_MINUTES)
        )
        val dataSource = SyncDataSource.fromPrefValue(
            raw = prefs.getString(KEY_DATA_SOURCE, null),
            samsungSdkEnabled = BuildConfig.SAMSUNG_HEALTH_SDK_ENABLED
        )
        return SyncSettings(
            dataSource = dataSource,
            autoSyncOnOpen = prefs.getBoolean(KEY_AUTO_SYNC_ON_OPEN, false),
            backgroundAutoSyncEnabled = prefs.getBoolean(KEY_BG_AUTO_SYNC_ENABLED, false),
            backgroundIntervalMinutes = interval,
            lastSyncSummary = prefs.getString(KEY_LAST_SYNC_SUMMARY, null)?.takeIf { it.isNotBlank() }
        )
    }

    fun setDataSource(source: SyncDataSource) {
        prefs.edit().putString(KEY_DATA_SOURCE, source.prefValue).apply()
    }

    fun setAutoSyncOnOpen(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC_ON_OPEN, enabled).apply()
    }

    fun setBackgroundAutoSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BG_AUTO_SYNC_ENABLED, enabled).apply()
    }

    fun setBackgroundIntervalMinutes(minutes: Long) {
        val clamped = SyncScheduleConfig.clampIntervalMinutes(minutes)
        prefs.edit().putLong(KEY_BG_INTERVAL_MINUTES, clamped).apply()
    }

    fun setLastSyncSummary(summary: String) {
        prefs.edit().putString(KEY_LAST_SYNC_SUMMARY, summary).apply()
    }

    companion object {
        private const val PREFS_NAME = "myrunsync_settings"
        private const val KEY_DATA_SOURCE = "sync_data_source"
        private const val KEY_AUTO_SYNC_ON_OPEN = "auto_sync_on_open"
        private const val KEY_BG_AUTO_SYNC_ENABLED = "bg_auto_sync_enabled"
        private const val KEY_BG_INTERVAL_MINUTES = "bg_sync_interval_minutes"
        private const val KEY_LAST_SYNC_SUMMARY = "last_sync_summary"
    }
}
