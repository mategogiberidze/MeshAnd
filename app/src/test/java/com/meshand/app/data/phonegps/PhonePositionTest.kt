package com.meshand.app.data.phonegps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Position

class PhonePositionTest {
    private val now = 1_790_000_000_000L

    @Test
    fun buildsScaledPositionMarkedExternal() {
        val p = PhonePosition.build(41.7151234, 44.8270987, altitudeHae = 512.6, altitudeMsl = 490.2, timeMillis = now)
        assertEquals(417151234, p.latitude_i)
        assertEquals(448270987, p.longitude_i)
        assertEquals(490, p.altitude)
        assertEquals(513, p.altitude_hae)
        assertEquals((now / 1000).toInt(), p.time)
        assertEquals(Position.LocSource.LOC_EXTERNAL, p.location_source)
    }

    @Test
    fun negativeCoordinatesRoundCorrectly() {
        val p = PhonePosition.build(-33.8688197, -151.2092955, null, null, now)
        assertEquals(-338688197, p.latitude_i)
        assertEquals(-1512092955, p.longitude_i)
    }

    @Test
    fun altitudeFallsBackToHaeAndCanBeMissing() {
        assertEquals(700, PhonePosition.build(41.7, 44.8, 700.4, null, now).altitude)
        val none = PhonePosition.build(41.7, 44.8, null, null, now)
        assertNull(none.altitude)
        assertNull(none.altitude_hae)
    }

    @Test
    fun usableFix() {
        assertTrue(PhonePosition.isUsable(8f, fixAgeMs = 1_000, lastSentAtMs = null, nowMs = now))
        assertTrue(PhonePosition.isUsable(100f, 60_000, now - 15_000, now))
    }

    @Test
    fun rejectsInaccurateOrUnknownAccuracy() {
        assertFalse(PhonePosition.isUsable(150f, 1_000, null, now))
        assertFalse(PhonePosition.isUsable(null, 1_000, null, now))
    }

    @Test
    fun rejectsOldOrFutureFixes() {
        assertFalse(PhonePosition.isUsable(8f, 61_000, null, now))
        assertFalse(PhonePosition.isUsable(8f, -1, null, now))
    }

    @Test
    fun rejectsSendingTooSoon() {
        assertFalse(PhonePosition.isUsable(8f, 1_000, now - 14_999, now))
    }
}
