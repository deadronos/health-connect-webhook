package com.hcwebhook.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

data class BatchPayload(
    val syncId: String,
    val batchId: String,
    val batchIndex: Int,
    val batchCount: Int,
    val windowStart: Instant,
    val windowEnd: Instant,
    val payload: String,
    val recordCount: Int,
    val maxTimestampsByType: Map<HealthDataType, Instant>
)

internal data class PayloadRecord(
    val type: HealthDataType,
    val metricKey: String,
    val eventTime: Instant,
    val payload: JsonObject
)

class HybridBatchPlanner(
    private val maxBodyBytes: Int = MAX_BODY_BYTES_DEFAULT,
    private val json: Json = Json
) {

    fun buildBatches(healthData: HealthData): Result<List<BatchPayload>> {
        val records = healthData.toPayloadRecords().sortedWith(compareBy<PayloadRecord> { it.eventTime }.thenBy { it.metricKey })
        if (records.isEmpty()) {
            return Result.success(emptyList())
        }

        val syncId = UUID.randomUUID().toString()
        val createdAt = Instant.now()

        val dayWindows = records.groupBy { it.eventTime.atZone(ZoneOffset.UTC).toLocalDate() }.toSortedMap()
        val plannedGroups = mutableListOf<List<PayloadRecord>>()

        for ((_, dayRecords) in dayWindows) {
            var current = mutableListOf<PayloadRecord>()
            for (record in dayRecords) {
                val candidate = current + record
                val candidatePayload = serializeBatchPayload(
                    records = candidate,
                    syncId = syncId,
                    batchIndex = 0,
                    batchCount = 1,
                    createdAt = createdAt,
                    windowDate = record.eventTime.atZone(ZoneOffset.UTC).toLocalDate()
                )

                if (byteSize(candidatePayload) <= maxBodyBytes) {
                    current.add(record)
                    continue
                }

                if (current.isEmpty()) {
                    return Result.failure(IllegalStateException("Single record exceeds MAX_BODY_BYTES=$maxBodyBytes"))
                }

                plannedGroups.add(current.toList())
                current = mutableListOf(record)
            }

            if (current.isNotEmpty()) {
                plannedGroups.add(current.toList())
            }
        }

        val batches = plannedGroups.mapIndexed { index, group ->
            val windowDate = group.first().eventTime.atZone(ZoneOffset.UTC).toLocalDate()
            val payload = serializeBatchPayload(
                records = group,
                syncId = syncId,
                batchIndex = index,
                batchCount = plannedGroups.size,
                createdAt = createdAt,
                windowDate = windowDate
            )

            if (byteSize(payload) > maxBodyBytes) {
                return Result.failure(IllegalStateException("Batch $index exceeds MAX_BODY_BYTES=$maxBodyBytes"))
            }

            BatchPayload(
                syncId = syncId,
                batchId = "$syncId:$index",
                batchIndex = index,
                batchCount = plannedGroups.size,
                windowStart = group.minOf { it.eventTime },
                windowEnd = group.maxOf { it.eventTime },
                payload = payload,
                recordCount = group.size,
                maxTimestampsByType = group
                    .groupBy { it.type }
                    .mapValues { (_, values) -> values.maxOf { it.eventTime } }
            )
        }

        return Result.success(batches)
    }

    private fun serializeBatchPayload(
        records: List<PayloadRecord>,
        syncId: String,
        batchIndex: Int,
        batchCount: Int,
        createdAt: Instant,
        windowDate: LocalDate
    ): String {
        val grouped = records.groupBy { it.metricKey }
        val jsonObject = buildJsonObject {
            put("schema_version", "2")
            put("strategy", "HYBRID")
            put("sync_id", syncId)
            put("batch_id", "$syncId:$batchIndex")
            put("batch_index", batchIndex)
            put("batch_count", batchCount)
            put("created_at", createdAt.toString())
            put("window_start", windowDate.atStartOfDay().toInstant(ZoneOffset.UTC).toString())
            put("window_end", windowDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString())
            put("max_body_bytes", maxBodyBytes)
            put("timestamp", createdAt.toString())
            put("app_version", "1.0")

            grouped.forEach { (metricKey, entries) ->
                putJsonArray(metricKey) {
                    entries.forEach { add(it.payload) }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), jsonObject)
    }

    private fun HealthData.toPayloadRecords(): List<PayloadRecord> {
        val records = mutableListOf<PayloadRecord>()

        steps.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.STEPS,
                    metricKey = "steps",
                    eventTime = it.endTime,
                    payload = buildJsonObject {
                        put("count", it.count)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                    }
                )
            )
        }

        sleep.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.SLEEP,
                    metricKey = "sleep",
                    eventTime = it.sessionEndTime,
                    payload = buildJsonObject {
                        put("session_end_time", it.sessionEndTime.toString())
                        put("duration_seconds", it.duration.seconds)
                        putJsonArray("stages") {
                            it.stages.forEach { stage ->
                                add(buildJsonObject {
                                    put("stage", stage.stage)
                                    put("start_time", stage.startTime.toString())
                                    put("end_time", stage.endTime.toString())
                                    put("duration_seconds", stage.duration.seconds)
                                })
                            }
                        }
                    }
                )
            )
        }

        heartRate.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.HEART_RATE,
                    metricKey = "heart_rate",
                    eventTime = it.time,
                    payload = buildJsonObject {
                        put("bpm", it.bpm)
                        put("time", it.time.toString())
                    }
                )
            )
        }

        heartRateVariability.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.HEART_RATE_VARIABILITY,
                    metricKey = "heart_rate_variability",
                    eventTime = it.time,
                    payload = buildJsonObject {
                        put("rmssd_millis", it.rmssdMillis)
                        put("time", it.time.toString())
                    }
                )
            )
        }

        distance.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.DISTANCE,
                    metricKey = "distance",
                    eventTime = it.endTime,
                    payload = buildJsonObject {
                        put("meters", it.meters)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                    }
                )
            )
        }

        activeCalories.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.ACTIVE_CALORIES,
                    metricKey = "active_calories",
                    eventTime = it.endTime,
                    payload = buildJsonObject {
                        put("calories", it.calories)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                    }
                )
            )
        }

        totalCalories.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.TOTAL_CALORIES,
                    metricKey = "total_calories",
                    eventTime = it.endTime,
                    payload = buildJsonObject {
                        put("calories", it.calories)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                    }
                )
            )
        }

        weight.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.WEIGHT,
                    metricKey = "weight",
                    eventTime = it.time,
                    payload = buildJsonObject {
                        put("kilograms", it.kilograms)
                        put("time", it.time.toString())
                    }
                )
            )
        }

        height.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.HEIGHT,
                    metricKey = "height",
                    eventTime = it.time,
                    payload = buildJsonObject {
                        put("meters", it.meters)
                        put("time", it.time.toString())
                    }
                )
            )
        }

        bloodPressure.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.BLOOD_PRESSURE,
                    metricKey = "blood_pressure",
                    eventTime = it.time,
                    payload = buildJsonObject {
                        put("systolic", it.systolic)
                        put("diastolic", it.diastolic)
                        put("time", it.time.toString())
                    }
                )
            )
        }

        bloodGlucose.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.BLOOD_GLUCOSE,
                    metricKey = "blood_glucose",
                    eventTime = it.time,
                    payload = buildJsonObject {
                        put("mmol_per_liter", it.mmolPerLiter)
                        put("time", it.time.toString())
                    }
                )
            )
        }

        oxygenSaturation.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.OXYGEN_SATURATION,
                    metricKey = "oxygen_saturation",
                    eventTime = it.time,
                    payload = buildJsonObject {
                        put("percentage", it.percentage)
                        put("time", it.time.toString())
                    }
                )
            )
        }

        bodyTemperature.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.BODY_TEMPERATURE,
                    metricKey = "body_temperature",
                    eventTime = it.time,
                    payload = buildJsonObject {
                        put("celsius", it.celsius)
                        put("time", it.time.toString())
                    }
                )
            )
        }

        respiratoryRate.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.RESPIRATORY_RATE,
                    metricKey = "respiratory_rate",
                    eventTime = it.time,
                    payload = buildJsonObject {
                        put("rate", it.rate)
                        put("time", it.time.toString())
                    }
                )
            )
        }

        restingHeartRate.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.RESTING_HEART_RATE,
                    metricKey = "resting_heart_rate",
                    eventTime = it.time,
                    payload = buildJsonObject {
                        put("bpm", it.bpm)
                        put("time", it.time.toString())
                    }
                )
            )
        }

        exercise.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.EXERCISE,
                    metricKey = "exercise",
                    eventTime = it.endTime,
                    payload = buildJsonObject {
                        put("type", it.type)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                        put("duration_seconds", it.duration.seconds)
                    }
                )
            )
        }

        hydration.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.HYDRATION,
                    metricKey = "hydration",
                    eventTime = it.endTime,
                    payload = buildJsonObject {
                        put("liters", it.liters)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                    }
                )
            )
        }

        nutrition.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.NUTRITION,
                    metricKey = "nutrition",
                    eventTime = it.endTime,
                    payload = buildJsonObject {
                        it.calories?.let { calories -> put("calories", calories) }
                        it.protein?.let { protein -> put("protein_grams", protein) }
                        it.carbs?.let { carbs -> put("carbs_grams", carbs) }
                        it.fat?.let { fat -> put("fat_grams", fat) }
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                    }
                )
            )
        }

        basalMetabolicRate.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.BASAL_METABOLIC_RATE,
                    metricKey = "basal_metabolic_rate",
                    eventTime = it.time,
                    payload = buildJsonObject {
                        put("watts", it.watts)
                        put("time", it.time.toString())
                    }
                )
            )
        }

        bodyFat.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.BODY_FAT,
                    metricKey = "body_fat",
                    eventTime = it.time,
                    payload = buildJsonObject {
                        put("percentage", it.percentage)
                        put("time", it.time.toString())
                    }
                )
            )
        }

        leanBodyMass.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.LEAN_BODY_MASS,
                    metricKey = "lean_body_mass",
                    eventTime = it.time,
                    payload = buildJsonObject {
                        put("kilograms", it.kilograms)
                        put("time", it.time.toString())
                    }
                )
            )
        }

        vo2Max.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.VO2_MAX,
                    metricKey = "vo2_max",
                    eventTime = it.time,
                    payload = buildJsonObject {
                        put("ml_per_kg_per_min", it.mlPerKgPerMin)
                        put("time", it.time.toString())
                    }
                )
            )
        }

        boneMass.forEach {
            records.add(
                PayloadRecord(
                    type = HealthDataType.BONE_MASS,
                    metricKey = "bone_mass",
                    eventTime = it.time,
                    payload = buildJsonObject {
                        put("kilograms", it.kilograms)
                        put("time", it.time.toString())
                    }
                )
            )
        }

        return records
    }

    private fun byteSize(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    companion object {
        const val MAX_BODY_BYTES_DEFAULT = 262_144
    }
}

