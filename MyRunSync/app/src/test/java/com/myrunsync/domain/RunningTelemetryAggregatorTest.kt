package com.myrunsync.domain

import com.myrunsync.health.HealthConnectRunningReader
import com.myrunsync.model.CadenceSample
import com.myrunsync.model.DistanceSegment
import com.myrunsync.model.HeartRateZone2Config
import com.myrunsync.model.HrSample
import com.myrunsync.model.RawRunningTelemetry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RunningTelemetryAggregatorTest {

    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    @Test
    fun aggregate_computesRunningLogsSummaryAndOneBasedBucketsCorrectly() {
        val start = Instant.parse("2026-11-23T15:00:00Z")
        val end = start.plusSeconds(150) // 2 min 30 sec -> 3 buckets (60s, 60s, 30s)
        val zone2 = HeartRateZone2Config(minBpm = 125, maxBpm = 145)

        val hrSamples = listOf(
            // Bucket 1: 0s -> 30s (130, Zone 2), 30s -> 60s (140, Zone 2) => 100% Zone 2
            HrSample(start.plusSeconds(0), 130),
            HrSample(start.plusSeconds(30), 140),
            // Bucket 2: 60s -> 90s (140, Zone 2), 90s -> 120s (155, above Zone 2) => 50% Zone 2
            HrSample(start.plusSeconds(60), 140),
            HrSample(start.plusSeconds(90), 155),
            // Bucket 3 (30s): 120s -> 150s (160, above Zone 2) => 0% Zone 2
            HrSample(start.plusSeconds(120), 160)
        )

        val cadenceSamples = listOf(
            CadenceSample(start.plusSeconds(15), 170.0),
            CadenceSample(start.plusSeconds(45), 174.0),
            CadenceSample(start.plusSeconds(75), 180.0)
        )

        val distanceSegments = listOf(
            DistanceSegment(start, start.plusSeconds(90), 300.0),
            DistanceSegment(start.plusSeconds(90), start.plusSeconds(150), 300.0)
        )

        val raw = RawRunningTelemetry(
            hcSessionId = "hc-run-001",
            sourcePackage = "com.sec.android.app.shealth",
            title = "Morning Zone 2 Run",
            workoutType = "야외",
            startTime = start,
            endTime = end,
            aggregatedDistanceMeters = 600.0,
            totalSteps = 435L,
            heartRateSamples = hrSamples,
            cadenceSamples = cadenceSamples,
            distanceSegments = distanceSegments
        )

        val dto = RunningTelemetryAggregator.aggregate(
            telemetry = raw,
            zone2Config = zone2,
            syncedAt = end
        )

        assertEquals("hc-run-001", dto.hcSessionId)
        assertEquals("2026-11-23T15:00:00Z", dto.runDate)
        assertEquals("야외", dto.workoutType)
        assertEquals(150, dto.durationSec)
        assertEquals(0.60, dto.distanceKm, 0.001)
        assertEquals(600.0, dto.distanceMeters, 0.01)
        assertEquals(250, dto.avgPaceSec)
        assertEquals(145, dto.avgHr)
        assertEquals(160, dto.maxHr)
        assertEquals(130, dto.minHr)
        assertEquals(0.60, dto.zone2Ratio!!, 0.0001)
        assertEquals(60.0, dto.zone2Pct!!, 0.1)
        assertEquals(175, dto.avgCadence)

        assertEquals(3, dto.timeseries1Min.size)

        val b1 = dto.timeseries1Min[0]
        assertEquals(1, b1.minute)
        assertEquals(0, b1.offsetSec)
        assertEquals(135, b1.hr)
        assertEquals(140, b1.maxHr)
        assertEquals(130, b1.minHr)
        assertEquals(1.0, b1.zone2Ratio!!, 0.0001)
        assertEquals(172, b1.cadence)
        assertEquals(200.0, b1.distanceM, 0.01)
        assertEquals(300, b1.paceSec)

        val b2 = dto.timeseries1Min[1]
        assertEquals(2, b2.minute)
        assertEquals(60, b2.offsetSec)
        assertEquals(148, b2.hr)
        assertEquals(0.5, b2.zone2Ratio!!, 0.0001)
        assertEquals(180, b2.cadence)
        assertEquals(250.0, b2.distanceM, 0.01)
        assertEquals(240, b2.paceSec)

        val b3 = dto.timeseries1Min[2]
        assertEquals(3, b3.minute)
        assertEquals(120, b3.offsetSec)
        assertEquals(160, b3.hr)
        assertEquals(0.0, b3.zone2Ratio!!, 0.0001)
        assertNull(b3.cadence)
        assertEquals(150.0, b3.distanceM, 0.01)
        assertEquals(200, b3.paceSec)
    }

    @Test
    fun aggregate_fallsBackToTotalStepsForCadenceWhenCadenceSamplesMissing() {
        val start = Instant.parse("2026-11-24T07:00:00Z")
        val end = start.plusSeconds(120)

        val raw = RawRunningTelemetry(
            hcSessionId = "hc-run-002",
            sourcePackage = null,
            title = null,
            workoutType = "트레드밀",
            startTime = start,
            endTime = end,
            aggregatedDistanceMeters = 400.0,
            totalSteps = 340L,
            heartRateSamples = emptyList(),
            cadenceSamples = emptyList(),
            distanceSegments = emptyList()
        )

        val dto = RunningTelemetryAggregator.aggregate(
            telemetry = raw,
            zone2Config = HeartRateZone2Config.fromMaxHeartRate(190),
            syncedAt = end
        )

        assertEquals("트레드밀", dto.workoutType)
        assertEquals(170, dto.avgCadence)
        assertNull(dto.avgHr)
        assertNull(dto.zone2Ratio)
        assertNull(dto.zone2Pct)
        assertEquals(2, dto.timeseries1Min.size)
    }

    @Test
    fun aggregate_handlesZeroDurationAndEncodesUnifiedRunningLogsJsonKeys() {
        val start = Instant.parse("2026-11-24T08:00:00Z")
        val raw = RawRunningTelemetry(
            hcSessionId = "hc-run-empty",
            sourcePackage = "com.example.runner",
            title = null,
            workoutType = "야외",
            startTime = start,
            endTime = start,
            aggregatedDistanceMeters = null,
            totalSteps = null,
            heartRateSamples = emptyList(),
            cadenceSamples = emptyList(),
            distanceSegments = emptyList()
        )

        val dto = RunningTelemetryAggregator.aggregate(
            telemetry = raw,
            zone2Config = HeartRateZone2Config(120, 140),
            syncedAt = start
        )

        assertEquals(0, dto.durationSec)
        assertEquals(0.0, dto.distanceKm, 0.001)
        assertEquals(0.0, dto.distanceMeters, 0.001)
        assertNull(dto.avgPaceSec)
        assertNull(dto.avgHr)
        assertNull(dto.zone2Ratio)
        assertNull(dto.zone2Pct)
        assertNull(dto.avgCadence)
        assertTrue(dto.timeseries1Min.isEmpty())

        val encoded = json.encodeToString(dto)
        val obj = json.parseToJsonElement(encoded).jsonObject

        assertEquals("hc-run-empty", obj["hc_session_id"]?.jsonPrimitive?.content)
        assertEquals("2026-11-24T08:00:00Z", obj["run_date"]?.jsonPrimitive?.content)
        assertEquals("야외", obj["workout_type"]?.jsonPrimitive?.content)
        assertEquals("0.0", obj["distance_km"]?.jsonPrimitive?.content)
        assertEquals("0.0", obj["distance_meters"]?.jsonPrimitive?.content)
        assertTrue(obj.containsKey("timeseries_1min"))
        assertEquals(0, obj["timeseries_1min"]?.jsonArray?.size)
    }

    @Test
    fun aggregate_serializesHasGpsAndExerciseTypeForIndoorOutdoorTreadmillAndWalking() {
        val start = Instant.parse("2026-11-24T09:00:00Z")
        val end = start.plusSeconds(600)
        val zone2 = HeartRateZone2Config(114, 133)

        val indoorRaw = RawRunningTelemetry(
            hcSessionId = "hc-indoor-run",
            sourcePackage = "com.sec.android.app.shealth",
            title = null,
            workoutType = HealthConnectRunningReader.WORKOUT_TYPE_INDOOR,
            startTime = start,
            endTime = end,
            aggregatedDistanceMeters = 1500.0,
            totalSteps = 1500L,
            heartRateSamples = emptyList(),
            cadenceSamples = emptyList(),
            distanceSegments = emptyList(),
            hasGps = false,
            exerciseType = HealthConnectRunningReader.EXERCISE_TYPE_RUNNING
        )
        val indoorObj = json.parseToJsonElement(
            json.encodeToString(RunningTelemetryAggregator.aggregate(indoorRaw, zone2, end))
        ).jsonObject
        assertEquals("실내", indoorObj["workout_type"]?.jsonPrimitive?.content)
        assertEquals("running", indoorObj["exercise_type"]?.jsonPrimitive?.content)
        assertEquals("false", indoorObj["has_gps"]?.jsonPrimitive?.content)

        val outdoorRaw = indoorRaw.copy(
            hcSessionId = "hc-outdoor-run",
            workoutType = HealthConnectRunningReader.WORKOUT_TYPE_OUTDOOR,
            hasGps = true
        )
        val outdoorObj = json.parseToJsonElement(
            json.encodeToString(RunningTelemetryAggregator.aggregate(outdoorRaw, zone2, end))
        ).jsonObject
        assertEquals("야외", outdoorObj["workout_type"]?.jsonPrimitive?.content)
        assertEquals("running", outdoorObj["exercise_type"]?.jsonPrimitive?.content)
        assertEquals("true", outdoorObj["has_gps"]?.jsonPrimitive?.content)

        val walkingRaw = indoorRaw.copy(
            hcSessionId = "hc-walk",
            workoutType = HealthConnectRunningReader.WORKOUT_TYPE_WALKING,
            hasGps = true,
            exerciseType = HealthConnectRunningReader.EXERCISE_TYPE_WALKING
        )
        val walkingObj = json.parseToJsonElement(
            json.encodeToString(RunningTelemetryAggregator.aggregate(walkingRaw, zone2, end))
        ).jsonObject
        assertEquals("걷기", walkingObj["workout_type"]?.jsonPrimitive?.content)
        assertEquals("walking", walkingObj["exercise_type"]?.jsonPrimitive?.content)
        assertEquals("true", walkingObj["has_gps"]?.jsonPrimitive?.content)

        val treadmillRaw = indoorRaw.copy(
            hcSessionId = "hc-treadmill",
            workoutType = HealthConnectRunningReader.WORKOUT_TYPE_TREADMILL,
            hasGps = false,
            exerciseType = HealthConnectRunningReader.EXERCISE_TYPE_TREADMILL
        )
        val treadmillObj = json.parseToJsonElement(
            json.encodeToString(RunningTelemetryAggregator.aggregate(treadmillRaw, zone2, end))
        ).jsonObject
        assertEquals("트레드밀", treadmillObj["workout_type"]?.jsonPrimitive?.content)
        assertEquals("treadmill", treadmillObj["exercise_type"]?.jsonPrimitive?.content)
        assertEquals("false", treadmillObj["has_gps"]?.jsonPrimitive?.content)
    }
}
