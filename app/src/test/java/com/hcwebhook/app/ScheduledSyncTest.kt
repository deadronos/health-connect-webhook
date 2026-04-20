package com.hcwebhook.app

import org.junit.Test
import org.junit.Assert.assertEquals

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
}
