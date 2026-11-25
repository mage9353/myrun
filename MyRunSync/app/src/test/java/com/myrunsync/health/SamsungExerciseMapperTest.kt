package com.myrunsync.health

import com.myrunsync.domain.RunningTelemetryAggregator
import com.myrunsync.model.AltitudeSample
import com.myrunsync.model.HeartRateZone2Config
import com.myrunsync.model.RawRunningTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SamsungExerciseMapperTest {

    @Test
    fun classifySamsungWorkout_usesRouteOnlyWhenLocationPermissionGranted() {
        val outdoor = SamsungExerciseMapper.classifySamsungWorkout(
            kind = SamsungExerciseKind.RUNNING,
            hasRoute = true,
            hasLocationPermission = true
        )
        assertEquals("running", outdoor.exerciseType)
        assertEquals("야외", outdoor.workoutType)
        assertEquals(true, outdoor.hasGps)

        val indoor = SamsungExerciseMapper.classifySamsungWorkout(
            kind = SamsungExerciseKind.RUNNING,
            hasRoute = false,
            hasLocationPermission = true
        )
        assertEquals("running", indoor.exerciseType)
        assertEquals("실내", indoor.workoutType)
        assertEquals(false, indoor.hasGps)

        val noLocPerm = SamsungExerciseMapper.classifySamsungWorkout(
            kind = SamsungExerciseKind.RUNNING,
            hasRoute = false,
            hasLocationPermission = false
        )
        assertEquals("running", noLocPerm.exerciseType)
        assertEquals("야외", noLocPerm.workoutType)
        assertNull(noLocPerm.hasGps)

        val treadmill = SamsungExerciseMapper.classifySamsungWorkout(
            kind = SamsungExerciseKind.TREADMILL,
            hasRoute = false,
            hasLocationPermission = true
        )
        assertEquals("treadmill", treadmill.exerciseType)
        assertEquals("트레드밀", treadmill.workoutType)
        assertEquals(false, treadmill.hasGps)

        val walking = SamsungExerciseMapper.classifySamsungWorkout(
            kind = SamsungExerciseKind.WALKING,
            hasRoute = true,
            hasLocationPermission = true
        )
        assertEquals("walking", walking.exerciseType)
        assertEquals("걷기", walking.workoutType)
        assertEquals(true, walking.hasGps)
    }

    @Test
    fun buildDistanceSegments_integratesSpeedLogsAndScalesToSessionTotal() {
        val start = Instant.parse("2026-11-25T00:00:00Z")
        val end = start.plusSeconds(120)
        val logs = listOf(
            SamsungLogPoint(start, heartRate = 130f, cadence = 170f, speedMps = 3.0f),
            SamsungLogPoint(start.plusSeconds(60), heartRate = 135f, cadence = 174f, speedMps = 3.0f)
        )

        val segments = SamsungExerciseMapper.buildDistanceSegments(
            startTime = start,
            endTime = end,
            totalDistanceMeters = 400.0,
            logPoints = logs,
            routePoints = emptyList()
        )
        assertEquals(2, segments.size)
        assertEquals(400.0, segments.sumOf { it.meters }, 0.01)
        assertEquals(200.0, segments[0].meters, 0.01)
    }

    @Test
    fun buildDistanceSegments_fallsBackToHaversineRouteThenUniformDistance() {
        val start = Instant.parse("2026-11-25T00:00:00Z")
        val end = start.plusSeconds(60)
        val route = listOf(
            SamsungLocationPoint(start, 37.5665, 126.9780),
            SamsungLocationPoint(start.plusSeconds(30), 37.5674, 126.9780),
            SamsungLocationPoint(end, 37.5683, 126.9780)
        )

        val routeSegments = SamsungExerciseMapper.buildDistanceSegments(
            startTime = start,
            endTime = end,
            totalDistanceMeters = null,
            logPoints = emptyList(),
            routePoints = route
        )
        assertEquals(2, routeSegments.size)
        assertTrue(routeSegments.sumOf { it.meters } in 180.0..220.0)

        val uniformSegments = SamsungExerciseMapper.buildDistanceSegments(
            startTime = start,
            endTime = end,
            totalDistanceMeters = 250.0,
            logPoints = emptyList(),
            routePoints = emptyList()
        )
        assertEquals(1, uniformSegments.size)
        assertEquals(250.0, uniformSegments[0].meters, 0.01)
    }

    @Test
    fun buildSessionId_prefixesSamsungHealthUid() {
        assertEquals("sh:uid-123", SamsungExerciseMapper.buildSessionId("uid-123", 0, 1))
        assertEquals("sh:uid-123:1", SamsungExerciseMapper.buildSessionId("uid-123", 1, 2))
    }

    @Test
    fun aggregator_usesSummaryAvgCadenceWhenPerSampleCadenceMissing() {
        val start = Instant.parse("2026-11-25T00:00:00Z")
        val end = start.plusSeconds(600)
        val raw = RawRunningTelemetry(
            hcSessionId = "sh:uid-cadence",
            sourcePackage = "com.sec.android.app.shealth",
            title = "Samsung Run",
            workoutType = "야외",
            startTime = start,
            endTime = end,
            aggregatedDistanceMeters = 2000.0,
            totalSteps = null,
            heartRateSamples = emptyList(),
            cadenceSamples = emptyList(),
            distanceSegments = emptyList(),
            hasGps = true,
            exerciseType = "running",
            summaryAvgCadence = 174.6,
            summaryAvgHr = 132.4,
            summaryMaxHr = 148,
            summaryMinHr = 112,
            calories = 342,
            maxCadence = 186,
            maxSpeedMps = 4.0,
            altitudeGainM = 45.67,
            altitudeLossM = 42.12,
            vo2Max = 49.34,
            avgPowerW = 245,
            altitudeSamples = listOf(
                AltitudeSample(start.plusSeconds(10), 31.2),
                AltitudeSample(start.plusSeconds(40), 33.4)
            )
        )

        val dto = RunningTelemetryAggregator.aggregate(
            telemetry = raw,
            zone2Config = HeartRateZone2Config(114, 133),
            syncedAt = end
        )
        assertEquals(175, dto.avgCadence)
        assertEquals(186, dto.maxCadence)
        assertEquals(132, dto.avgHr)
        assertEquals(148, dto.maxHr)
        assertEquals(112, dto.minHr)
        assertEquals(342, dto.calories)
        assertEquals(250, dto.bestPaceSec)
        assertEquals(45.7, dto.altitudeGain!!, 0.01)
        assertEquals(42.1, dto.altitudeLoss!!, 0.01)
        assertEquals(49.3, dto.vo2Max!!, 0.01)
        assertEquals(245, dto.avgPowerW)
        assertEquals(32.3, dto.timeseries1Min.first().altitudeM!!, 0.01)
    }
}
