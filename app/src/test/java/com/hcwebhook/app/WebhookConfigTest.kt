package com.hcwebhook.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WebhookConfigTest {

    @Test
    fun `getHeaderCount returns 0 for empty headers`() {
        val config = WebhookConfig(url = "https://example.com", headers = emptyMap())
        assertEquals(0, config.getHeaderCount())
    }

    @Test
    fun `getHeaderCount returns correct size for populated headers`() {
        val config = WebhookConfig(
            url = "https://example.com",
            headers = mapOf("Authorization" to "Bearer token", "Content-Type" to "application/json")
        )
        assertEquals(2, config.getHeaderCount())
    }
}
