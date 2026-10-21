package com.meshand.app.data.storage

import com.meshand.app.domain.model.ChatChannel
import com.meshand.app.domain.model.ChatMessage
import com.meshand.app.domain.model.ChatThread
import com.meshand.app.domain.model.Pin
import com.meshand.app.domain.model.TrailPoint
import java.time.Instant

/**
 * Plain-text (tab-separated) file formats for trails, pins and chat. Pure, unit-tested.
 * Malformed lines are skipped, so a damaged file loses at most those lines.
 */
object StoreCodec {
    private const val TRAILS_HEADER = "# MeshAnd trails v1"
    private const val PINS_HEADER = "# MeshAnd pins v1"
    private const val CHAT_HEADER = "# MeshAnd chat v1"

    /** Saved chat: the messages (oldest first) and the radio's channel names. */
    data class Chat(val messages: List<ChatMessage>, val channels: List<ChatChannel>)

    fun encodeTrails(trails: Map<Long, List<TrailPoint>>): String = buildString {
        appendLine(TRAILS_HEADER)
        for ((nodeId, points) in trails) {
            for (p in points) {
                append(nodeId).append('\t').append(p.time.toEpochMilli()).append('\t')
                append(p.latitude).append('\t').append(p.longitude).append('\t')
                appendLine(p.altitude?.toString().orEmpty())
            }
        }
    }

    fun decodeTrails(text: String): Map<Long, List<TrailPoint>> {
        val trails = LinkedHashMap<Long, MutableList<TrailPoint>>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split('\t')
            if (f.size < 5) continue
            val nodeId = f[0].toLongOrNull() ?: continue
            val time = f[1].toLongOrNull()?.let(Instant::ofEpochMilli) ?: continue
            val lat = f[2].toDoubleOrNull() ?: continue
            val lon = f[3].toDoubleOrNull() ?: continue
            trails.getOrPut(nodeId) { ArrayList() }.add(TrailPoint(lat, lon, f[4].toIntOrNull(), time))
        }
        return trails.mapValues { (_, points) -> points.sortedBy { it.time } }
    }

    fun encodePins(pins: List<Pin>): String = buildString {
        appendLine(PINS_HEADER)
        for (pin in pins) {
            append(escape(pin.id)).append('\t')
            append(pin.latitude).append('\t').append(pin.longitude).append('\t')
            append(pin.fromNodeId?.toString().orEmpty()).append('\t')
            append(pin.time.toEpochMilli()).append('\t')
            append(if (pin.mine) "1" else "0").append('\t')
            append(pin.delivery?.name.orEmpty()).append('\t')
            appendLine(pin.name?.let(::escape).orEmpty())
        }
    }

    fun decodePins(text: String): List<Pin> = text.lineSequence().mapNotNull { line ->
        if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
        val f = line.split('\t')
        if (f.size < 8) return@mapNotNull null
        Pin(
            id = unescape(f[0]).ifEmpty { return@mapNotNull null },
            latitude = f[1].toDoubleOrNull() ?: return@mapNotNull null,
            longitude = f[2].toDoubleOrNull() ?: return@mapNotNull null,
            fromNodeId = f[3].toLongOrNull(),
            time = f[4].toLongOrNull()?.let(Instant::ofEpochMilli) ?: return@mapNotNull null,
            mine = f[5] == "1",
            delivery = Pin.Delivery.entries.firstOrNull { it.name == f[6] },
            name = unescape(f[7]).ifEmpty { null },
        )
    }.toList()

    fun encodeChat(chat: Chat): String = buildString {
        appendLine(CHAT_HEADER)
        for (c in chat.channels) {
            append("C\t").append(c.index).append('\t').appendLine(escape(c.name))
        }
        for (m in chat.messages) {
            append("M\t").append(escape(m.id)).append('\t').append(m.thread.key).append('\t')
            append(m.fromNodeId?.toString().orEmpty()).append('\t')
            append(m.time.toEpochMilli()).append('\t')
            append(if (m.mine) "1" else "0").append('\t')
            append(m.delivery?.name.orEmpty()).append('\t')
            append(if (m.unread) "1" else "0").append('\t')
            append(m.channelIndex).append('\t')
            appendLine(escape(m.text))
        }
    }

    fun decodeChat(text: String): Chat {
        val messages = ArrayList<ChatMessage>()
        val channels = ArrayList<ChatChannel>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split('\t')
            when (f[0]) {
                "C" -> if (f.size >= 3) {
                    val index = f[1].toIntOrNull()?.takeIf { it in 0..7 } ?: continue
                    channels += ChatChannel(index, unescape(f[2]))
                }
                "M" -> if (f.size >= 10) {
                    messages += ChatMessage(
                        id = unescape(f[1]).ifEmpty { continue },
                        thread = ChatThread.fromKey(f[2]) ?: continue,
                        fromNodeId = f[3].toLongOrNull(),
                        time = f[4].toLongOrNull()?.let(Instant::ofEpochMilli) ?: continue,
                        mine = f[5] == "1",
                        delivery = ChatMessage.Delivery.entries.firstOrNull { it.name == f[6] },
                        unread = f[7] == "1",
                        channelIndex = f[8].toIntOrNull() ?: 0,
                        text = unescape(f[9]),
                    )
                }
            }
        }
        return Chat(messages, channels)
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                out.append(
                    when (s[i + 1]) {
                        't' -> '\t'
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> s[i + 1]
                    },
                )
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
