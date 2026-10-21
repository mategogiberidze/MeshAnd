package com.meshand.app.domain

import com.meshand.app.domain.model.ChatChannel
import com.meshand.app.domain.model.ChatMessage
import com.meshand.app.domain.model.ChatThread
import com.meshand.app.domain.model.MeshNode

/** Chat rules: which conversation a message belongs to, the size limit, the conversation list. Pure, unit-tested. */
object ChatRules {
    /**
     * Largest text the radio takes, in UTF-8 bytes (the SDK's `DATA_PAYLOAD_LEN`). Georgian letters
     * take 3 bytes each, so a Georgian message fits about 77 letters.
     */
    const val MAX_TEXT_BYTES = 233

    /** Show the byte counter once a message gets this long. */
    const val SHOW_COUNTER_FROM = 160

    /** Node number meaning "everyone on the channel". */
    const val BROADCAST = 0xFFFF_FFFFL

    /** Messages kept per conversation; older ones are dropped. */
    const val KEEP_PER_THREAD = 500

    data class Conversation(
        val thread: ChatThread,
        val title: String,
        /** The direct-message partner, if the radio knows it. */
        val node: MeshNode?,
        val lastMessage: ChatMessage?,
        val unread: Int,
    )

    fun bytesUsed(text: String): Int = PinText.utf8Bytes(text)

    /** True when [text] has something to say and fits in one packet. */
    fun canSend(text: String): Boolean = text.isNotBlank() && bytesUsed(text.trim()) <= MAX_TEXT_BYTES

    /** Identifies a message by its sender and packet id, so repeats of the same packet are dropped. */
    fun messageId(fromNodeId: Long, packetId: Int): String = "%08x-%08x".format(fromNodeId, packetId)

    /**
     * The conversation a text packet belongs to: a channel for broadcasts, otherwise the other side
     * of a direct message. Null when it isn't addressed to us (or we don't know our node yet).
     */
    fun threadFor(from: Long, to: Long, channel: Int, ownNodeId: Long?): ChatThread? = when {
        to == BROADCAST -> if (channel in 0..7) ChatThread.Channel(channel) else null
        ownNodeId == null -> null
        to == ownNodeId && from != ownNodeId -> ChatThread.Direct(from)
        from == ownNodeId && to != ownNodeId -> ChatThread.Direct(to)
        else -> null
    }

    /**
     * Channel to send a direct message on: the one [nodeId] last wrote to us on, else the primary.
     * (Radios with each other's keys encrypt direct messages end-to-end whatever the channel.)
     */
    fun replyChannel(messages: List<ChatMessage>, nodeId: Long): Int =
        messages.lastOrNull { it.thread == ChatThread.Direct(nodeId) && !it.mine }?.channelIndex ?: 0

    /** Channel's display name: its own name, else the modem preset ("LongFast") for the primary. */
    fun channelName(index: Int, name: String, presetName: String?): String =
        name.trim().ifEmpty { if (index == 0) presetName ?: "Primary" else "Channel $index" }

    /** `LONG_FAST` → `LongFast`, as the Meshtastic apps name the default primary channel. */
    fun presetName(enumName: String): String =
        enumName.split('_').filter { it.isNotEmpty() }.joinToString("") { part ->
            part.lowercase().replaceFirstChar { it.uppercase() }
        }

    fun title(thread: ChatThread, channels: List<ChatChannel>, nodes: List<MeshNode>): String = when (thread) {
        is ChatThread.Channel -> channels.firstOrNull { it.index == thread.index }?.name
            ?: channelName(thread.index, "", null)
        is ChatThread.Direct -> nodes.firstOrNull { it.id == thread.nodeId }?.let { it.longName ?: it.shortName }
            ?: "!%08x".format(thread.nodeId)
    }

    /**
     * The conversation list: every channel on the radio (the primary even before the radio says so,
     * plus channels that still have saved messages), then direct conversations, latest first.
     * [openDirect] adds an empty direct conversation the user just started.
     */
    fun conversations(
        messages: List<ChatMessage>,
        channels: List<ChatChannel>,
        nodes: List<MeshNode>,
        openDirect: Long? = null,
    ): List<Conversation> {
        val byThread = messages.groupBy { it.thread }
        fun conversation(thread: ChatThread): Conversation {
            val list = byThread[thread].orEmpty()
            return Conversation(
                thread = thread,
                title = title(thread, channels, nodes),
                node = (thread as? ChatThread.Direct)?.let { dm -> nodes.firstOrNull { it.id == dm.nodeId } },
                lastMessage = list.maxByOrNull { it.time },
                unread = list.count { it.unread },
            )
        }
        val channelIndexes = (channels.map { it.index } + 0 + byThread.keys.filterIsInstance<ChatThread.Channel>().map { it.index })
            .distinct().sorted()
        val direct = byThread.keys.filterIsInstance<ChatThread.Direct>() +
            listOfNotNull(openDirect?.let { ChatThread.Direct(it) }).filter { it !in byThread }
        return channelIndexes.map { conversation(ChatThread.Channel(it)) } +
            direct.map(::conversation).sortedByDescending { it.lastMessage?.time }
    }

    /** Drops the oldest messages of any conversation longer than [keep]. Keeps the order. */
    fun trim(messages: List<ChatMessage>, keep: Int = KEEP_PER_THREAD): List<ChatMessage> {
        val counts = messages.groupingBy { it.thread }.eachCount()
        if (counts.values.none { it > keep }) return messages
        val toDrop = counts.mapValues { (_, n) -> (n - keep).coerceAtLeast(0) }.toMutableMap()
        return messages.filter { m ->
            val left = toDrop.getValue(m.thread)
            if (left > 0) {
                toDrop[m.thread] = left - 1
                false
            } else {
                true
            }
        }
    }
}
