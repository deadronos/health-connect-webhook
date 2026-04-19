package com.hcwebhook.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class HybridBatchPlannerTest {

    @Test
    fun planner_splits_batches_under_max_body_bytes() {
        val base = Instant.parse("2026-04-01T00:00:00Z")
        val healthData = emptyHealthData(
            steps = (0 until 120).map { index ->
                val time = base.plusSeconds(index.toLong() * 60)
                StepsData(count = (index + 1).toLong(), startTime = time.minusSeconds(60), endTime = time)
            }
        )

        val planner = HybridBatchPlanner(maxBodyBytes = 1024)
        val result = planner.buildBatches(healthData)

        assertTrue(result.isSuccess)
        val batches = result.getOrThrow()
        assertTrue(batches.size > 1)
        assertTrue(batches.all { it.payload.toByteArray().size <= 1024 })
        assertEquals((0 until batches.size).toList(), batches.map { it.batchIndex })
        assertTrue(batches.all { it.batchCount == batches.size })
    }

    @Test
    fun planner_has_stable_batch_boundaries_for_same_input() {
        val base = Instant.parse("2026-04-01T00:00:00Z")
        val healthData = emptyHealthData(
            heartRate = (0 until 80).map { index ->
                HeartRateData(bpm = (60 + index).toLong(), time = base.plusSeconds(index.toLong() * 30))
            }
        )

        val planner = HybridBatchPlanner(maxBodyBytes = 900)
        val first = planner.buildBatches(healthData).getOrThrow()
        val second = planner.buildBatches(healthData).getOrThrow()

        assertEquals(first.map { it.recordCount }, second.map { it.recordCount })
        assertEquals(first.map { it.windowStart }, second.map { it.windowStart })
        assertEquals(first.map { it.windowEnd }, second.map { it.windowEnd })
    }

    @Test
    fun planner_fails_when_single_record_cannot_fit() {
        val base = Instant.parse("2026-04-01T00:00:00Z")
        val healthData = emptyHealthData(
            sleep = listOf(
                SleepData(
                    sessionEndTime = base,
                    duration = Duration.ofHours(8),
                    stages = (0 until 8).map { stage ->
                        val start = base.minusSeconds((stage + 1L) * 1800)
                        SleepStage(
                            stage = "stage-$stage",
                            startTime = start,
                            endTime = start.plusSeconds(1800),
                            duration = Duration.ofMinutes(30)
                        )
                    }
                )
            )
        )

        val planner = HybridBatchPlanner(maxBodyBytes = 200)
        val result = planner.buildBatches(healthData)

        assertTrue(result.isFailure)
    }

    private fun emptyHealthData(
        steps: List<StepsData> = emptyList(),
        sleep: List<SleepData> = emptyList(),
        heartRate: List<HeartRateData> = emptyList()
    ): HealthData {
        return HealthData(
            steps = steps,
            sleep = sleep,
            heartRate = heartRate,
            heartRateVariability = emptyList(),
            distance = emptyList(),
            activeCalories = emptyList(),
            totalCalories = emptyList(),
            weight = emptyList(),
            height = emptyList(),
            bloodPressure = emptyList(),
            bloodGlucose = emptyList(),
            oxygenSaturation = emptyList(),
            bodyTemperature = emptyList(),
            respiratoryRate = emptyList(),
            restingHeartRate = emptyList(),
            exercise = emptyList(),
            hydration = emptyList(),
            nutrition = emptyList(),
            basalMetabolicRate = emptyList(),
            bodyFat = emptyList(),
            leanBodyMass = emptyList(),
            vo2Max = emptyList(),
            boneMass = emptyList()
        )
    }
}

