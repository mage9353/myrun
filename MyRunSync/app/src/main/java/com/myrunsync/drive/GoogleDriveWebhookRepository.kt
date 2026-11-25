package com.myrunsync.drive

import com.myrunsync.model.HeartRateZone2Config
import com.myrunsync.model.RunningLogDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

interface DriveSyncGateway {
    suspend fun uploadRunningLogs(
        logs: List<RunningLogDto>,
        zone2Config: HeartRateZone2Config,
        syncedAt: Instant
    )
}

@Serializable
data class GoogleDriveSyncPayload(
    @SerialName("secret") val secret: String = "",
    @SerialName("zone2_min_bpm") val zone2MinBpm: Int,
    @SerialName("zone2_max_bpm") val zone2MaxBpm: Int,
    @SerialName("synced_at") val syncedAt: String,
    @SerialName("logs") val logs: List<RunningLogDto>
)

/**
 * Posts synced running logs to a user-deployed Google Apps Script Web App so that
 * Google Drive (Sheets history + compact Gemini Doc) stays synchronized alongside Supabase.
 */
class GoogleDriveWebhookRepository(
    private val webhookUrl: String,
    private val secret: String = ""
) : DriveSyncGateway {

    override suspend fun uploadRunningLogs(
        logs: List<RunningLogDto>,
        zone2Config: HeartRateZone2Config,
        syncedAt: Instant
    ) {
        if (logs.isEmpty()) return
        val targetUrl = webhookUrl.trim()
        require(targetUrl.isNotBlank()) { "Google Drive webhook URL must not be blank" }

        val bodyJson = encodePayload(
            logs = logs,
            zone2Config = zone2Config,
            syncedAt = syncedAt,
            secret = secret
        )

        withContext(Dispatchers.IO) {
            postToWebhook(targetUrl, bodyJson)
        }
    }

    private fun postToWebhook(targetUrl: String, bodyJson: String) {
        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            // Apps Script web apps execute doPost() and respond with 302 -> Location; follow with GET.
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }

        val (statusCode, responseBody) = try {
            conn.outputStream.use { os ->
                os.write(bodyJson.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            if (code in 301..303) {
                val redirectUrl = conn.getHeaderField("Location")
                if (redirectUrl.isNullOrBlank()) {
                    code to readStreamText(conn)
                } else {
                    followRedirectWithGet(redirectUrl)
                }
            } else {
                code to readStreamText(conn)
            }
        } finally {
            conn.disconnect()
        }

        if (statusCode !in 200..299) {
            throw IllegalStateException("Google Drive 웹훅 HTTP 오류 ($statusCode)")
        }

        parseAndValidateResponse(responseBody)
    }

    private fun followRedirectWithGet(redirectUrl: String): Pair<Int, String> {
        var currentUrl = redirectUrl
        repeat(MAX_REDIRECTS) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code in 301..303 || code == 307 || code == 308) {
                    val next = conn.getHeaderField("Location")
                    if (next.isNullOrBlank()) {
                        return code to readStreamText(conn)
                    }
                    currentUrl = next
                } else {
                    return code to readStreamText(conn)
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("Google Drive 웹훅 리다이렉트 횟수를 초과했습니다.")
    }

    private fun readStreamText(conn: HttpURLConnection): String {
        val stream = runCatching { conn.inputStream }.getOrNull() ?: conn.errorStream ?: return ""
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 5

        private val json = Json {
            encodeDefaults = false
            ignoreUnknownKeys = true
        }

        internal fun encodePayload(
            logs: List<RunningLogDto>,
            zone2Config: HeartRateZone2Config,
            syncedAt: Instant,
            secret: String
        ): String {
            val payload = GoogleDriveSyncPayload(
                secret = secret.trim(),
                zone2MinBpm = zone2Config.minBpm,
                zone2MaxBpm = zone2Config.maxBpm,
                syncedAt = syncedAt.toString(),
                logs = logs
            )
            return json.encodeToString(payload)
        }

        internal fun parseAndValidateResponse(responseBody: String) {
            val trimmed = responseBody.trim()
            if (trimmed.isEmpty()) {
                throw IllegalStateException("Google Drive 웹훅 응답이 비어 있습니다.")
            }
            if (trimmed.startsWith("<")) {
                throw IllegalStateException("Google Apps Script 웹 앱 액세스 권한을 '모든 사용자'로 배포했는지 확인해 주세요.")
            }
            val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
                ?: throw IllegalStateException("Google Drive 웹훅 JSON 응답을 해석할 수 없습니다.")
            val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull
            if (ok != true) {
                val errMsg = obj["error"]?.jsonPrimitive?.content ?: "알 수 없는 오류"
                throw IllegalStateException("Google Drive 동기화 실패: $errMsg")
            }
        }
    }
}
