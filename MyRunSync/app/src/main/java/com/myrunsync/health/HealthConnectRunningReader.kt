package com.myrunsync.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
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

    suspend fun readRunningSessions(
        startTime: Instant,
        endTime: Instant
    ): List<RawRunningTelemetry> {
        val sessions = readAllRecords(
            recordType = ExerciseSessionRecord::class,
            startTime = startTime,
            endTime = endTime
        ).filter { it.exerciseType in RUNNING_EXERCISE_TYPES }

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

        val workoutType = if (session.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL) {
            WORKOUT_TYPE_TREADMILL
        } else {
            WORKOUT_TYPE_OUTDOOR
        }

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
            distanceSegments = distanceSegments
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
        const val WORKOUT_TYPE_TREADMILL = "트레드밀"

        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(StepsCadenceRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class)
        )

        private val RUNNING_EXERCISE_TYPES = setOf(
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
        )

        fun isAvailable(context: Context): Boolean {
            return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        }
    }
}
