package com.hcwebhook.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ScheduledSyncTest {

    @Test
    fun getDisplayTime_withDoubleDigitHourAndMinute_returnsFormattedString() {
        val sync = ScheduledSync(hour = 10, minute = 30)
        assertEquals("10:30", sync.getDisplayTime())
    }

    @Test
    fun getDisplayTime_withSingleDigitHour_addsZeroPadding() {
        val sync = ScheduledSync(hour = 9, minute = 30)
        assertEquals("09:30", sync.getDisplayTime())
    }

    @Test
    fun getDisplayTime_withSingleDigitMinute_addsZeroPadding() {
        val sync = ScheduledSync(hour = 10, minute = 5)
        assertEquals("10:05", sync.getDisplayTime())
    }

    @Test
    fun getDisplayTime_withSingleDigitHourAndMinute_addsZeroPadding() {
        val sync = ScheduledSync(hour = 5, minute = 5)
        assertEquals("05:05", sync.getDisplayTime())
    }

    @Test
    fun getDisplayTime_withMidnight_returnsZeroedString() {
        val sync = ScheduledSync(hour = 0, minute = 0)
        assertEquals("00:00", sync.getDisplayTime())
    }

    @Test
    fun getDisplayTime_withEndOfDay_returnsFormattedString() {
        val sync = ScheduledSync(hour = 23, minute = 59)
        assertEquals("23:59", sync.getDisplayTime())
    }

    @Test
    fun getDisplayLabel_withNonBlankLabel_returnsLabel() {
        val sync = ScheduledSync(hour = 10, minute = 30, label = "Morning Sync")
        assertEquals("Morning Sync", sync.getDisplayLabel())
    }

    @Test
    fun getDisplayLabel_withEmptyLabel_returnsDisplayTime() {
        val sync = ScheduledSync(hour = 10, minute = 30, label = "")
        assertEquals("10:30", sync.getDisplayLabel())
    }

    @Test
    fun getDisplayLabel_withBlankLabel_returnsDisplayTime() {
        val sync = ScheduledSync(hour = 10, minute = 30, label = "   ")
        assertEquals("10:30", sync.getDisplayLabel())
    }

    @Test
    fun testCreateWithDefaultParameters() {
        val sync = ScheduledSync.create(hour = 14, minute = 30)

        assertEquals(14, sync.hour)
        assertEquals(30, sync.minute)
        assertEquals("", sync.label)
        assertTrue(sync.enabled)
        assertTrue(sync.id.isNotBlank())
    }

    @Test
    fun testCreateWithCustomParameters() {
        val sync = ScheduledSync.create(hour = 8, minute = 15, label = "Morning Sync", enabled = false)

        assertEquals(8, sync.hour)
        assertEquals(15, sync.minute)
        assertEquals("Morning Sync", sync.label)
        assertFalse(sync.enabled)
        assertTrue(sync.id.isNotBlank())
    }

    @Test
    fun testCreateGeneratesUniqueIds() {
        val sync1 = ScheduledSync.create(hour = 10, minute = 0)
        val sync2 = ScheduledSync.create(hour = 10, minute = 0)

        assertNotEquals(sync1.id, sync2.id)
    }

    @Test
    fun testGetDisplayTimePadsWithZeroes() {
        val sync1 = ScheduledSync(hour = 8, minute = 5)
        assertEquals("08:05", sync1.getDisplayTime())

        val sync2 = ScheduledSync(hour = 14, minute = 30)
        assertEquals("14:30", sync2.getDisplayTime())

        val sync3 = ScheduledSync(hour = 0, minute = 0)
        assertEquals("00:00", sync3.getDisplayTime())
    }

    @Test
    fun testGetDisplayLabel() {
        val syncWithLabel = ScheduledSync(hour = 8, minute = 30, label = "Custom Label")
        assertEquals("Custom Label", syncWithLabel.getDisplayLabel())

        val syncWithEmptyLabel = ScheduledSync(hour = 8, minute = 30, label = "")
        assertEquals("08:30", syncWithEmptyLabel.getDisplayLabel())

        val syncWithBlankLabel = ScheduledSync(hour = 8, minute = 30, label = "   ")
        assertEquals("08:30", syncWithBlankLabel.getDisplayLabel())
    }
}