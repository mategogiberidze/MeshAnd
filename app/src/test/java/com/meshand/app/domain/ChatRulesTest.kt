package com.meshand.app.domain

import com.meshand.app.domain.model.ChatChannel
import com.meshand.app.domain.model.ChatMessage
import com.meshand.app.domain.model.ChatThread
import com.meshand.app.domain.model.MeshNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChatRulesTest {
    private val t0 = Instant.parse("2026-10-21T10:00:00Z")
    private val own = 0xA1B2C3D4L
    private val ana = 0x11111111L
    private val beka = 0x22222222L

    private fun node(id: Long, name: String?, isOwn: Boolean = false) =
        MeshNode(id, null, name, null, null, null, null, null, null, null, isOwnNode = isOwn)

    private fun msg(id: String, thread: ChatThread, from: Long?, seconds: Long, mine: Boolean = false, unread: Boolean = false, channel: Int = 0) =
        ChatMessage(id, thread, from, "text $id", t0.plusSeconds(seconds), mine, unread = unread, channelIndex = channel)

    @Test
    fun `byte limit counts UTF-8 bytes, so Georgian letters count three`() {
        assertTrue(ChatRules.canSend("a".repeat(233)))
        assertFalse(ChatRules.canSend("a".repeat(234)))
        val georgian = "ა".repeat(77) // 231 bytes
        assertEquals(231, ChatRules.bytesUsed(georgian))
        assertTrue(ChatRules.canSend(georgian))
        assertFalse(ChatRules.canSend(georgian + "ბ"))
        // Surrounding spaces are trimmed before sending.
        assertTrue(ChatRules.canSend("  " + "a".repeat(233) + "\n"))
        assertFalse(ChatRules.canSend("   "))
        assertFalse(ChatRules.canSend(""))
    }

    @Test
    fun `broadcasts go to their channel, direct messages to the other side`() {
        assertEquals(ChatThread.Channel(0), ChatRules.threadFor(ana, ChatRules.BROADCAST, 0, own))
        assertEquals(ChatThread.Channel(3), ChatRules.threadFor(ana, ChatRules.BROADCAST, 3, null))
        assertNull(ChatRules.threadFor(ana, ChatRules.BROADCAST, 8, own))
        assertEquals(ChatThread.Direct(ana), ChatRules.threadFor(ana, own, 0, own))
        assertEquals(ChatThread.Direct(beka), ChatRules.threadFor(own, beka, 1, own))
        // Not for us, or we don't know who we are yet.
        assertNull(ChatRules.threadFor(ana, beka, 0, own))
        assertNull(ChatRules.threadFor(ana, own, 0, null))
        assertNull(ChatRules.threadFor(own, own, 0, own))
    }

    @Test
    fun `thread keys round-trip and reject junk`() {
        for (t in listOf(ChatThread.Channel(0), ChatThread.Channel(7), ChatThread.Direct(0xFFFFFFFEL), ChatThread.Direct(1))) {
            assertEquals(t, ChatThread.fromKey(t.key))
        }
        for (bad in listOf("", "ch:8", "ch:-1", "dm:x", "dm:4294967296", "xx:1", "ch")) assertNull(bad, ChatThread.fromKey(bad))
    }

    @Test
    fun `channel names fall back to the preset, then the slot`() {
        assertEquals("Team", ChatRules.channelName(1, " Team ", "LongFast"))
        assertEquals("LongFast", ChatRules.channelName(0, "", "LongFast"))
        assertEquals("Primary", ChatRules.channelName(0, "", null))
        assertEquals("Channel 2", ChatRules.channelName(2, "", "LongFast"))
        assertEquals("LongFast", ChatRules.presetName("LONG_FAST"))
        assertEquals("VeryLongSlow", ChatRules.presetName("VERY_LONG_SLOW"))
    }

    @Test
    fun `conversation list has every channel, then direct chats latest first, with unread counts`() {
        val messages = listOf(
            msg("1", ChatThread.Channel(0), ana, 0, unread = true),
            msg("2", ChatThread.Direct(ana), ana, 10, unread = true),
            msg("3", ChatThread.Direct(beka), own, 30, mine = true),
            msg("4", ChatThread.Direct(ana), ana, 20, unread = true),
            msg("5", ChatThread.Channel(5), beka, 40),
        )
        val channels = listOf(ChatChannel(0, "LongFast"), ChatChannel(2, "Team"))
        val nodes = listOf(node(own, "Me", isOwn = true), node(ana, "Ana"))
        val list = ChatRules.conversations(messages, channels, nodes)
        assertEquals(
            listOf(ChatThread.Channel(0), ChatThread.Channel(2), ChatThread.Channel(5), ChatThread.Direct(beka), ChatThread.Direct(ana)),
            list.map { it.thread },
        )
        assertEquals(listOf("LongFast", "Team", "Channel 5", "!22222222", "Ana"), list.map { it.title })
        assertEquals(listOf(1, 0, 0, 0, 2), list.map { it.unread })
        assertEquals("4", list.last().lastMessage?.id)
        assertEquals("Ana", list.last().node?.longName)
    }

    @Test
    fun `primary channel is listed before the radio reports channels, and a new direct chat shows up`() {
        val list = ChatRules.conversations(emptyList(), emptyList(), emptyList(), openDirect = ana)
        assertEquals(listOf(ChatThread.Channel(0), ChatThread.Direct(ana)), list.map { it.thread })
        assertEquals("Primary", list.first().title)
        assertNull(list.last().lastMessage)
    }

    @Test
    fun `direct replies go on the channel the partner last used`() {
        val messages = listOf(
            msg("1", ChatThread.Direct(ana), ana, 0, channel = 2),
            msg("2", ChatThread.Direct(ana), own, 5, mine = true, channel = 0),
            msg("3", ChatThread.Channel(1), ana, 6, channel = 1),
        )
        assertEquals(2, ChatRules.replyChannel(messages, ana))
        assertEquals(0, ChatRules.replyChannel(messages, beka))
    }

    @Test
    fun `trim drops the oldest messages of long conversations only`() {
        val a = (1..5).map { msg("a$it", ChatThread.Channel(0), ana, it.toLong()) }
        val b = (1..2).map { msg("b$it", ChatThread.Direct(beka), beka, it.toLong()) }
        val mixed = listOf(a[0], b[0], a[1], a[2], b[1], a[3], a[4])
        assertEquals(listOf("b1", "a3", "b2", "a4", "a5"), ChatRules.trim(mixed, keep = 3).map { it.id })
        assertEquals(mixed, ChatRules.trim(mixed, keep = 5))
    }

    @Test
    fun `message ids combine sender and packet id`() {
        assertEquals("a1b2c3d4-ffffffff", ChatRules.messageId(own, -1))
        assertEquals("00000001-0000002a", ChatRules.messageId(1, 42))
    }
}
