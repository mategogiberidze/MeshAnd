package com.meshand.app.domain.model

import java.time.Instant

/** Where a chat message belongs: a channel (everyone on it) or a direct conversation with one node. */
sealed interface ChatThread {
    /** Stable text form, used in saved files and notification intents. */
    val key: String

    /** A channel slot on the radio, 0 (primary) to 7. */
    data class Channel(val index: Int) : ChatThread {
        override val key: String get() = "ch:$index"
    }

    /** Direct messages with [nodeId] (unsigned node number). */
    data class Direct(val nodeId: Long) : ChatThread {
        override val key: String get() = "dm:$nodeId"
    }

    companion object {
        fun fromKey(key: String): ChatThread? {
            val value = key.substringAfter(':', "")
            return when (key.substringBefore(':')) {
                "ch" -> value.toIntOrNull()?.takeIf { it in 0..7 }?.let(::Channel)
                "dm" -> value.toLongOrNull()?.takeIf { it in 0..0xFFFF_FFFFL }?.let(::Direct)
                else -> null
            }
        }
    }
}

/** One text message, sent from this phone or received over the mesh. */
data class ChatMessage(
    /** Unique per message: sender plus the packet id. */
    val id: String,
    val thread: ChatThread,
    /** Sender's node number, if known. */
    val fromNodeId: Long?,
    val text: String,
    val time: Instant,
    /** True for messages this phone sent. */
    val mine: Boolean,
    /** Progress of a message this phone sent; null for received ones. */
    val delivery: Delivery? = null,
    /** A received message the user hasn't looked at yet. */
    val unread: Boolean = false,
    /** Channel the message travelled on (for a direct message, the channel to answer on). */
    val channelIndex: Int = 0,
) {
    /** ACKED: the recipient confirmed a direct message, or a neighbour relayed a channel message. */
    enum class Delivery { SENDING, SENT, ACKED, FAILED }
}

/** A channel configured on the radio. */
data class ChatChannel(val index: Int, val name: String)
