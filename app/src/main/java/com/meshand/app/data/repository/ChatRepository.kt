package com.meshand.app.data.repository

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.meshand.app.MainActivity
import com.meshand.app.R
import com.meshand.app.data.meshtastic.MeshtasticClient
import com.meshand.app.data.meshtastic.MeshtasticMapper
import com.meshand.app.data.storage.LocalStore
import com.meshand.app.data.storage.StoreCodec
import com.meshand.app.domain.ChatRules
import com.meshand.app.domain.NodeColors
import com.meshand.app.domain.PinText
import com.meshand.app.domain.model.ChatChannel
import com.meshand.app.domain.model.ChatMessage
import com.meshand.app.domain.model.ChatThread
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.MeshNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.proto.Channel
import org.meshtastic.proto.MeshPacket
import org.meshtastic.sdk.ChannelIndex
import org.meshtastic.sdk.ConfigBundle
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.SendState
import org.meshtastic.sdk.asText
import java.time.Instant

private const val TAG = "MeshAnd/Chat"

/**
 * Text messages: channel messages and direct messages, received from the mesh or sent from this
 * phone (only when the user taps Send). `meshand:` pin messages are left to [PinRepository].
 * Saved to [store] so they survive restarts. Message texts are never logged.
 */
class ChatRepository(
    context: Context,
    private val client: MeshtasticClient,
    private val nodes: StateFlow<List<MeshNode>>,
    private val scope: CoroutineScope,
    private val store: LocalStore,
) {
    private val appContext = context.applicationContext
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _channels = MutableStateFlow<List<ChatChannel>>(emptyList())

    /** The conversation on screen; its messages are read as they arrive. */
    private var openThread: ChatThread? = null

    /** Oldest first. */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** Channels on the radio (last known while disconnected), by index. */
    val channels: StateFlow<List<ChatChannel>> = _channels.asStateFlow()

    val unreadCount: StateFlow<Int> =
        _messages.map { list -> list.count { it.unread } }.stateIn(scope, SharingStarted.Eagerly, 0)

    init {
        NotificationManagerCompat.from(appContext).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Chat messages")
                .setDescription("New text messages from the mesh")
                .build(),
        )
        scope.launch {
            val saved = withContext(Dispatchers.IO) { store.read(LocalStore.CHAT_FILE)?.let(StoreCodec::decodeChat) }
            if (saved != null) {
                // A send that was still in progress when the app stopped can't be tracked any more.
                val restored = saved.messages.map {
                    if (it.delivery == ChatMessage.Delivery.SENDING) it.copy(delivery = ChatMessage.Delivery.SENT) else it
                }
                // Keep anything that arrived while loading.
                _messages.update { current -> restored.filter { r -> current.none { it.id == r.id } } + current }
                _channels.update { current -> current.ifEmpty { saved.channels } }
            }
            // Save changes from now on, at most every half second (a burst of messages is one write).
            combine(_messages, _channels, StoreCodec::Chat).drop(1).collectLatest { chat ->
                delay(500)
                withContext(Dispatchers.IO) { store.write(LocalStore.CHAT_FILE, StoreCodec.encodeChat(chat)) }
            }
        }
        scope.launch {
            client.session.collectLatest { session -> session?.packets?.collect(::onPacket) }
        }
        scope.launch {
            client.session.collectLatest { session ->
                session ?: return@collectLatest
                combine(session.channels, session.configBundle, ::toChatChannels).collect { list ->
                    if (!list.isNullOrEmpty()) _channels.value = list
                }
            }
        }
    }

    /** Only names and slots are read; keys and settings stay in the SDK. */
    private fun toChatChannels(channels: List<Channel>?, bundle: ConfigBundle?): List<ChatChannel>? {
        val lora = bundle?.configs?.firstNotNullOfOrNull { it.lora }
        val preset = lora?.takeIf { it.use_preset }?.let { ChatRules.presetName(it.modem_preset.name) }
        return channels
            ?.filter { it.role != Channel.Role.DISABLED && it.index in 0..ChannelIndex.MAX_CHANNEL_INDEX }
            ?.map { ChatChannel(it.index, ChatRules.channelName(it.index, it.settings?.name.orEmpty(), preset)) }
            ?.sortedBy { it.index }
    }

    private fun ownNodeId(): Long? =
        nodes.value.firstOrNull { it.isOwnNode }?.id
            ?: client.session.value?.ownNode?.value?.num?.let(MeshtasticMapper::nodeNumToLong)

    private fun onPacket(packet: MeshPacket) {
        val text = packet.asText() ?: return
        if (PinText.parseMessage(text) != null) return
        val from = MeshtasticMapper.nodeNumToLong(packet.from)
        val own = ownNodeId()
        val thread = ChatRules.threadFor(from, MeshtasticMapper.nodeNumToLong(packet.to), packet.channel, own) ?: return
        val mine = from == own
        val message = ChatMessage(
            id = ChatRules.messageId(from, packet.id),
            thread = thread,
            fromNodeId = from,
            text = text,
            time = Instant.now(),
            mine = mine,
            unread = !mine && thread != openThread,
            channelIndex = packet.channel,
        )
        if (!add(message)) return
        Log.i(TAG, "Message received from !%08x in ${thread.key}".format(from))
        if (message.unread) notifyReceived(thread)
    }

    /**
     * Sends [text] to a channel or a node. Returns null when it was handed to the radio, or a
     * message saying why it couldn't be sent.
     */
    fun send(thread: ChatThread, text: String): String? {
        val clean = text.trim()
        if (clean.isEmpty()) return "Type a message first."
        if (!ChatRules.canSend(clean)) {
            return "Too long: ${ChatRules.bytesUsed(clean)} of ${ChatRules.MAX_TEXT_BYTES} bytes."
        }
        val session = client.session.value
        if (session == null || client.status.value !is ConnectionStatus.Connected) {
            return "MeshAnd isn't connected to your radio."
        }
        val (to, channel) = when (thread) {
            is ChatThread.Channel -> NodeId.BROADCAST to thread.index
            // Node numbers are uint32; toInt() keeps the same 32 bits.
            is ChatThread.Direct -> NodeId(thread.nodeId.toInt()) to ChatRules.replyChannel(_messages.value, thread.nodeId)
        }
        val handle = try {
            session.sendText(clean, to = to, channel = ChannelIndex(channel))
        } catch (e: MeshtasticException) {
            Log.w(TAG, "Sending message failed", e)
            return "The radio didn't accept the message: ${e.message}"
        }
        val own = ownNodeId()
        val message = ChatMessage(
            id = own?.let { ChatRules.messageId(it, handle.id.raw) } ?: "mine-${Instant.now().toEpochMilli()}",
            thread = thread,
            fromNodeId = own,
            text = clean,
            time = Instant.now(),
            mine = true,
            delivery = ChatMessage.Delivery.SENDING,
            channelIndex = channel,
        )
        add(message)
        Log.i(TAG, "Message sent to ${thread.key}")
        scope.launch {
            // A direct message is acked by the recipient; a channel message when a neighbour relays it.
            val final = withTimeoutOrNull(ACK_WAIT) {
                handle.state.first { state ->
                    setDelivery(message.id, state)
                    state is SendState.Acked || state is SendState.Delivered || state is SendState.Failed
                }
            }
            if (final == null) Log.i(TAG, "Message ${message.id}: no ack within $ACK_WAIT ms")
        }
        return null
    }

    /** Tells the repository which conversation is on screen (null: none), and marks it read. */
    fun setOpenThread(thread: ChatThread?) {
        openThread = thread
        if (thread != null) markRead(thread)
    }

    fun markRead(thread: ChatThread) {
        if (_messages.value.any { it.thread == thread && it.unread }) {
            _messages.update { list -> list.map { if (it.thread == thread && it.unread) it.copy(unread = false) else it } }
        }
        NotificationManagerCompat.from(appContext).cancel(notificationId(thread))
    }

    /** Deletes the messages of one conversation from this phone. */
    fun deleteThread(thread: ChatThread) {
        _messages.update { list -> list.filterNot { it.thread == thread } }
        NotificationManagerCompat.from(appContext).cancel(notificationId(thread))
    }

    /** Deletes every message; the channel names stay. */
    fun clear() {
        _messages.value = emptyList()
    }

    /** Adds [message] unless the same packet is already there. Returns false for a repeat. */
    private fun add(message: ChatMessage): Boolean {
        var isNew = true
        _messages.update { list ->
            if (list.any { it.id == message.id }) {
                isNew = false
                list
            } else {
                ChatRules.trim(list + message)
            }
        }
        return isNew
    }

    private fun setDelivery(id: String, state: SendState) {
        val delivery = when (state) {
            SendState.Queued -> ChatMessage.Delivery.SENDING
            SendState.Sent -> ChatMessage.Delivery.SENT
            SendState.Acked, SendState.Delivered -> ChatMessage.Delivery.ACKED
            is SendState.Failed -> ChatMessage.Delivery.FAILED
        }
        _messages.update { list -> list.map { if (it.id == id) it.copy(delivery = delivery) else it } }
    }

    private fun senderName(nodeId: Long?): String {
        val node = nodes.value.firstOrNull { it.id == nodeId }
        return node?.let { it.longName ?: it.shortName } ?: nodeId?.let { "!%08x".format(it) } ?: "Someone"
    }

    /** One notification per conversation, listing its unread messages. */
    private fun notifyReceived(thread: ChatThread) {
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) return
        val unread = _messages.value.filter { it.thread == thread && it.unread }
        val last = unread.lastOrNull() ?: return
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
        if (thread is ChatThread.Channel) {
            style.setConversationTitle(ChatRules.title(thread, _channels.value, nodes.value)).setGroupConversation(true)
        }
        for (m in unread.takeLast(MAX_NOTIFIED)) {
            style.addMessage(m.text, m.time.toEpochMilli(), Person.Builder().setName(senderName(m.fromNodeId)).build())
        }
        val sender = nodes.value.firstOrNull { it.id == last.fromNodeId }
        val open = Intent(appContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_CHAT, thread.key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setColor(sender?.let(NodeColors::colorFor) ?: NodeColors.colorForId(last.fromNodeId ?: 0))
            .setStyle(style)
            .setNumber(unread.size)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    notificationId(thread),
                    open,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        try {
            manager.notify(notificationId(thread), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission missing", e)
        }
    }

    private fun notificationId(thread: ChatThread) = ("chat-" + thread.key).hashCode()

    companion object {
        private const val CHANNEL_ID = "chat_messages"
        private const val ACK_WAIT = 120_000L
        private const val MAX_NOTIFIED = 6
    }
}
