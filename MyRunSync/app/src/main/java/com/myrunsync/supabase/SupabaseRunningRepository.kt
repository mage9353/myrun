package com.myrunsync.supabase

import com.myrunsync.model.RunningLogDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.serialization.json.Json

/**
 * Factory and repository for upserting running logs into Supabase `public.running_logs`.
 */
class SupabaseRunningRepository(
    private val supabaseClient: SupabaseClient
) {
    suspend fun upsertRunningLogs(logs: List<RunningLogDto>) {
        if (logs.isEmpty()) return
        supabaseClient.from(TABLE_RUNNING_LOGS).upsert(logs) {
            onConflict = "hc_session_id"
        }
    }

    companion object {
        private const val TABLE_RUNNING_LOGS = "running_logs"

        fun createSupabaseClient(
            supabaseUrl: String,
            supabaseAnonKey: String
        ): SupabaseClient {
            require(supabaseUrl.isNotBlank()) { "SUPABASE_URL must not be blank" }
            require(supabaseAnonKey.isNotBlank()) { "SUPABASE_ANON_KEY must not be blank" }

            return createSupabaseClient(
                supabaseUrl = supabaseUrl,
                supabaseKey = supabaseAnonKey
            ) {
                install(Postgrest)
                defaultSerializer = KotlinXSerializer(
                    Json {
                        encodeDefaults = false
                        ignoreUnknownKeys = true
                    }
                )
            }
        }
    }
}
