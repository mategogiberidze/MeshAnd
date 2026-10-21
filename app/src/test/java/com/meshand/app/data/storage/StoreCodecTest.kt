package com.meshand.app.data.storage

import com.meshand.app.domain.model.ChatChannel
import com.meshand.app.domain.model.ChatMessage
import com.meshand.app.domain.model.ChatThread
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

    @Test
    fun `chat round-trips, including multi-line Georgian text and channel names`() {
        val chat = StoreCodec.Chat(
            messages = listOf(
                ChatMessage("0000002a-00000001", ChatThread.Channel(0), 42L, "გამარჯობა\nhi\tthere \\ ok", t0, mine = false, unread = true),
                ChatMessage(
                    "deadbeef-00000002", ChatThread.Direct(0xFFFFFFF0L), 0xDEADBEEFL, "on my way", t0.plusSeconds(5),
                    mine = true, delivery = ChatMessage.Delivery.ACKED, channelIndex = 2,
                ),
                ChatMessage("mine-3", ChatThread.Channel(7), null, "", t0, mine = true, delivery = ChatMessage.Delivery.FAILED),
            ),
            channels = listOf(ChatChannel(0, "LongFast"), ChatChannel(1, "Team\tA")),
        )
        assertEquals(chat, StoreCodec.decodeChat(StoreCodec.encodeChat(chat)))
    }

    @Test
    fun `damaged chat lines are skipped`() {
        val good = StoreCodec.encodeChat(
            StoreCodec.Chat(listOf(ChatMessage("a", ChatThread.Channel(1), 1L, "ok", t0, mine = false)), emptyList()),
        )
        val text = good + "M\tb\tch:9\t1\t0\t0\t\t0\t0\tbad thread\n" + "M\tc\tdm:1\t1\tnot-a-time\t0\t\t0\t0\tx\n" +
            "C\t12\tbad slot\n" + "M\ttoo\tshort\n"
        val decoded = StoreCodec.decodeChat(text)
        assertEquals(listOf("a"), decoded.messages.map { it.id })
        assertEquals(emptyList<ChatChannel>(), decoded.channels)
    }
}
