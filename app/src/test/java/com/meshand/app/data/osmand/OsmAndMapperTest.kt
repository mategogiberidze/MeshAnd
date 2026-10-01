package com.meshand.app.data.osmand

import com.meshand.app.domain.model.MeshNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class OsmAndMapperTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val utc = ZoneOffset.UTC

    private fun node(
        lat: Double? = 41.7151,
        lon: Double? = 44.8271,
        lastSeen: Instant? = now.minusSeconds(60),
        own: Boolean = false,
    ) = MeshNode(
        id = 0x12345678L, shortName = "GIO", longName = "Giorgi",
        latitude = lat, longitude = lon, altitude = 850, batteryLevel = 78,
        snr = 7.5f, hopsAway = 1, lastSeen = lastSeen, isOwnNode = own,
    )

    @Test
    fun `node without position is not drawn`() {
        assertNull(OsmAndMapper.toSpec(node(lat = null), now, utc))
        assertNull(OsmAndMapper.toSpec(node(lon = null), now, utc))
    }

    @Test
    fun `maps node to point`() {
        val spec = OsmAndMapper.toSpec(node(), now, utc)!!
        assertEquals("!12345678", spec.id)
        assertEquals("GIO", spec.shortName)
        assertEquals("Giorgi", spec.fullName)
        assertEquals(41.7151, spec.latitude, 0.0)
        assertEquals(44.8271, spec.longitude, 0.0)
        assertEquals(OsmAndMapper.NODE_COLOR, spec.color)
        assertEquals(
            listOf("Altitude: 850 m", "Battery: 78%", "SNR: 7.5 dB", "Hops: 1", "Last seen: 11:59:00"),
            spec.details,
        )
        assertFalse(spec.stale)
    }

    @Test
    fun `own node uses its own color`() {
        val spec = OsmAndMapper.toSpec(node(own = true), now, utc)!!
        assertEquals(OsmAndMapper.OWN_NODE_COLOR, spec.color)
        assertTrue(spec.typeName.contains("this radio"))
    }

    @Test
    fun `missing names fall back to node id`() {
        val spec = OsmAndMapper.toSpec(node().copy(shortName = null, longName = null), now, utc)!!
        assertEquals("5678", spec.shortName)
        assertEquals("5678", spec.fullName)
    }

    @Test
    fun `stale after threshold or when never heard`() {
        val old = now.minus(OsmAndMapper.STALE_AFTER).minus(Duration.ofSeconds(1))
        assertTrue(OsmAndMapper.toSpec(node(lastSeen = old), now, utc)!!.stale)
        assertTrue(OsmAndMapper.toSpec(node(lastSeen = null), now, utc)!!.stale)
        assertFalse(OsmAndMapper.isStale(now.minus(OsmAndMapper.STALE_AFTER), now))
    }

    @Test
    fun `last seen on another day includes the date`() {
        val spec = OsmAndMapper.toSpec(node(lastSeen = Instant.parse("2026-09-30T08:15:00Z")), now, utc)!!
        assertEquals("Last seen: 2026-09-30 08:15", spec.details.last())
    }

    @Test
    fun `externally powered battery`() {
        val spec = OsmAndMapper.toSpec(node().copy(batteryLevel = MeshNode.BATTERY_POWERED), now, utc)!!
        assertTrue("Battery: powered" in spec.details)
    }
}
