package com.meshand.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class TimeFormatTest {
    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-05T12:00:00Z")

    @Test
    fun `today shows the clock, other days the date`() {
        assertEquals("11:55", formatDateTime(Instant.parse("2026-10-05T11:55:00Z"), now, utc))
        assertEquals("4 Oct 23:10", formatDateTime(Instant.parse("2026-10-04T23:10:00Z"), now, utc))
        assertEquals("31 Dec 2025 09:00", formatDateTime(Instant.parse("2025-12-31T09:00:00Z"), now, utc))
    }

    @Test
    fun `when combines real time and age`() {
        assertEquals("11:55 (5 min ago)", formatWhen(Instant.parse("2026-10-05T11:55:00Z"), now, utc))
    }
}
