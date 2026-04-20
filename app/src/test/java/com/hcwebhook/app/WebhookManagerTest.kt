package com.hcwebhook.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookManagerTest {

    @Test
    fun postData_withEmptyConfigs_returnsFailure() = runBlocking {
        // Arrange
        val manager = WebhookManager(webhookConfigs = emptyList())
        val jsonPayload = "{}"

        // Act
        val result = manager.postData(jsonPayload)

        // Assert
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is IllegalStateException)
        assertEquals("No webhook URLs configured", exception?.message)
    }
}
