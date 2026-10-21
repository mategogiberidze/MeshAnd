package com.meshand.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.meshand.app.R
import com.meshand.app.domain.ChatRules
import com.meshand.app.domain.NodeColors
import com.meshand.app.domain.model.ChatMessage
import com.meshand.app.domain.model.ChatThread
import com.meshand.app.domain.model.MeshNode
import com.meshand.app.ui.common.HintText
import com.meshand.app.ui.common.SectionCard
import com.meshand.app.ui.common.formatDateTime
import kotlinx.coroutines.delay
import java.time.Instant

/* The Chat tab: the conversation list, and one conversation with its compose box. */

@Composable
private fun rememberMinute(): Instant {
    val now by produceState(Instant.now()) {
        while (true) {
            delay(30_000)
            value = Instant.now()
        }
    }
    return now
}

/** Every channel on the radio, then direct conversations, latest first. */
@Composable
fun ChatListPage(conversations: List<ChatRules.Conversation>, onOpen: (ChatThread) -> Unit) {
    val now = rememberMinute()
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            HintText(
                "Channel messages reach everyone on that channel. To write to one person, open Nodes " +
                    "(or the Team list), then Message.",
            )
        }
        items(conversations, key = { it.thread.key }) { c -> ConversationRow(c, now) { onOpen(c.thread) } }
    }
}

@Composable
private fun ConversationRow(c: ChatRules.Conversation, now: Instant, onClick: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThreadIcon(c.thread, c.node)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        c.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    c.lastMessage?.let { HintText(formatDateTime(it.time, now)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val preview = c.lastMessage?.let { if (it.mine) "You: ${it.text}" else it.text }
                        ?: when (val t = c.thread) {
                            is ChatThread.Channel -> if (t.index == 0) "Primary channel · no messages yet" else "Channel ${t.index} · no messages yet"
                            is ChatThread.Direct -> "No messages yet"
                        }
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (c.unread > 0) Badge { Text(c.unread.toString()) }
                }
            }
        }
    }
}

/** A chat glyph for channels, the person's map colour for direct messages. */
@Composable
private fun ThreadIcon(thread: ChatThread, node: MeshNode?) {
    when (thread) {
        is ChatThread.Channel -> Box(
            Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_chat),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        is ChatThread.Direct -> Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            val color = node?.let(NodeColors::colorFor) ?: NodeColors.colorForId(thread.nodeId)
            Box(Modifier.size(20.dp).background(Color(color), CircleShape))
        }
    }
}

/**
 * One conversation: messages oldest at the top, and a compose box. [onVisible] is called with the
 * thread while the screen is in front (so its messages count as read), and with null after.
 */
@Composable
fun ConversationPage(
    thread: ChatThread,
    messages: List<ChatMessage>,
    nodes: List<MeshNode>,
    connected: Boolean,
    onVisible: (ChatThread?) -> Unit,
    onSend: (String) -> String?,
) {
    val now = rememberMinute()
    LifecycleResumeEffect(thread) {
        onVisible(thread)
        onPauseOrDispose { onVisible(null) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                HintText(
                    when (thread) {
                        is ChatThread.Channel -> "Everyone on this channel can read these messages."
                        is ChatThread.Direct -> "Only this person can read these messages (their radio needs to be in range of the mesh)."
                    },
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            items(messages, key = { it.id }) { m ->
                val sender = nodes.firstOrNull { it.id == m.fromNodeId }
                MessageBubble(m, sender, showSender = thread is ChatThread.Channel, now = now)
            }
        }
        ComposeBox(connected, onSend)
    }
}

@Composable
private fun MessageBubble(m: ChatMessage, sender: MeshNode?, showSender: Boolean, now: Instant) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start,
    ) {
        if (showSender && !m.mine) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            ) {
                val color = sender?.let(NodeColors::colorFor) ?: NodeColors.colorForId(m.fromNodeId ?: 0)
                Box(Modifier.size(10.dp).background(Color(color), CircleShape))
                Text(
                    sender?.let { it.longName ?: it.shortName } ?: m.fromNodeId?.let { "!%08x".format(it) } ?: "Unknown",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Surface(
            color = if (m.mine) colors.primaryContainer else colors.surfaceContainerHigh,
            contentColor = if (m.mine) colors.onPrimaryContainer else colors.onSurface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(m.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        val (state, stateColor) = deliveryText(m)
        Text(
            formatDateTime(m.time, now) + state,
            style = MaterialTheme.typography.labelMedium,
            color = stateColor ?: colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun deliveryText(m: ChatMessage): Pair<String, Color?> {
    val direct = m.thread is ChatThread.Direct
    return when (m.delivery) {
        ChatMessage.Delivery.SENDING -> " · sending…" to null
        ChatMessage.Delivery.SENT -> " · sent" to null
        ChatMessage.Delivery.ACKED -> (if (direct) " · delivered ✓" else " · relayed ✓") to null
        ChatMessage.Delivery.FAILED -> (if (direct) " · not delivered" else " · not confirmed") to MaterialTheme.colorScheme.error
        null -> "" to null
    }
}

/** Text field and Send button, with a byte counter near the 233-byte limit. */
@Composable
private fun ComposeBox(connected: Boolean, onSend: (String) -> String?) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val used = ChatRules.bytesUsed(text.trim())
    val tooLong = used > ChatRules.MAX_TEXT_BYTES
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth().imePadding()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (!connected) {
                HintText("Not connected to your radio: you can read, but not send.", modifier = Modifier.padding(bottom = 4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    placeholder = { Text("Message") },
                    maxLines = 5,
                    isError = tooLong,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    supportingText = if (used >= ChatRules.SHOW_COUNTER_FROM) {
                        { Text(if (tooLong) "Too long: $used of ${ChatRules.MAX_TEXT_BYTES} bytes" else "$used of ${ChatRules.MAX_TEXT_BYTES} bytes") }
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                )
                FilledIconButton(
                    onClick = {
                        val result = onSend(text)
                        if (result == null) text = "" else error = result
                    },
                    enabled = connected && ChatRules.canSend(text),
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_send), contentDescription = "Send")
                }
            }
            error?.let { HintText(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
