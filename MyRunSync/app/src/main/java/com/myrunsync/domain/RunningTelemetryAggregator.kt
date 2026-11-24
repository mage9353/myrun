package com.myrunsync.domain

import com.myrunsync.model.CadenceSample
import com.myrunsync.model.DistanceSegment
import com.myrunsync.model.HeartRateZone2Config
import com.myrunsync.model.HrSample
import com.myrunsync.model.MinuteBucket
import com.myrunsync.model.RawRunningTelemetry
import com.myrunsync.model.RunningLogDto
import java.time.Duration
import java.time.Instant
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Pure Kotlin aggregator that computes running summary metrics and 1-minute downsampled buckets
 * matching `public.running_logs` from raw Health Connect telemetry.
 */
object RunningTelemetryAggregator {

    private const val BUCKET_SECONDS = 60L
    // Cap per-sample duration weight to avoid over-weighting gaps caused by sensor dropouts.
    private const val MAX_HR_SAMPLE_WEIGHT_SECONDS = 60.0

    fun aggregate(
        telemetry: RawRunningTelemetry,
        zone2Config: HeartRateZone2Config,
        syncedAt: Instant = Instant.now()
    ): RunningLogDto {
        val durationSeconds = Duration.between(telemetry.startTime, telemetry.endTime)
            .seconds
            .coerceAtLeast(0L)
            .toInt()

        val sortedHr = telemetry.heartRateSamples
            .filter { !it.time.isBefore(telemetry.startTime) && !it.time.isAfter(telemetry.endTime) && it.bpm > 0 }
            .sortedBy { it.time }

        val validCadence = telemetry.cadenceSamples
            .filter { !it.time.isBefore(telemetry.startTime) && !it.time.isAfter(telemetry.endTime) && it.spm > 0.0 }
            .sortedBy { it.time }

        val validSegments = telemetry.distanceSegments
            .filter { it.meters > 0.0 && !it.endTime.isBefore(it.startTime) }

        val distanceMeters = resolveTotalDistanceMeters(
            aggregatedDistanceMeters = telemetry.aggregatedDistanceMeters,
            segments = validSegments
        )

        val avgPaceSecPerKm = computePaceSecPerKm(distanceMeters, durationSeconds.toDouble())
        val hrSummary = summarizeHeartRate(sortedHr, telemetry.endTime, zone2Config)
        val zone2Ratio = hrSummary.zone2Ratio?.let(::roundTo4)
        val zone2Pct = zone2Ratio?.let { roundTo1(it * 100.0) }

        val avgCadenceSpm = resolveAverageCadence(
            cadenceSamples = validCadence
        )

        val timeseries1Min = buildMinuteSeries(
            startTime = telemetry.startTime,
            endTime = telemetry.endTime,
            hrSamples = sortedHr,
            cadenceSamples = validCadence,
            distanceSegments = validSegments,
            zone2Config = zone2Config
        )

        return RunningLogDto(
            hcSessionId = telemetry.hcSessionId,
            runDate = telemetry.startTime.toString(),
            endedAt = telemetry.endTime.toString(),
            workoutType = telemetry.workoutType,
            exerciseType = telemetry.exerciseType,
            hasGps = telemetry.hasGps,
            sourcePackage = telemetry.sourcePackage,
            title = telemetry.title,
            distanceKm = roundTo3(distanceMeters / 1000.0),
            distanceMeters = roundTo2(distanceMeters),
            durationSec = durationSeconds,
            avgPaceSec = avgPaceSecPerKm?.roundToInt(),
            avgHr = hrSummary.avgBpm?.roundToInt(),
            maxHr = hrSummary.maxBpm,
            minHr = hrSummary.minBpm,
            zone2MinBpm = zone2Config.minBpm,
            zone2MaxBpm = zone2Config.maxBpm,
            zone2Ratio = zone2Ratio,
            zone2Pct = zone2Pct,
            avgCadence = avgCadenceSpm?.roundToInt(),
            timeseries1Min = timeseries1Min,
            syncedAt = syncedAt.toString()
        )
    }

    private data class HrSummary(
        val avgBpm: Double?,
        val maxBpm: Int?,
        val minBpm: Int?,
        val zone2Ratio: Double?
    )

    private fun summarizeHeartRate(
        samples: List<HrSample>,
        windowEnd: Instant,
        zone2Config: HeartRateZone2Config
    ): HrSummary {
        if (samples.isEmpty()) {
            return HrSummary(avgBpm = null, maxBpm = null, minBpm = null, zone2Ratio = null)
        }
        val avgBpm = samples.map { it.bpm }.average()
        val maxBpm = samples.maxOf { it.bpm }.toInt()
        val minBpm = samples.minOf { it.bpm }.toInt()
        val zone2Ratio = computeTimeWeightedZone2Ratio(samples, windowEnd, zone2Config)
        return HrSummary(avgBpm = avgBpm, maxBpm = maxBpm, minBpm = minBpm, zone2Ratio = zone2Ratio)
    }

    private fun computeTimeWeightedZone2Ratio(
        samples: List<HrSample>,
        windowEnd: Instant,
        zone2Config: HeartRateZone2Config
    ): Double? {
        if (samples.isEmpty()) return null
        if (samples.size == 1) {
            return if (zone2Config.contains(samples[0].bpm)) 1.0 else 0.0
        }

        var totalWeightSec = 0.0
        var zone2WeightSec = 0.0

        for (i in samples.indices) {
            val current = samples[i]
            val nextTime = if (i + 1 < samples.size) samples[i + 1].time else windowEnd
            val deltaSec = (Duration.between(current.time, nextTime).toMillis() / 1000.0)
                .coerceIn(0.0, MAX_HR_SAMPLE_WEIGHT_SECONDS)
            totalWeightSec += deltaSec
            if (zone2Config.contains(current.bpm)) {
                zone2WeightSec += deltaSec
            }
        }

        if (totalWeightSec <= 0.0) {
            val inZone2Count = samples.count { zone2Config.contains(it.bpm) }
            return inZone2Count.toDouble() / samples.size.toDouble()
        }

        return (zone2WeightSec / totalWeightSec).coerceIn(0.0, 1.0)
    }

    private fun resolveAverageCadence(
        cadenceSamples: List<CadenceSample>
    ): Double? {
        if (cadenceSamples.isNotEmpty()) {
            return cadenceSamples.map { it.spm }.average()
        }
        return null
    }

    private fun resolveTotalDistanceMeters(
        aggregatedDistanceMeters: Double?,
        segments: List<DistanceSegment>
    ): Double {
        if (aggregatedDistanceMeters != null && aggregatedDistanceMeters.isFinite() && aggregatedDistanceMeters >= 0.0) {
            return aggregatedDistanceMeters
        }
        return segments.sumOf { it.meters }.coerceAtLeast(0.0)
    }

    private fun buildMinuteSeries(
        startTime: Instant,
        endTime: Instant,
        hrSamples: List<HrSample>,
        cadenceSamples: List<CadenceSample>,
        distanceSegments: List<DistanceSegment>,
        zone2Config: HeartRateZone2Config
    ): List<MinuteBucket> {
        if (!endTime.isAfter(startTime)) return emptyList()

        val totalSeconds = Duration.between(startTime, endTime).seconds
        if (totalSeconds <= 0L) return emptyList()

        val bucketCount = ((totalSeconds + BUCKET_SECONDS - 1L) / BUCKET_SECONDS).toInt()
        val buckets = ArrayList<MinuteBucket>(bucketCount)

        for (minuteIndex in 0 until bucketCount) {
            val offsetSec = minuteIndex * BUCKET_SECONDS.toInt()
            val bucketStart = startTime.plusSeconds(offsetSec.toLong())
            val candidateEnd = bucketStart.plusSeconds(BUCKET_SECONDS)
            val bucketEnd = if (candidateEnd.isAfter(endTime)) endTime else candidateEnd
            val bucketDurationSec = (Duration.between(bucketStart, bucketEnd).toMillis() / 1000.0)
                .coerceAtLeast(0.0)

            val bucketHr = hrSamples.filter {
                !it.time.isBefore(bucketStart) && it.time.isBefore(bucketEnd)
            }
            val hrSummary = summarizeHeartRate(bucketHr, bucketEnd, zone2Config)

            val bucketCadence = cadenceSamples.filter {
                !it.time.isBefore(bucketStart) && it.time.isBefore(bucketEnd)
            }
            val avgCadence = if (bucketCadence.isNotEmpty()) {
                bucketCadence.map { it.spm }.average()
            } else {
                null
            }

            val distanceM = computeBucketDistanceMeters(distanceSegments, bucketStart, bucketEnd)
            val paceSecPerKm = computePaceSecPerKm(distanceM, bucketDurationSec)

            buckets.add(
                MinuteBucket(
                    minute = minuteIndex + 1,
                    offsetSec = offsetSec,
                    timestamp = bucketStart.toString(),
                    hr = hrSummary.avgBpm?.roundToInt(),
                    maxHr = hrSummary.maxBpm,
                    minHr = hrSummary.minBpm,
                    zone2Ratio = hrSummary.zone2Ratio?.let(::roundTo4),
                    paceSec = paceSecPerKm?.roundToInt(),
                    cadence = avgCadence?.roundToInt(),
                    distanceM = roundTo2(distanceM)
                )
            )
        }

        return buckets
    }

    private fun computeBucketDistanceMeters(
        segments: List<DistanceSegment>,
        bucketStart: Instant,
        bucketEnd: Instant
    ): Double {
        var total = 0.0
        for (segment in segments) {
            val segMillis = Duration.between(segment.startTime, segment.endTime).toMillis()
            if (segMillis <= 0L) {
                if (!segment.startTime.isBefore(bucketStart) && segment.startTime.isBefore(bucketEnd)) {
                    total += segment.meters
                }
                continue
            }
            val overlapStart = if (segment.startTime.isAfter(bucketStart)) segment.startTime else bucketStart
            val overlapEnd = if (segment.endTime.isBefore(bucketEnd)) segment.endTime else bucketEnd
            val overlapMillis = Duration.between(overlapStart, overlapEnd).toMillis()
            if (overlapMillis > 0L) {
                total += segment.meters * (overlapMillis.toDouble() / segMillis.toDouble())
            }
        }
        return total
    }

    private fun computePaceSecPerKm(distanceMeters: Double, durationSeconds: Double): Double? {
        if (!distanceMeters.isFinite() || !durationSeconds.isFinite()) return null
        if (distanceMeters <= 0.0 || durationSeconds <= 0.0) return null
        val pace = durationSeconds / (distanceMeters / 1000.0)
        return if (pace.isFinite()) pace else null
    }

    private fun roundTo1(value: Double): Double = round(value * 10.0) / 10.0

    private fun roundTo2(value: Double): Double = round(value * 100.0) / 100.0

    private fun roundTo3(value: Double): Double = round(value * 1000.0) / 1000.0

    private fun roundTo4(value: Double): Double = round(value * 10000.0) / 10000.0
}
