package com.meshand.app.data.storage

import com.meshand.app.domain.model.Pin
import com.meshand.app.domain.model.TrailPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class StoreCodecTest {
    private val t0 = Instant.parse("2026-10-05T09:00:00.123Z")

    @Test
    fun `trails round-trip exactly`() {
        val trails = mapOf(
            0xFFFFFFF0L to listOf(TrailPoint(41.7500123, 44.7712456, 850, t0), TrailPoint(41.7501, 44.7713, null, t0.plusSeconds(60))),
            7L to listOf(TrailPoint(-33.8688, -70.0, -5, t0)),
        )
        assertEquals(trails, StoreCodec.decodeTrails(StoreCodec.encodeTrails(trails)))
    }

    @Test
    fun `pins round-trip, including names with tabs and Georgian`() {
        val pins = listOf(
            Pin("pin-1", 41.75002, 44.77124, "ლისის ტბა", 0x12345678L, t0, mine = false),
            Pin("pin-mine-2", 41.7, 44.8, "a\tb\\c\nd", null, t0, mine = true, delivery = Pin.Delivery.RELAYED),
            Pin("pin-3", 1.0, 2.0, null, 5L, t0, mine = false),
        )
        assertEquals(pins, StoreCodec.decodePins(StoreCodec.encodePins(pins)))
    }

    @Test
    fun `damaged lines are skipped, the rest is kept`() {
        val text = StoreCodec.encodeTrails(mapOf(1L to listOf(TrailPoint(1.0, 2.0, null, t0)))) +
            "garbage line\n1\tnot-a-time\t1\t2\t\n"
        assertEquals(1, StoreCodec.decodeTrails(text)[1L]!!.size)
        assertEquals(emptyList<Pin>(), StoreCodec.decodePins("# MeshAnd pins v1\nx\ty\n"))
    }
}
