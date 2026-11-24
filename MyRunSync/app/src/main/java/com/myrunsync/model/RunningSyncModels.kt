package com.myrunsync.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Defines the inclusive heart rate boundaries (in bpm) for Zone 2 training.
 */
data class HeartRateZone2Config(
    val minBpm: Int,
    val maxBpm: Int
) {
    init {
        require(minBpm > 0) { "minBpm must be positive" }
        require(maxBpm >= minBpm) { "maxBpm ($maxBpm) must be >= minBpm ($minBpm)" }
    }

    fun contains(bpm: Long): Boolean = bpm in minBpm..maxBpm

    companion object {
        /**
         * Derives Zone 2 bounds as percentages of maximum heart rate (default 60% - 70%).
         */
        fun fromMaxHeartRate(
            maxHrBpm: Int,
            minPct: Double = 0.60,
            maxPct: Double = 0.70
        ): HeartRateZone2Config {
            require(maxHrBpm > 0) { "maxHrBpm must be positive" }
            require(minPct in 0.0..1.0 && maxPct in minPct..1.0) { "Invalid Zone 2 percentage range" }
            return HeartRateZone2Config(
                minBpm = (maxHrBpm * minPct).roundToInt(),
                maxBpm = (maxHrBpm * maxPct).roundToInt()
            )
        }
    }
}

data class HrSample(
    val time: Instant,
    val bpm: Long
)

data class CadenceSample(
    val time: Instant,
    val spm: Double
)

data class DistanceSegment(
    val startTime: Instant,
    val endTime: Instant,
    val meters: Double
)

/**
 * Raw Health Connect running telemetry extracted for a single ExerciseSessionRecord.
 */
data class RawRunningTelemetry(
    val hcSessionId: String,
    val sourcePackage: String?,
    val title: String?,
    val workoutType: String,
    val startTime: Instant,
    val endTime: Instant,
    val aggregatedDistanceMeters: Double?,
    val totalSteps: Long?,
    val heartRateSamples: List<HrSample>,
    val cadenceSamples: List<CadenceSample>,
    val distanceSegments: List<DistanceSegment>
)

/**
 * 1-minute downsampled telemetry bucket stored inside `running_logs.timeseries_1min` JSONB.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MinuteBucket(
    @SerialName("minute") val minute: Int,
    @SerialName("offset_sec") val offsetSec: Int,
    @SerialName("timestamp") val timestamp: String,
    @SerialName("hr") val hr: Int? = null,
    @SerialName("max_hr") val maxHr: Int? = null,
    @SerialName("min_hr") val minHr: Int? = null,
    @SerialName("zone2_ratio") val zone2Ratio: Double? = null,
    @SerialName("pace_sec") val paceSec: Int? = null,
    @SerialName("cadence") val cadence: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("distance_m") val distanceM: Double = 0.0
)

/**
 * Unified Supabase Postgrest DTO matching live `public.running_logs`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RunningLogDto(
    @SerialName("hc_session_id") val hcSessionId: String,
    @SerialName("run_date") val runDate: String,
    @SerialName("ended_at") val endedAt: String,
    @SerialName("workout_type") val workoutType: String,
    @SerialName("source_package") val sourcePackage: String? = null,
    @SerialName("title") val title: String? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("distance_km") val distanceKm: Double = 0.0,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("distance_meters") val distanceMeters: Double = 0.0,
    @SerialName("duration_sec") val durationSec: Int,
    @SerialName("avg_pace_sec") val avgPaceSec: Int? = null,
    @SerialName("avg_hr") val avgHr: Int? = null,
    @SerialName("max_hr") val maxHr: Int? = null,
    @SerialName("min_hr") val minHr: Int? = null,
    @SerialName("zone2_min_bpm") val zone2MinBpm: Int,
    @SerialName("zone2_max_bpm") val zone2MaxBpm: Int,
    @SerialName("zone2_ratio") val zone2Ratio: Double? = null,
    @SerialName("zone2_pct") val zone2Pct: Double? = null,
    @SerialName("avg_cadence") val avgCadence: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("timeseries_1min") val timeseries1Min: List<MinuteBucket> = emptyList(),
    @SerialName("synced_at") val syncedAt: String
)
