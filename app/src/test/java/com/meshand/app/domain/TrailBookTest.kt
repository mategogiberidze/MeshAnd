package com.meshand.app.domain

import com.meshand.app.domain.model.MeshNode
import com.meshand.app.domain.model.TrailPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class TrailBookTest {
    private val t0 = Instant.parse("2026-10-03T10:00:00Z")

    private fun node(id: Long, lat: Double?, lon: Double?, seen: Instant?) = MeshNode(
        id = id, shortName = null, longName = null, latitude = lat, longitude = lon, altitude = 500,
        batteryLevel = null, snr = null, hopsAway = null, lastSeen = seen,
    )

    @Test
    fun `records a point only when the position changes`() {
        val book = TrailBook()
        assertTrue(book.record(listOf(node(1, 41.70, 44.80, t0)), t0))
        assertFalse(book.record(listOf(node(1, 41.70, 44.80, t0.plusSeconds(60))), t0.plusSeconds(60)))
        assertTrue(book.record(listOf(node(1, 41.71, 44.80, t0.plusSeconds(120))), t0.plusSeconds(120)))
        val trail = book.snapshot().getValue(1)
        assertEquals(2, trail.size)
        assertEquals(41.71, trail.last().latitude, 0.0)
        assertEquals(t0.plusSeconds(120), trail.last().time)
    }

    @Test
    fun `nodes without position are ignored, older data is not appended`() {
        val book = TrailBook()
        assertFalse(book.record(listOf(node(1, null, null, t0)), t0))
        book.record(listOf(node(2, 41.0, 44.0, t0)), t0)
        assertFalse(book.record(listOf(node(2, 42.0, 45.0, t0.minusSeconds(10))), t0))
        assertEquals(1, book.snapshot().getValue(2).size)
    }

    @Test
    fun `prune drops points older than the window and empty trails`() {
        val book = TrailBook()
        book.record(listOf(node(1, 41.0, 44.0, t0)), t0)
        book.record(listOf(node(1, 41.1, 44.0, t0.plus(Duration.ofMinutes(50)))), t0)
        book.record(listOf(node(2, 40.0, 43.0, t0)), t0)
        val now = t0.plus(Duration.ofMinutes(70))
        assertTrue(book.prune(Duration.ofHours(1), now))
        val trails = book.snapshot()
        assertEquals(1, trails.getValue(1).size)
        assertFalse(2L in trails)
    }

    @Test
    fun `point count per node is capped`() {
        val book = TrailBook(maxPointsPerNode = 3)
        repeat(5) { i -> book.record(listOf(node(1, 41.0 + i, 44.0, t0.plusSeconds(i.toLong()))), t0) }
        val trail = book.snapshot().getValue(1)
        assertEquals(3, trail.size)
        assertEquals(43.0, trail.first().latitude, 0.0)
    }

    @Test
    fun `reset forgets one node's trail and it restarts from the next position`() {
        val book = TrailBook()
        book.record(listOf(node(1, 41.0, 44.0, t0), node(2, 40.0, 43.0, t0)), t0)
        book.record(listOf(node(1, 41.1, 44.0, t0.plusSeconds(60))), t0)
        book.reset(1)
        assertFalse(1L in book.snapshot())
        assertEquals(1, book.snapshot().getValue(2).size)
        book.record(listOf(node(1, 41.1, 44.0, t0.plusSeconds(120))), t0)
        assertEquals(1, book.snapshot().getValue(1).size)
    }

    @Test
    fun `restore replaces trails and keeps recording after them`() {
        val book = TrailBook()
        val t = Instant.parse("2026-10-05T09:00:00Z")
        val saved = mapOf(9L to listOf(TrailPoint(1.0, 2.0, null, t)))
        book.restore(saved)
        assertEquals(saved, book.snapshot())
        book.restore(emptyMap())
        assertEquals(emptyMap<Long, Any>(), book.snapshot())
    }
}
