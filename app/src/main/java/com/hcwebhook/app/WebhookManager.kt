package com.hcwebhook.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class WebhookManager(
    private val webhookConfigs: List<WebhookConfig>,
    private val context: Context? = null,
    private val dataType: String? = null,
    private val recordCount: Int? = null
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun postData(jsonPayload: String): Result<Unit> {
        if (webhookConfigs.isEmpty()) {
            return Result.failure(IllegalStateException("No webhook URLs configured"))
        }

        var lastFailure: Exception? = null

        // Try posting to all configured webhooks
        for (config in webhookConfigs) {
            val result = postToUrl(config, jsonPayload)
            if (result.isSuccess) {
                return result // Success if at least one webhook succeeds
            } else {
                lastFailure = result.exceptionOrNull() as? Exception ?: Exception("Unknown error")  
            }
        }

        return Result.failure(lastFailure ?: IOException("All webhook posts failed"))
    }

    private suspend fun postToUrl(config: WebhookConfig, jsonPayload: String): Result<Unit> {
        val timestamp = System.currentTimeMillis()
        var statusCode: Int? = null
        var success = false
        var errorMessage: String? = null

        return try {
            val requestBody = jsonPayload.toRequestBody(jsonMediaType)
            val requestBuilder = Request.Builder()
                .url(config.url)
                .post(requestBody)
            
            // Add custom headers
            config.headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }
            
            val request = requestBuilder.build()

            var lastException: Exception? = null
            for (attempt in 1..MAX_RETRIES) {
                try {
                    val response = client.newCall(request).execute()
                    statusCode = response.code
                    if (response.isSuccessful) {
                        success = true
                        logWebhookCall(config.url, timestamp, statusCode, true, null)
                        return Result.success(Unit)
                    } else {
                        lastException = IOException("HTTP ${response.code}: ${response.message}")
                        errorMessage = "HTTP ${response.code}: ${response.message}"
                    }
                } catch (e: IOException) {
                    lastException = e
                    errorMessage = e.message
                }

                if (attempt < MAX_RETRIES) {
                    // Exponential backoff
                    val delayMs = INITIAL_RETRY_DELAY_MS * (2.0.pow(attempt - 1).toLong())
                    kotlinx.coroutines.delay(delayMs)
                }
            }

            logWebhookCall(config.url, timestamp, statusCode, false, errorMessage)
            Result.failure(lastException ?: IOException("Max retries exceeded"))
        } catch (e: Exception) {
            logWebhookCall(config.url, timestamp, null, false, e.message)
            Result.failure(e)
        }
    }

    suspend fun postBatch(
        config: WebhookConfig,
        jsonPayload: String,
        idempotencyKey: String,
        batchIndex: Int,
        batchCount: Int
    ): BatchPostResult {
        val timestamp = System.currentTimeMillis()
        var statusCode: Int? = null
        var errorMessage: String? = null

        return try {
            val requestBody = jsonPayload.toRequestBody(jsonMediaType)
            val requestBuilder = Request.Builder()
                .url(config.url)
                .post(requestBody)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Batch-Index", batchIndex.toString())
                .header("X-Batch-Count", batchCount.toString())

            config.headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }

            val request = requestBuilder.build()

            var lastResult: BatchPostResult? = null
            for (attempt in 1..MAX_RETRIES) {
                try {
                    val response = client.newCall(request).execute()
                    statusCode = response.code
                    if (response.isSuccessful) {
                        logWebhookCall(config.url, timestamp, statusCode, true, null)
                        return BatchPostResult.Success(statusCode)
                    } else {
                        errorMessage = "HTTP ${response.code}: ${response.message}"
                        lastResult = if (response.code >= 500 || response.code == 429) {
                            BatchPostResult.TransientFailure(response.code, errorMessage)
                        } else {
                            BatchPostResult.PermanentFailure(response.code, errorMessage)
                        }
                    }
                } catch (e: IOException) {
                    errorMessage = e.message
                    lastResult = BatchPostResult.TransientFailure(null, errorMessage ?: "IOException")
                }

                if (attempt < MAX_RETRIES && lastResult is BatchPostResult.TransientFailure) {
                    val delayMs = INITIAL_RETRY_DELAY_MS * (2.0.pow(attempt - 1).toLong())
                    kotlinx.coroutines.delay(delayMs)
                } else if (lastResult is BatchPostResult.PermanentFailure) {
                    break
                }
            }

            logWebhookCall(config.url, timestamp, statusCode, false, errorMessage)
            lastResult ?: BatchPostResult.PermanentFailure(null, "Max retries exceeded")
        } catch (e: Exception) {
            logWebhookCall(config.url, timestamp, null, false, e.message)
            BatchPostResult.PermanentFailure(null, e.message ?: "Unknown error")
        }
    }

    sealed class BatchPostResult {
        data class Success(val statusCode: Int) : BatchPostResult()
        data class TransientFailure(val statusCode: Int?, val error: String) : BatchPostResult()
        data class PermanentFailure(val statusCode: Int?, val error: String) : BatchPostResult()
    }

    private fun logWebhookCall(
        url: String,
        timestamp: Long,
        statusCode: Int?,
        success: Boolean,
        errorMessage: String?
    ) {
        context?.let {
            val preferencesManager = PreferencesManager(it)
            val log = WebhookLog(
                id = UUID.randomUUID().toString(),
                timestamp = timestamp,
                url = url,
                statusCode = statusCode,
                success = success,
                errorMessage = errorMessage,
                dataType = dataType,
                recordCount = recordCount
            )
            preferencesManager.addWebhookLog(log)
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 60L
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
    }
}