package com.myrunsync.config

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncScheduleConfigTest {

    @Test
    fun `clampIntervalMinutes enforces WorkManager 15 minute minimum`() {
        assertEquals(15L, SyncScheduleConfig.clampIntervalMinutes(1L))
        assertEquals(15L, SyncScheduleConfig.clampIntervalMinutes(15L))
        assertEquals(60L, SyncScheduleConfig.clampIntervalMinutes(60L))
        assertEquals(1440L, SyncScheduleConfig.clampIntervalMinutes(1440L))
    }

    @Test
    fun `indexForMinutes returns matching index or defaults to 1 hour option`() {
        assertEquals(0, SyncScheduleConfig.indexForMinutes(15L))
        assertEquals(2, SyncScheduleConfig.indexForMinutes(60L))
        assertEquals(6, SyncScheduleConfig.indexForMinutes(1440L))
        assertEquals(2, SyncScheduleConfig.indexForMinutes(45L))
    }

    @Test
    fun `labelForMinutes returns option label or fallback minutes text`() {
        assertEquals("15분", SyncScheduleConfig.labelForMinutes(15L))
        assertEquals("1시간", SyncScheduleConfig.labelForMinutes(60L))
        assertEquals("24시간", SyncScheduleConfig.labelForMinutes(1440L))
        assertEquals("45분", SyncScheduleConfig.labelForMinutes(45L))
        assertEquals("15분", SyncScheduleConfig.labelForMinutes(5L))
    }
}
