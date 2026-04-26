package com.hcwebhook.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PreferencesManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var preferencesManager: PreferencesManager

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        preferencesManager = PreferencesManager(fakePrefs)
    }

    @Test
    fun exportSettings_capturesCurrentValues() {
        // Arrange
        val configs = listOf(
            WebhookConfig(url = "https://example.com/1", headers = mapOf("Auth" to "token1")),
            WebhookConfig(url = "https://example.com/2")
        )
        preferencesManager.setWebhookConfigs(configs)

        val dataTypes = setOf(HealthDataType.STEPS, HealthDataType.HEART_RATE)
        preferencesManager.setEnabledDataTypes(dataTypes)

        preferencesManager.setSyncMode(SyncMode.SCHEDULED)
        preferencesManager.setSyncIntervalMinutes(30)

        val scheduledSyncs = listOf(
            ScheduledSync.create(7, 0, "Breakfast"),
            ScheduledSync.create(19, 0, "Dinner")
        )
        preferencesManager.setScheduledSyncs(scheduledSyncs)

        // Act
        val export = preferencesManager.exportSettings()

        // Assert
        assertEquals(configs, export.webhookConfigs)
        assertEquals(dataTypes.map { it.name }.toSet(), export.enabledDataTypes.toSet())
        assertEquals(SyncMode.SCHEDULED.name, export.syncMode)
        assertEquals(30, export.syncIntervalMinutes)
        assertEquals(scheduledSyncs.size, export.scheduledSyncs.size)
        assertEquals(scheduledSyncs[0].hour, export.scheduledSyncs[0].hour)
        assertEquals(scheduledSyncs[1].label, export.scheduledSyncs[1].label)
        assertTrue(export.exportedAt > 0)
    }

    @Test
    fun importSettings_restoresValues() {
        // Arrange
        val export = SettingsExport(
            webhookConfigs = listOf(WebhookConfig(url = "https://imported.com")),
            enabledDataTypes = listOf(HealthDataType.SLEEP.name, HealthDataType.DISTANCE.name),
            syncMode = SyncMode.INTERVAL.name,
            syncIntervalMinutes = 15,
            scheduledSyncs = listOf(ScheduledSync.create(12, 0, "Noon"))
        )

        // Act
        preferencesManager.importSettings(export)

        // Assert
        assertEquals(export.webhookConfigs, preferencesManager.getWebhookConfigs())
        assertEquals(setOf(HealthDataType.SLEEP, HealthDataType.DISTANCE), preferencesManager.getEnabledDataTypes())
        assertEquals(SyncMode.INTERVAL, preferencesManager.getSyncMode())
        assertEquals(15, preferencesManager.getSyncIntervalMinutes())
        val restoredSyncs = preferencesManager.getScheduledSyncs()
        assertEquals(1, restoredSyncs.size)
        assertEquals(12, restoredSyncs[0].hour)
        assertEquals("Noon", restoredSyncs[0].label)
    }

    @Test
    fun exportSettings_withDefaults() {
        // Act
        val export = preferencesManager.exportSettings()

        // Assert
        assertTrue(export.webhookConfigs.isEmpty())
        assertTrue(export.enabledDataTypes.isEmpty())
        assertEquals(SyncMode.INTERVAL.name, export.syncMode)
        assertEquals(60, export.syncIntervalMinutes)
        // Default scheduled syncs are Morning (8:00) and Evening (21:00)
        assertEquals(2, export.scheduledSyncs.size)
    }

    @Test
    fun importSettings_handlesInvalidDataGracefully() {
        // Arrange
        val export = SettingsExport(
            enabledDataTypes = listOf("INVALID_TYPE", "STEPS"),
            syncMode = "UNKNOWN_MODE",
            webhookConfigs = emptyList(),
            syncIntervalMinutes = 45,
            scheduledSyncs = emptyList()
        )

        // Act
        preferencesManager.importSettings(export)

        // Assert
        assertEquals(setOf(HealthDataType.STEPS), preferencesManager.getEnabledDataTypes())
        assertEquals(SyncMode.INTERVAL, preferencesManager.getSyncMode()) // Should fallback to default
        assertEquals(45, preferencesManager.getSyncIntervalMinutes())
    }
}
