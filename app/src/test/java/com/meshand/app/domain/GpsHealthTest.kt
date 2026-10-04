package com.meshand.app.domain

import com.meshand.app.domain.GpsHealth.Level
import com.meshand.app.domain.model.MeshNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class GpsHealthTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")

    private fun minutesAgo(m: Long) = now.minus(Duration.ofMinutes(m))

    private fun node(
        lat: Double? = 41.7,
        fixTime: Instant? = null,
        changedAt: Instant? = null,
        reportedAt: Instant? = null,
        sats: Int? = null,
    ) = MeshNode(
        id = 1, shortName = null, longName = null, latitude = lat, longitude = lat?.let { 44.8 },
        altitude = null, batteryLevel = null, snr = null, hopsAway = null, lastSeen = now,
        isOwnNode = true, fixTime = fixTime, satellites = sats,
        positionChangedAt = changedAt, positionReportedAt = reportedAt,
    )

    @Test
    fun `no own node or no position means no GPS`() {
        assertEquals(Level.NONE, GpsHealth.evaluate(null, now).level)
        assertEquals(Level.NONE, GpsHealth.evaluate(node(lat = null), now).level)
    }

    @Test
    fun `fix time wins and is exact`() {
        val fresh = GpsHealth.evaluate(node(fixTime = minutesAgo(3), changedAt = minutesAgo(30), sats = 9), now)
        assertEquals(Level.GOOD, fresh.level)
        assertEquals(9, fresh.satellites)
        assertTrue(fresh.exact)
        assertEquals(Level.STALE, GpsHealth.evaluate(node(fixTime = minutesAgo(40)), now).level)
    }

    @Test
    fun `without fix time the last position change decides`() {
        val moving = GpsHealth.evaluate(node(changedAt = minutesAgo(5)), now)
        assertEquals(Level.GOOD, moving.level)
        assertFalse(moving.exact)
        assertEquals(Level.STALE, GpsHealth.evaluate(node(changedAt = minutesAgo(25)), now).level)
    }

    @Test
    fun `implausible fix time is ignored, nothing known means unknown`() {
        val bogus = GpsHealth.evaluate(node(fixTime = Instant.parse("1970-01-02T00:00:00Z"), changedAt = minutesAgo(5)), now)
        assertEquals(minutesAgo(5), bogus.fixTime)
        val unknown = GpsHealth.evaluate(node(), now)
        assertEquals(Level.UNKNOWN, unknown.level)
        assertNull(unknown.fixTime)
    }

    @Test
    fun `stuck when reports keep coming with an old position`() {
        assertTrue(PositionAge.isStuck(node(changedAt = minutesAgo(40), reportedAt = minutesAgo(2)), now))
        assertFalse(PositionAge.isStuck(node(changedAt = minutesAgo(12), reportedAt = minutesAgo(2)), now))
        // A radio that simply hasn't reported for a while isn't "stuck".
        assertFalse(PositionAge.isStuck(node(changedAt = minutesAgo(40), reportedAt = minutesAgo(40)), now))
        assertFalse(PositionAge.isStuck(node(reportedAt = minutesAgo(2)), now))
    }
}
