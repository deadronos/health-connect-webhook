package com.hcwebhook.app

import org.junit.Assert.*
import org.junit.Test

class WebhookConfigTest {

    @Test
    fun fromUrl_createsConfigWithCorrectUrlAndEmptyHeaders() {
        val url = "https://example.com/webhook"
        val config = WebhookConfig.fromUrl(url)

        assertEquals(url, config.url)
        assertTrue(config.headers.isEmpty())
    }

    @Test
    fun getHeaderCount_returnsCorrectSize() {
        val config = WebhookConfig(
            url = "https://example.com",
            headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer token")
        )

        assertEquals(2, config.getHeaderCount())
    }

    @Test
    fun withHeader_addsNewHeader() {
        val config = WebhookConfig(url = "https://example.com")
        val updatedConfig = config.withHeader("X-Custom", "value")

        assertEquals(1, updatedConfig.headers.size)
        assertEquals("value", updatedConfig.headers["X-Custom"])
    }

    @Test
    fun withHeader_updatesExistingHeader() {
        val config = WebhookConfig(url = "https://example.com", headers = mapOf("X-Custom" to "old"))
        val updatedConfig = config.withHeader("X-Custom", "new")

        assertEquals(1, updatedConfig.headers.size)
        assertEquals("new", updatedConfig.headers["X-Custom"])
    }

    @Test
    fun withoutHeader_removesHeader() {
        val config = WebhookConfig(url = "https://example.com", headers = mapOf("X-Custom" to "value"))
        val updatedConfig = config.withoutHeader("X-Custom")

        assertTrue(updatedConfig.headers.isEmpty())
    }
}
