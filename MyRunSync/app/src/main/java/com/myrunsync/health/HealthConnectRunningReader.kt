package com.myrunsync.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.myrunsync.model.CadenceSample
import com.myrunsync.model.DistanceSegment
import com.myrunsync.model.HrSample
import com.myrunsync.model.RawRunningTelemetry
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Reads running exercise sessions and associated telemetry from Android Health Connect.
 */
class HealthConnectRunningReader(
    private val client: HealthConnectClient
) {
    suspend fun hasAllPermissions(): Boolean {
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(REQUIRED_PERMISSIONS)
    }

    fun isBackgroundReadSupported(): Boolean {
        return client.features.getFeatureStatus(
            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
        ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }

    suspend fun hasBackgroundPermission(): Boolean {
        if (!isBackgroundReadSupported()) return false
        val granted = client.permissionController.getGrantedPermissions()
        return BACKGROUND_PERMISSION in granted
    }

    fun permissionsToRequest(includeBackground: Boolean): Set<String> {
        return if (includeBackground && isBackgroundReadSupported()) {
            ALL_PERMISSIONS_WITH_BACKGROUND
        } else {
            REQUIRED_PERMISSIONS
        }
    }

    suspend fun readRunningSessions(
        startTime: Instant,
        endTime: Instant
    ): List<RawRunningTelemetry> {
        val sessions = readAllRecords(
            recordType = ExerciseSessionRecord::class,
            startTime = startTime,
            endTime = endTime
        ).filter { it.exerciseType in SYNCED_EXERCISE_TYPES }

        return sessions.map { session ->
            readTelemetryForSession(session)
        }
    }

    private suspend fun readTelemetryForSession(
        session: ExerciseSessionRecord
    ): RawRunningTelemetry {
        val window = TimeRangeFilter.between(session.startTime, session.endTime)

        val aggregate = client.aggregate(
            AggregateRequest(
                metrics = setOf(DistanceRecord.DISTANCE_TOTAL, StepsRecord.COUNT_TOTAL),
                timeRangeFilter = window
            )
        )

        val heartRateRecords = readAllRecords(
            recordType = HeartRateRecord::class,
            startTime = session.startTime,
            endTime = session.endTime
        )

        val cadenceRecords = readAllRecords(
            recordType = StepsCadenceRecord::class,
            startTime = session.startTime,
            endTime = session.endTime
        )

        val distanceRecords = readAllRecords(
            recordType = DistanceRecord::class,
            startTime = session.startTime,
            endTime = session.endTime
        )

        val hrSamples = heartRateRecords
            .flatMap { it.samples }
            .map { HrSample(time = it.time, bpm = it.beatsPerMinute) }

        val cadenceSamples = cadenceRecords
            .flatMap { it.samples }
            .map { CadenceSample(time = it.time, spm = it.rate) }

        val distanceSegments = distanceRecords.map { record ->
            DistanceSegment(
                startTime = record.startTime,
                endTime = record.endTime,
                meters = record.distance.inMeters
            )
        }

        val hasGps = resolveHasGps(session.exerciseRouteResult)
        val (exerciseType, workoutType) = classifySessionType(
            sessionExerciseType = session.exerciseType,
            hasGps = hasGps
        )

        return RawRunningTelemetry(
            hcSessionId = session.metadata.id,
            sourcePackage = session.metadata.dataOrigin.packageName,
            title = session.title,
            workoutType = workoutType,
            startTime = session.startTime,
            endTime = session.endTime,
            aggregatedDistanceMeters = aggregate[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
            totalSteps = aggregate[StepsRecord.COUNT_TOTAL],
            heartRateSamples = hrSamples,
            cadenceSamples = cadenceSamples,
            distanceSegments = distanceSegments,
            hasGps = hasGps,
            exerciseType = exerciseType
        )
    }

    private suspend fun <T : Record> readAllRecords(
        recordType: KClass<T>,
        startTime: Instant,
        endTime: Instant
    ): List<T> {
        val results = mutableListOf<T>()
        var pageToken: String? = null
        val filter = TimeRangeFilter.between(startTime, endTime)
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = recordType,
                    timeRangeFilter = filter,
                    pageToken = pageToken
                )
            )
            results.addAll(response.records)
            pageToken = response.pageToken
        } while (pageToken != null)
        return results
    }

    companion object {
        const val WORKOUT_TYPE_OUTDOOR = "야외"
        const val WORKOUT_TYPE_INDOOR = "실내"
        const val WORKOUT_TYPE_TREADMILL = "트레드밀"
        const val WORKOUT_TYPE_WALKING = "걷기"

        const val EXERCISE_TYPE_RUNNING = "running"
        const val EXERCISE_TYPE_TREADMILL = "treadmill"
        const val EXERCISE_TYPE_WALKING = "walking"

        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(StepsCadenceRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class)
        )

        const val BACKGROUND_PERMISSION: String =
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

        val ALL_PERMISSIONS_WITH_BACKGROUND: Set<String> =
            REQUIRED_PERMISSIONS + BACKGROUND_PERMISSION

        private val SYNCED_EXERCISE_TYPES = setOf(
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        )

        internal fun resolveHasGps(routeResult: ExerciseRouteResult): Boolean {
            return when (routeResult) {
                is ExerciseRouteResult.Data -> routeResult.exerciseRoute.route.isNotEmpty()
                is ExerciseRouteResult.ConsentRequired -> true
                is ExerciseRouteResult.NoData -> false
                else -> false
            }
        }

        internal fun classifySessionType(
            sessionExerciseType: Int,
            hasGps: Boolean
        ): Pair<String, String> {
            return when (sessionExerciseType) {
                ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL ->
                    EXERCISE_TYPE_TREADMILL to WORKOUT_TYPE_TREADMILL
                ExerciseSessionRecord.EXERCISE_TYPE_WALKING ->
                    EXERCISE_TYPE_WALKING to WORKOUT_TYPE_WALKING
                else ->
                    EXERCISE_TYPE_RUNNING to if (hasGps) WORKOUT_TYPE_OUTDOOR else WORKOUT_TYPE_INDOOR
            }
        }

        fun isAvailable(context: Context): Boolean {
            return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        }
    }
}
