package com.myrunsync.config

import android.content.Context
import com.myrunsync.BuildConfig

data class SupabaseConfig(
    val url: String,
    val anonKey: String
) {
    fun isComplete(): Boolean = url.isNotBlank() && anonKey.isNotBlank()
}

/**
 * Pure normalization and validation for Supabase credentials entered in-app.
 */
object SupabaseConfigValidator {
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
}

/**
 * Persists user-provided Supabase credentials in private device SharedPreferences.
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

        val resolvedUrl = SupabaseConfigValidator.normalizeUrl(
            if (!savedUrl.isNullOrBlank()) savedUrl else fallbackUrl
        )
        val resolvedAnonKey = SupabaseConfigValidator.normalizeAnonKey(
            if (!savedAnonKey.isNullOrBlank()) savedAnonKey else fallbackAnonKey
        )
        return SupabaseConfig(url = resolvedUrl, anonKey = resolvedAnonKey)
    }

    fun save(url: String, anonKey: String): SupabaseConfig {
        val normalized = SupabaseConfig(
            url = SupabaseConfigValidator.normalizeUrl(url),
            anonKey = SupabaseConfigValidator.normalizeAnonKey(anonKey)
        )
        prefs.edit()
            .putString(KEY_URL, normalized.url)
            .putString(KEY_ANON_KEY, normalized.anonKey)
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
    }
}
