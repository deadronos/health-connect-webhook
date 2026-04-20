package com.hcwebhook.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WebhookConfigTest {

    @Test
    fun `withHeader adds header to empty config`() {
        val config = WebhookConfig.fromUrl("https://example.com")
        val updatedConfig = config.withHeader("Authorization", "Bearer token")

        assertEquals(1, updatedConfig.headers.size)
        assertEquals("Bearer token", updatedConfig.headers["Authorization"])
    }

    @Test
    fun `withHeader adds header to config with existing headers`() {
        val config = WebhookConfig.fromUrl("https://example.com")
            .withHeader("Content-Type", "application/json")

        val updatedConfig = config.withHeader("Authorization", "Bearer token")

        assertEquals(2, updatedConfig.headers.size)
        assertEquals("application/json", updatedConfig.headers["Content-Type"])
        assertEquals("Bearer token", updatedConfig.headers["Authorization"])
    }

    @Test
    fun `withHeader updates existing header`() {
        val config = WebhookConfig.fromUrl("https://example.com")
            .withHeader("Authorization", "Bearer old-token")

        val updatedConfig = config.withHeader("Authorization", "Bearer new-token")

        assertEquals(1, updatedConfig.headers.size)
        assertEquals("Bearer new-token", updatedConfig.headers["Authorization"])
    }
}
