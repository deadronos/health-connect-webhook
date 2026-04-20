package com.hcwebhook.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookConfigTest {

    @Test
    fun `withoutHeader removes existing header`() {
        val config = WebhookConfig(
            url = "https://example.com",
            headers = mapOf("Authorization" to "Bearer token", "Content-Type" to "application/json")
        )

        val newConfig = config.withoutHeader("Authorization")

        assertEquals(1, newConfig.headers.size)
        assertEquals(mapOf("Content-Type" to "application/json"), newConfig.headers)
    }

    @Test
    fun `withoutHeader handles non-existent header`() {
        val config = WebhookConfig(
            url = "https://example.com",
            headers = mapOf("Content-Type" to "application/json")
        )

        val newConfig = config.withoutHeader("Authorization")

        assertEquals(1, newConfig.headers.size)
        assertEquals(mapOf("Content-Type" to "application/json"), newConfig.headers)
    }

    @Test
    fun `withHeader adds new header`() {
        val config = WebhookConfig(url = "https://example.com")

        val newConfig = config.withHeader("Authorization", "Bearer token")

        assertEquals(1, newConfig.headers.size)
        assertEquals(mapOf("Authorization" to "Bearer token"), newConfig.headers)
    }

    @Test
    fun `withHeader updates existing header`() {
        val config = WebhookConfig(
            url = "https://example.com",
            headers = mapOf("Authorization" to "Bearer old-token")
        )

        val newConfig = config.withHeader("Authorization", "Bearer new-token")

        assertEquals(1, newConfig.headers.size)
        assertEquals(mapOf("Authorization" to "Bearer new-token"), newConfig.headers)
    }

    @Test
    fun `fromUrl creates config with empty headers`() {
        val url = "https://example.com"
        val config = WebhookConfig.fromUrl(url)

        assertEquals(url, config.url)
        assertEquals(0, config.headers.size)
        assertTrue(config.headers.isEmpty())
    }
}
