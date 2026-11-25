package com.myrunsync.health

import android.app.Activity
import android.content.Context
import com.myrunsync.model.CadenceSample
import com.myrunsync.model.HrSample
import com.myrunsync.model.RawRunningTelemetry
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.data.entries.ExerciseSession
import com.samsung.android.sdk.health.data.error.ErrorCode
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.InstantTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Reads running and walking workouts directly from Samsung Health via Samsung Health Data SDK 1.1.0,
 * including per-interval heart rate, cadence, speed, and GPS exercise route coordinates.
 */
class SamsungHealthRunningReader(
    context: Context
) : RunningTelemetryReader {

    private val appContext = context.applicationContext
    private val store: HealthDataStore by lazy { HealthDataService.getStore(appContext) }

    suspend fun hasRequiredPermissions(): Boolean {
        val granted = store.getGrantedPermissions(ALL_PERMISSIONS)
        return REQUIRED_PERMISSIONS.all { it in granted }
    }

    suspend fun hasLocationPermission(): Boolean {
        val granted = store.getGrantedPermissions(ALL_PERMISSIONS)
        return EXERCISE_LOCATION_PERMISSION in granted
    }

    suspend fun requestPermissions(activity: Activity): Set<String> {
        return try {
            val granted = store.requestPermissions(ALL_PERMISSIONS, activity)
            granted.map { it.dataType.name }.toSet()
        } catch (e: ResolvablePlatformException) {
            if (e.hasResolution) {
                e.resolve(activity)
            }
            throw e
        }
    }

    override suspend fun readRunningSessions(
        startTime: Instant,
        endTime: Instant
    ): List<RawRunningTelemetry> {
        val granted = store.getGrantedPermissions(ALL_PERMISSIONS)
        val hasLocationPerm = EXERCISE_LOCATION_PERMISSION in granted
        val hasHeartRatePerm = HEART_RATE_PERMISSION in granted

        val dataPoints = readAllExercises(startTime, endTime)
        val results = mutableListOf<RawRunningTelemetry>()

        for (dp in dataPoints) {
            val sessions = dp.getValue(DataType.ExerciseType.SESSIONS).orEmpty()
            val pointTitle = dp.getValue(DataType.ExerciseType.CUSTOM_TITLE)
            val sourceApp = dp.dataSource?.appId ?: SAMSUNG_HEALTH_PACKAGE

            sessions.forEachIndexed { index, session ->
                val kind = mapExerciseKind(session.exerciseType) ?: return@forEachIndexed
                val telemetry = convertSession(
                    dp = dp,
                    session = session,
                    kind = kind,
                    sessionIndex = index,
                    sessionCount = sessions.size,
                    pointTitle = pointTitle,
                    sourceApp = sourceApp,
                    hasLocationPerm = hasLocationPerm,
                    hasHeartRatePerm = hasHeartRatePerm
                )
                results.add(telemetry)
            }
        }

        return results
    }

    private suspend fun readAllExercises(
        startTime: Instant,
        endTime: Instant
    ): List<HealthDataPoint> {
        val all = mutableListOf<HealthDataPoint>()
        var pageToken: String? = null
        do {
            val builder = DataTypes.EXERCISE.readDataRequestBuilder
                .setInstantTimeFilter(InstantTimeFilter.of(startTime, endTime))
                .setOrdering(Ordering.DESC)
            if (pageToken != null) {
                builder.setPageToken(pageToken)
            }
            val response = store.readData(builder.build())
            all.addAll(response.dataList)
            pageToken = response.pageToken
        } while (pageToken != null)
        return all
    }

    private suspend fun convertSession(
        dp: HealthDataPoint,
        session: ExerciseSession,
        kind: SamsungExerciseKind,
        sessionIndex: Int,
        sessionCount: Int,
        pointTitle: String?,
        sourceApp: String,
        hasLocationPerm: Boolean,
        hasHeartRatePerm: Boolean
    ): RawRunningTelemetry {
        val logPoints = session.log.orEmpty().map { entry ->
            SamsungLogPoint(
                timestamp = entry.timestamp,
                heartRate = entry.heartRate,
                cadence = entry.cadence,
                speedMps = entry.speed
            )
        }

        val routePoints = session.route.orEmpty().map { loc ->
            SamsungLocationPoint(
                timestamp = loc.timestamp,
                latitude = loc.latitude.toDouble(),
                longitude = loc.longitude.toDouble()
            )
        }

        val classification = SamsungExerciseMapper.classifySamsungWorkout(
            kind = kind,
            hasRoute = routePoints.isNotEmpty(),
            hasLocationPermission = hasLocationPerm
        )

        var hrSamples = logPoints.mapNotNull { log ->
            val hr = log.heartRate
            if (hr != null && hr.isFinite() && hr > 0f) {
                HrSample(time = log.timestamp, bpm = hr.roundToLong())
            } else {
                null
            }
        }

        if (hrSamples.isEmpty() && hasHeartRatePerm) {
            hrSamples = readHeartRateSamples(session.startTime, session.endTime)
        }

        val cadenceSamples = logPoints.mapNotNull { log ->
            val cad = log.cadence
            if (cad != null && cad.isFinite() && cad > 0f) {
                CadenceSample(time = log.timestamp, spm = cad.toDouble())
            } else {
                null
            }
        }

        val totalDistanceMeters = session.distance?.takeIf { it.isFinite() && it >= 0f }?.toDouble()
        val distanceSegments = SamsungExerciseMapper.buildDistanceSegments(
            startTime = session.startTime,
            endTime = session.endTime,
            totalDistanceMeters = totalDistanceMeters,
            logPoints = logPoints,
            routePoints = routePoints
        )

        val sessionId = SamsungExerciseMapper.buildSessionId(
            uid = dp.uid,
            sessionIndex = sessionIndex,
            sessionCount = sessionCount
        )

        return RawRunningTelemetry(
            hcSessionId = sessionId,
            sourcePackage = sourceApp,
            title = session.customTitle ?: pointTitle,
            workoutType = classification.workoutType,
            startTime = session.startTime,
            endTime = session.endTime,
            aggregatedDistanceMeters = totalDistanceMeters,
            totalSteps = null,
            heartRateSamples = hrSamples,
            cadenceSamples = cadenceSamples,
            distanceSegments = distanceSegments,
            hasGps = classification.hasGps,
            exerciseType = classification.exerciseType,
            summaryAvgCadence = session.meanCadence?.takeIf { it.isFinite() && it > 0f }?.toDouble(),
            summaryAvgHr = session.meanHeartRate?.takeIf { it.isFinite() && it > 0f }?.toDouble(),
            summaryMaxHr = session.maxHeartRate?.takeIf { it.isFinite() && it > 0f }?.roundToInt(),
            summaryMinHr = session.minHeartRate?.takeIf { it.isFinite() && it > 0f }?.roundToInt()
        )
    }

    private suspend fun readHeartRateSamples(
        startTime: Instant,
        endTime: Instant
    ): List<HrSample> {
        val samples = mutableListOf<HrSample>()
        var pageToken: String? = null
        do {
            val builder = DataTypes.HEART_RATE.readDataRequestBuilder
                .setInstantTimeFilter(InstantTimeFilter.of(startTime, endTime))
                .setOrdering(Ordering.ASC)
            if (pageToken != null) {
                builder.setPageToken(pageToken)
            }
            val response = store.readData(builder.build())
            for (dp in response.dataList) {
                val series = dp.getValue(DataType.HeartRateType.SERIES_DATA).orEmpty()
                if (series.isNotEmpty()) {
                    for (entry in series) {
                        if (entry.heartRate.isFinite() && entry.heartRate > 0f) {
                            samples.add(HrSample(time = entry.startTime, bpm = entry.heartRate.roundToLong()))
                        }
                    }
                } else {
                    val hr = dp.getValue(DataType.HeartRateType.HEART_RATE)
                    if (hr != null && hr.isFinite() && hr > 0f) {
                        samples.add(HrSample(time = dp.startTime, bpm = hr.roundToLong()))
                    }
                }
            }
            pageToken = response.pageToken
        } while (pageToken != null)
        return samples
    }

    private fun mapExerciseKind(
        type: DataType.ExerciseType.PredefinedExerciseType
    ): SamsungExerciseKind? {
        return when (type) {
            DataType.ExerciseType.PredefinedExerciseType.RUNNING -> SamsungExerciseKind.RUNNING
            DataType.ExerciseType.PredefinedExerciseType.TRACK_RUNNING -> SamsungExerciseKind.TRACK_RUNNING
            DataType.ExerciseType.PredefinedExerciseType.TREADMILL -> SamsungExerciseKind.TREADMILL
            DataType.ExerciseType.PredefinedExerciseType.WALKING -> SamsungExerciseKind.WALKING
            else -> null
        }
    }

    companion object {
        private const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"

        private val EXERCISE_PERMISSION = Permission.of(DataTypes.EXERCISE, AccessType.READ)
        private val EXERCISE_LOCATION_PERMISSION = Permission.of(DataTypes.EXERCISE_LOCATION, AccessType.READ)
        private val HEART_RATE_PERMISSION = Permission.of(DataTypes.HEART_RATE, AccessType.READ)

        val REQUIRED_PERMISSIONS: Set<Permission> = setOf(EXERCISE_PERMISSION)
        val ALL_PERMISSIONS: Set<Permission> = setOf(
            EXERCISE_PERMISSION,
            EXERCISE_LOCATION_PERMISSION,
            HEART_RATE_PERMISSION
        )

        fun isSupported(): Boolean = true

        fun isSamsungHealthInstalled(context: Context): Boolean {
            return runCatching {
                context.packageManager.getPackageInfo(SAMSUNG_HEALTH_PACKAGE, 0)
                true
            }.getOrDefault(false)
        }

        fun formatErrorMessage(error: Throwable): String {
            if (error is HealthDataException) {
                val code = error.errorCode
                val base = error.errorMessage ?: error.localizedMessage ?: "HealthDataException($code)"
                return when (code) {
                    ErrorCode.ERR_ACCESS_CONTROL,
                    ErrorCode.ERR_INVALID_CALLER ->
                        "Samsung Health 접근 거부 ($code): 삼성 헬스 → 설정 → 삼성 헬스 정보 → 버전 10번 탭 → 개발자 모드(데이터 읽기)를 켜주세요."
                    ErrorCode.ERR_NO_USER_PERMISSION ->
                        "Samsung Health 권한이 필요합니다. 권한 허용 버튼을 먼저 눌러주세요."
                    ErrorCode.ERR_PLATFORM_NOT_INSTALLED,
                    ErrorCode.ERR_OLD_VERSION_PLATFORM ->
                        "삼성 헬스 앱이 설치되어 있지 않거나 최신 버전으로 업데이트가 필요합니다."
                    else -> "$base (code=$code)"
                }
            }
            return error.localizedMessage ?: error::class.java.simpleName
        }
    }
}
