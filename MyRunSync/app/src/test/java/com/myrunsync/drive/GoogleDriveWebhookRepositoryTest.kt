package com.myrunsync.drive

import com.myrunsync.model.HeartRateZone2Config
import com.myrunsync.model.RunningLogDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class GoogleDriveWebhookRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `encodePayload serializes secret, zone2 bounds, syncedAt, and logs`() {
        val dto = RunningLogDto(
            hcSessionId = "hc-drive-001",
            runDate = "2026-11-25T05:00:00Z",
            endedAt = "2026-11-25T05:30:00Z",
            workoutType = "야외",
            exerciseType = "running",
            hasGps = true,
            distanceKm = 5.0,
            distanceMeters = 5000.0,
            durationSec = 1800,
            avgPaceSec = 360,
            avgHr = 132,
            zone2MinBpm = 114,
            zone2MaxBpm = 133,
            zone2Ratio = 0.85,
            zone2Pct = 85.0,
            avgCadence = 172,
            timeseries1Min = emptyList(),
            syncedAt = "2026-11-25T05:31:00Z"
        )

        val raw = GoogleDriveWebhookRepository.encodePayload(
            logs = listOf(dto),
            zone2Config = HeartRateZone2Config(minBpm = 114, maxBpm = 133),
            syncedAt = Instant.parse("2026-11-25T05:31:00Z"),
            secret = "  my-secret  "
        )

        val obj = json.parseToJsonElement(raw).jsonObject
        assertEquals("my-secret", obj["secret"]?.jsonPrimitive?.content)
        assertEquals("114", obj["zone2_min_bpm"]?.jsonPrimitive?.content)
        assertEquals("133", obj["zone2_max_bpm"]?.jsonPrimitive?.content)
        assertEquals("2026-11-25T05:31:00Z", obj["synced_at"]?.jsonPrimitive?.content)
        val logsArr = obj["logs"]?.jsonArray
        assertEquals(1, logsArr?.size)
        assertEquals("hc-drive-001", logsArr?.get(0)?.jsonObject?.get("hc_session_id")?.jsonPrimitive?.content)
    }

    @Test
    fun `parseAndValidateResponse succeeds when ok is true`() {
        GoogleDriveWebhookRepository.parseAndValidateResponse("""{"ok":true,"upserted":3,"total":12}""")
    }

    @Test
    fun `parseAndValidateResponse throws clear errors on html or failure json`() {
        try {
            GoogleDriveWebhookRepository.parseAndValidateResponse("<html><body>Sign in</body></html>")
            fail("Expected exception for HTML response")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("모든 사용자"))
        }

        try {
            GoogleDriveWebhookRepository.parseAndValidateResponse("""{"ok":false,"error":"Unauthorized"}""")
            fail("Expected exception for ok=false JSON")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Unauthorized"))
        }
    }
}
