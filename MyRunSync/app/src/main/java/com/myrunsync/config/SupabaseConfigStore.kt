package com.myrunsync.config

import android.content.Context
import com.myrunsync.BuildConfig

data class SupabaseConfig(
    val url: String,
    val anonKey: String,
    val zone2MinBpm: Int = DEFAULT_ZONE2_MIN_BPM,
    val zone2MaxBpm: Int = DEFAULT_ZONE2_MAX_BPM
) {
    fun isComplete(): Boolean = url.isNotBlank() && anonKey.isNotBlank()

    companion object {
        const val DEFAULT_ZONE2_MIN_BPM = 114
        const val DEFAULT_ZONE2_MAX_BPM = 133
    }
}

/**
 * Pure normalization and validation for Supabase credentials and Zone 2 HR entered in-app.
 */
object SupabaseConfigValidator {
    const val MIN_VALID_HR_BPM = 40
    const val MAX_VALID_HR_BPM = 240

    fun normalizeUrl(raw: String): String = raw.trim().trimEnd('/')

    fun normalizeAnonKey(raw: String): String = raw.trim()

    fun validate(url: String, anonKey: String): String? {
        val normalizedUrl = normalizeUrl(url)
        val normalizedKey = normalizeAnonKey(anonKey)
        if (normalizedUrl.isBlank()) {
            return "Supabase URL을 입력해 주세요."
        }
        if (!normalizedUrl.startsWith("https://") && !normalizedUrl.startsWith("http://")) {
            return "Supabase URL은 https:// 로 시작해야 합니다."
        }
        if (normalizedKey.isBlank()) {
            return "Supabase Anon Key를 입력해 주세요."
        }
        return null
    }

    fun validateZone2(minBpmRaw: String, maxBpmRaw: String): String? {
        val minBpm = minBpmRaw.trim().toIntOrNull()
            ?: return "Zone 2 최소 심박수(bpm)를 숫자로 입력해 주세요."
        val maxBpm = maxBpmRaw.trim().toIntOrNull()
            ?: return "Zone 2 최대 심박수(bpm)를 숫자로 입력해 주세요."
        if (minBpm !in MIN_VALID_HR_BPM..MAX_VALID_HR_BPM || maxBpm !in MIN_VALID_HR_BPM..MAX_VALID_HR_BPM) {
            return "Zone 2 심박수는 ${MIN_VALID_HR_BPM}~${MAX_VALID_HR_BPM} bpm 범위여야 합니다."
        }
        if (minBpm > maxBpm) {
            return "Zone 2 최소 심박수는 최대 심박수보다 클 수 없습니다."
        }
        return null
    }

    fun parseZone2(minBpmRaw: String, maxBpmRaw: String): Pair<Int, Int>? {
        if (validateZone2(minBpmRaw, maxBpmRaw) != null) return null
        return minBpmRaw.trim().toInt() to maxBpmRaw.trim().toInt()
    }
}

/**
 * Persists user-provided Supabase credentials and Zone 2 HR bounds in private device SharedPreferences.
 */
class SupabaseConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun load(
        fallbackUrl: String = BuildConfig.SUPABASE_URL,
        fallbackAnonKey: String = BuildConfig.SUPABASE_ANON_KEY
    ): SupabaseConfig {
        val savedUrl = prefs.getString(KEY_URL, null)
        val savedAnonKey = prefs.getString(KEY_ANON_KEY, null)
        val savedMinBpm = prefs.getInt(KEY_ZONE2_MIN_BPM, SupabaseConfig.DEFAULT_ZONE2_MIN_BPM)
        val savedMaxBpm = prefs.getInt(KEY_ZONE2_MAX_BPM, SupabaseConfig.DEFAULT_ZONE2_MAX_BPM)
        val validRange = savedMinBpm in SupabaseConfigValidator.MIN_VALID_HR_BPM..SupabaseConfigValidator.MAX_VALID_HR_BPM &&
            savedMaxBpm in savedMinBpm..SupabaseConfigValidator.MAX_VALID_HR_BPM

        val resolvedUrl = SupabaseConfigValidator.normalizeUrl(
            if (!savedUrl.isNullOrBlank()) savedUrl else fallbackUrl
        )
        val resolvedAnonKey = SupabaseConfigValidator.normalizeAnonKey(
            if (!savedAnonKey.isNullOrBlank()) savedAnonKey else fallbackAnonKey
        )
        return SupabaseConfig(
            url = resolvedUrl,
            anonKey = resolvedAnonKey,
            zone2MinBpm = if (validRange) savedMinBpm else SupabaseConfig.DEFAULT_ZONE2_MIN_BPM,
            zone2MaxBpm = if (validRange) savedMaxBpm else SupabaseConfig.DEFAULT_ZONE2_MAX_BPM
        )
    }

    fun save(
        url: String,
        anonKey: String,
        zone2MinBpm: Int = SupabaseConfig.DEFAULT_ZONE2_MIN_BPM,
        zone2MaxBpm: Int = SupabaseConfig.DEFAULT_ZONE2_MAX_BPM
    ): SupabaseConfig {
        val normalized = SupabaseConfig(
            url = SupabaseConfigValidator.normalizeUrl(url),
            anonKey = SupabaseConfigValidator.normalizeAnonKey(anonKey),
            zone2MinBpm = zone2MinBpm,
            zone2MaxBpm = zone2MaxBpm
        )
        prefs.edit()
            .putString(KEY_URL, normalized.url)
            .putString(KEY_ANON_KEY, normalized.anonKey)
            .putInt(KEY_ZONE2_MIN_BPM, normalized.zone2MinBpm)
            .putInt(KEY_ZONE2_MAX_BPM, normalized.zone2MaxBpm)
            .apply()
        return normalized
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "myrunsync_settings"
        private const val KEY_URL = "supabase_url"
        private const val KEY_ANON_KEY = "supabase_anon_key"
        private const val KEY_ZONE2_MIN_BPM = "zone2_min_bpm"
        private const val KEY_ZONE2_MAX_BPM = "zone2_max_bpm"
    }
}
