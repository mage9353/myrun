package com.myrunsync.health

import com.myrunsync.model.DistanceSegment
import java.time.Duration
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class SamsungExerciseKind {
    RUNNING,
    TRACK_RUNNING,
    TREADMILL,
    WALKING
}

data class SamsungLogPoint(
    val timestamp: Instant,
    val heartRate: Float?,
    val cadence: Float?,
    val speedMps: Float?
)

data class SamsungLocationPoint(
    val timestamp: Instant,
    val latitude: Double,
    val longitude: Double
)

data class SamsungWorkoutClassification(
    val exerciseType: String,
    val workoutType: String,
    val hasGps: Boolean?
)

/**
 * Pure Kotlin helper for mapping Samsung Health Data SDK exercise entries
 * into MyRunSync telemetry primitives (unit-testable without Android runtime).
 */
object SamsungExerciseMapper {

    private const val MAX_LOG_DELTA_SECONDS = 60.0
    private const val MAX_REALISTIC_GPS_SPEED_MPS = 15.0
    private const val EARTH_RADIUS_METERS = 6371000.0

    fun buildSessionId(uid: String, sessionIndex: Int, sessionCount: Int): String {
        return if (sessionCount <= 1) "sh:$uid" else "sh:$uid:$sessionIndex"
    }

    fun classifySamsungWorkout(
        kind: SamsungExerciseKind,
        hasRoute: Boolean,
        hasLocationPermission: Boolean
    ): SamsungWorkoutClassification {
        return when (kind) {
            SamsungExerciseKind.TREADMILL -> SamsungWorkoutClassification(
                exerciseType = HealthConnectRunningReader.EXERCISE_TYPE_TREADMILL,
                workoutType = HealthConnectRunningReader.WORKOUT_TYPE_TREADMILL,
                hasGps = false
            )
            SamsungExerciseKind.WALKING -> SamsungWorkoutClassification(
                exerciseType = HealthConnectRunningReader.EXERCISE_TYPE_WALKING,
                workoutType = HealthConnectRunningReader.WORKOUT_TYPE_WALKING,
                hasGps = if (hasLocationPermission) hasRoute else null
            )
            SamsungExerciseKind.RUNNING,
            SamsungExerciseKind.TRACK_RUNNING -> {
                if (hasLocationPermission) {
                    SamsungWorkoutClassification(
                        exerciseType = HealthConnectRunningReader.EXERCISE_TYPE_RUNNING,
                        workoutType = if (hasRoute) {
                            HealthConnectRunningReader.WORKOUT_TYPE_OUTDOOR
                        } else {
                            HealthConnectRunningReader.WORKOUT_TYPE_INDOOR
                        },
                        hasGps = hasRoute
                    )
                } else {
                    SamsungWorkoutClassification(
                        exerciseType = HealthConnectRunningReader.EXERCISE_TYPE_RUNNING,
                        workoutType = HealthConnectRunningReader.WORKOUT_TYPE_OUTDOOR,
                        hasGps = null
                    )
                }
            }
        }
    }

    fun buildDistanceSegments(
        startTime: Instant,
        endTime: Instant,
        totalDistanceMeters: Double?,
        logPoints: List<SamsungLogPoint>,
        routePoints: List<SamsungLocationPoint>
    ): List<DistanceSegment> {
        val validTotal = totalDistanceMeters?.takeIf { it.isFinite() && it > 0.0 }

        val speedSegments = buildFromSpeedLogs(startTime, endTime, logPoints)
        if (speedSegments.isNotEmpty()) {
            return scaleSegments(speedSegments, validTotal)
        }

        val gpsSegments = buildFromRoute(startTime, endTime, routePoints)
        if (gpsSegments.isNotEmpty()) {
            return scaleSegments(gpsSegments, validTotal)
        }

        if (validTotal != null && endTime.isAfter(startTime)) {
            return listOf(DistanceSegment(startTime, endTime, validTotal))
        }
        return emptyList()
    }

    private fun buildFromSpeedLogs(
        startTime: Instant,
        endTime: Instant,
        logPoints: List<SamsungLogPoint>
    ): List<DistanceSegment> {
        val sorted = logPoints
            .filter {
                !it.timestamp.isBefore(startTime) &&
                    !it.timestamp.isAfter(endTime) &&
                    it.speedMps != null &&
                    it.speedMps.isFinite() &&
                    it.speedMps > 0f
            }
            .sortedBy { it.timestamp }
        if (sorted.isEmpty()) return emptyList()

        val result = ArrayList<DistanceSegment>(sorted.size)
        for (i in sorted.indices) {
            val current = sorted[i]
            val nextTime = if (i + 1 < sorted.size) sorted[i + 1].timestamp else endTime
            val deltaSec = (Duration.between(current.timestamp, nextTime).toMillis() / 1000.0)
                .coerceIn(0.0, MAX_LOG_DELTA_SECONDS)
            if (deltaSec > 0.0) {
                val segEnd = current.timestamp.plusMillis((deltaSec * 1000.0).toLong())
                val meters = current.speedMps!!.toDouble() * deltaSec
                if (meters > 0.0) {
                    result.add(DistanceSegment(current.timestamp, segEnd, meters))
                }
            }
        }
        return result
    }

    private fun buildFromRoute(
        startTime: Instant,
        endTime: Instant,
        routePoints: List<SamsungLocationPoint>
    ): List<DistanceSegment> {
        val sorted = routePoints
            .filter { !it.timestamp.isBefore(startTime) && !it.timestamp.isAfter(endTime) }
            .sortedBy { it.timestamp }
        if (sorted.size < 2) return emptyList()

        val result = ArrayList<DistanceSegment>(sorted.size - 1)
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            val dtSec = Duration.between(a.timestamp, b.timestamp).toMillis() / 1000.0
            if (dtSec <= 0.0) continue
            val meters = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            if (meters > 0.0 && meters / dtSec <= MAX_REALISTIC_GPS_SPEED_MPS) {
                result.add(DistanceSegment(a.timestamp, b.timestamp, meters))
            }
        }
        return result
    }

    private fun scaleSegments(
        segments: List<DistanceSegment>,
        targetTotalMeters: Double?
    ): List<DistanceSegment> {
        if (targetTotalMeters == null || targetTotalMeters <= 0.0) return segments
        val rawSum = segments.sumOf { it.meters }
        if (rawSum <= 0.0) return segments
        val factor = targetTotalMeters / rawSum
        return segments.map { it.copy(meters = it.meters * factor) }
    }

    internal fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(rLat1) * cos(rLat2) * sin(dLon / 2.0) * sin(dLon / 2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
        return EARTH_RADIUS_METERS * c
    }
}
