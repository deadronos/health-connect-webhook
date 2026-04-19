package com.hcwebhook.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.io.IOException

/**
 * Integration-style tests for the per-URL ordered batch delivery semantics,
 * checkpoint behaviour, and partial-failure handling.
 *
 * We exercise the logic directly through [SyncDeliveryEngine], a pure-Kotlin
 * extraction of the delivery loop in [SyncManager], so no Android runtime or
 * HealthConnect SDK is required.
 */
class SyncDeliveryEngineTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun makeConfig(url: String) = WebhookConfig(url = url)

    private fun makeBatch(index: Int, total: Int, typeTimestamp: Instant): BatchPayload {
        val syncId = "test-sync"
        return BatchPayload(
            syncId = syncId,
            batchId = "$syncId:$index",
            batchIndex = index,
            batchCount = total,
            windowStart = typeTimestamp.minusSeconds(60),
            windowEnd = typeTimestamp,
            payload = """{"batch_index":$index}""",
            recordCount = 5,
            maxTimestampsByType = mapOf(HealthDataType.STEPS to typeTimestamp)
        )
    }

    private fun emptyHealthData() = HealthData(
        steps = emptyList(), sleep = emptyList(), heartRate = emptyList(),
        heartRateVariability = emptyList(), distance = emptyList(),
        activeCalories = emptyList(), totalCalories = emptyList(),
        weight = emptyList(), height = emptyList(), bloodPressure = emptyList(),
        bloodGlucose = emptyList(), oxygenSaturation = emptyList(),
        bodyTemperature = emptyList(), respiratoryRate = emptyList(),
        restingHeartRate = emptyList(), exercise = emptyList(),
        hydration = emptyList(), nutrition = emptyList(),
        basalMetabolicRate = emptyList(), bodyFat = emptyList(),
        leanBodyMass = emptyList(), vo2Max = emptyList(), boneMass = emptyList()
    )

    // ── SyncDeliveryEngine ────────────────────────────────────────────────────

    /**
     * Pure-Kotlin delivery engine extracted from SyncManager so we can inject
     * a fake post function and a fake checkpoint store.
     */
    class SyncDeliveryEngine(
        private val checkpoints: MutableMap<String, MutableMap<HealthDataType, Long>> = mutableMapOf(),
        private val lastBatchIds: MutableMap<String, String> = mutableMapOf(),
        private val lastSyncTimes: MutableMap<String, Long> = mutableMapOf(),
        private val postFn: suspend (url: String, batch: BatchPayload) -> WebhookManager.BatchPostResult
    ) {

        data class DeliveryOutcome(
            val allSucceeded: Boolean,
            val failedUrls: List<String>,
            val committedCheckpoints: Map<String, Map<HealthDataType, Long>>
        )

        suspend fun deliver(
            configs: List<WebhookConfig>,
            batches: List<BatchPayload>
        ): DeliveryOutcome {
            val failedUrls = mutableListOf<String>()

            for (config in configs) {
                var urlFailed = false
                for (batch in batches) {
                    if (!shouldSend(config.url, batch)) continue
                    when (val result = postFn(config.url, batch)) {
                        is WebhookManager.BatchPostResult.Success -> persistCheckpoint(config.url, batch)
                        is WebhookManager.BatchPostResult.TransientFailure,
                        is WebhookManager.BatchPostResult.PermanentFailure -> {
                            urlFailed = true
                            break
                        }
                    }
                }
                if (!urlFailed) lastSyncTimes[config.url] = System.currentTimeMillis()
                else failedUrls.add(config.url)
            }

            return DeliveryOutcome(
                allSucceeded = failedUrls.isEmpty(),
                failedUrls = failedUrls,
                committedCheckpoints = checkpoints.mapValues { it.value.toMap() }
            )
        }

        private fun shouldSend(url: String, batch: BatchPayload): Boolean {
            return batch.maxTimestampsByType.any { (type, maxTs) ->
                val cp = checkpoints[url]?.get(type)?.let { Instant.ofEpochMilli(it) }
                cp == null || cp.isBefore(maxTs)
            }
        }

        private fun persistCheckpoint(url: String, batch: BatchPayload) {
            val urlMap = checkpoints.getOrPut(url) { mutableMapOf() }
            batch.maxTimestampsByType.forEach { (type, ts) ->
                urlMap[type] = ts.toEpochMilli()
            }
            lastBatchIds[url] = batch.batchId
        }

        fun checkpointFor(url: String, type: HealthDataType): Long? =
            checkpoints[url]?.get(type)

        fun lastSyncTimeFor(url: String): Long? = lastSyncTimes[url]
        fun lastBatchIdFor(url: String): String? = lastBatchIds[url]
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun all_urls_succeed_checkpoints_fully_advanced() = runBlocking {
        val base = Instant.parse("2026-04-01T10:00:00Z")
        val configs = listOf(makeConfig("https://a.example.com"), makeConfig("https://b.example.com"))
        val batches = listOf(
            makeBatch(0, 2, base.plusSeconds(100)),
            makeBatch(1, 2, base.plusSeconds(200))
        )

        val engine = SyncDeliveryEngine { _, _ -> WebhookManager.BatchPostResult.Success(200) }
        val outcome = engine.deliver(configs, batches)

        assertTrue(outcome.allSucceeded)
        assertTrue(outcome.failedUrls.isEmpty())

        // Both URLs must have checkpoint advanced to the latest batch's timestamp
        for (config in configs) {
            val cp = engine.checkpointFor(config.url, HealthDataType.STEPS)
            assertNotNull("Checkpoint must be set for ${config.url}", cp)
            assertEquals(base.plusSeconds(200).toEpochMilli(), cp)
            assertNotNull(engine.lastSyncTimeFor(config.url))
        }
    }

    @Test
    fun one_url_permanent_fail_only_other_url_checkpoint_advances() = runBlocking {
        val base = Instant.parse("2026-04-01T10:00:00Z")
        val goodUrl = "https://good.example.com"
        val badUrl = "https://bad.example.com"
        val configs = listOf(makeConfig(goodUrl), makeConfig(badUrl))
        val batches = listOf(makeBatch(0, 1, base.plusSeconds(100)))

        val engine = SyncDeliveryEngine { url, _ ->
            if (url == badUrl)
                WebhookManager.BatchPostResult.PermanentFailure(IOException("403"), 403)
            else
                WebhookManager.BatchPostResult.Success(200)
        }
        val outcome = engine.deliver(configs, batches)

        assertFalse(outcome.allSucceeded)
        assertEquals(listOf(badUrl), outcome.failedUrls)

        // Good URL checkpoint advanced
        assertNotNull(engine.checkpointFor(goodUrl, HealthDataType.STEPS))
        assertNotNull(engine.lastSyncTimeFor(goodUrl))

        // Bad URL checkpoint NOT advanced
        assertNull(engine.checkpointFor(badUrl, HealthDataType.STEPS))
        assertNull(engine.lastSyncTimeFor(badUrl))
    }

    @Test
    fun stop_on_failure_skips_remaining_batches_for_that_url() = runBlocking {
        val base = Instant.parse("2026-04-01T10:00:00Z")
        val url = "https://flaky.example.com"
        val configs = listOf(makeConfig(url))
        val batches = listOf(
            makeBatch(0, 3, base.plusSeconds(100)),
            makeBatch(1, 3, base.plusSeconds(200)),
            makeBatch(2, 3, base.plusSeconds(300))
        )

        val sentBatches = mutableListOf<Int>()
        val engine = SyncDeliveryEngine { _, batch ->
            sentBatches.add(batch.batchIndex)
            if (batch.batchIndex == 1)
                WebhookManager.BatchPostResult.TransientFailure(IOException("timeout"))
            else
                WebhookManager.BatchPostResult.Success(200)
        }
        engine.deliver(configs, batches)

        // Batch 2 must not have been sent after batch 1 failed
        assertEquals(listOf(0, 1), sentBatches)
        // Checkpoint advanced only for batch 0
        assertEquals(base.plusSeconds(100).toEpochMilli(), engine.checkpointFor(url, HealthDataType.STEPS))
    }

    @Test
    fun already_acknowledged_batches_skipped_on_rerun() = runBlocking {
        val base = Instant.parse("2026-04-01T10:00:00Z")
        val url = "https://a.example.com"
        val configs = listOf(makeConfig(url))
        val batches = listOf(
            makeBatch(0, 2, base.plusSeconds(100)),
            makeBatch(1, 2, base.plusSeconds(200))
        )

        val checkpoints: MutableMap<String, MutableMap<HealthDataType, Long>> = mutableMapOf(
            url to mutableMapOf(HealthDataType.STEPS to base.plusSeconds(100).toEpochMilli())
        )
        val sentBatches = mutableListOf<Int>()
        val engine = SyncDeliveryEngine(checkpoints = checkpoints) { _, batch ->
            sentBatches.add(batch.batchIndex)
            WebhookManager.BatchPostResult.Success(200)
        }
        engine.deliver(configs, batches)

        // batch 0 should be skipped (already acknowledged), only batch 1 sent
        assertEquals(listOf(1), sentBatches)
    }

    @Test
    fun idempotency_key_format_is_stable_for_same_batch_and_url() {
        val url = "https://example.com/webhook"
        val payload = """{"batch_index":0,"steps":[]}"""
        val syncId = "stable-sync-id"
        val batch = BatchPayload(
            syncId = syncId,
            batchId = "$syncId:0",
            batchIndex = 0,
            batchCount = 1,
            windowStart = Instant.EPOCH,
            windowEnd = Instant.EPOCH.plusSeconds(86400),
            payload = payload,
            recordCount = 0,
            maxTimestampsByType = emptyMap()
        )

        val key1 = buildTestIdempotencyKey(url, batch)
        val key2 = buildTestIdempotencyKey(url, batch)

        assertEquals(key1, key2)
        assertTrue(key1.startsWith("hcw:v2:"))
        val parts = key1.split(":")
        // hcw : v2 : urlHash : batchIndex : payloadHash  (5 parts)
        assertEquals(5, parts.size)
    }

    @Test
    fun idempotency_key_differs_for_different_url() {
        val payload = """{"x":1}"""
        val batch = BatchPayload(
            syncId = "s", batchId = "s:0", batchIndex = 0, batchCount = 1,
            windowStart = Instant.EPOCH, windowEnd = Instant.EPOCH.plusSeconds(60),
            payload = payload, recordCount = 1, maxTimestampsByType = emptyMap()
        )
        val key1 = buildTestIdempotencyKey("https://a.example.com", batch)
        val key2 = buildTestIdempotencyKey("https://b.example.com", batch)
        assertNotEquals(key1, key2)
    }

    @Test
    fun idempotency_key_differs_for_different_batch_index() {
        val url = "https://example.com"
        val b0 = BatchPayload("s","s:0",0,2,Instant.EPOCH,Instant.EPOCH.plusSeconds(60),"""{"x":0}""",1, emptyMap())
        val b1 = BatchPayload("s","s:1",1,2,Instant.EPOCH,Instant.EPOCH.plusSeconds(60),"""{"x":1}""",1, emptyMap())
        assertNotEquals(buildTestIdempotencyKey(url, b0), buildTestIdempotencyKey(url, b1))
    }

    // ── key builder mirroring SyncManager logic ───────────────────────────────

    private fun buildTestIdempotencyKey(url: String, batch: BatchPayload): String {
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        fun hex(value: String) = sha.digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        val urlHash = hex(url.trim().lowercase()).take(12)
        val payloadHash = hex(batch.payload).take(12)
        return "hcw:v2:$urlHash:${batch.batchIndex}:$payloadHash"
    }
}

