package com.meshand.app.data.repository

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.meshand.app.MainActivity
import com.meshand.app.R
import com.meshand.app.data.meshtastic.MeshtasticClient
import com.meshand.app.data.meshtastic.MeshtasticMapper
import com.meshand.app.data.storage.LocalStore
import com.meshand.app.data.storage.StoreCodec
import com.meshand.app.domain.NodeColors
import com.meshand.app.domain.PinText
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.MeshNode
import com.meshand.app.domain.model.Pin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.proto.MeshPacket
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.SendState
import org.meshtastic.sdk.asText
import java.time.Duration
import java.time.Instant
import java.util.Locale

private const val TAG = "MeshAnd/Pins"

/**
 * Pins shared over the mesh as `meshand: lat,lon` text messages ([PinText]): received from teammates,
 * or sent from this phone (from OsmAnd's Share menu, see `SharePinActivity`). Sending a pin is
 * the only thing MeshAnd ever transmits, and only when the user taps Send. Saved to [store] so
 * they survive restarts; pins older than [KEEP_FOR] are dropped.
 */
class PinRepository(
    context: Context,
    private val client: MeshtasticClient,
    private val nodes: StateFlow<List<MeshNode>>,
    private val scope: CoroutineScope,
    private val store: LocalStore,
    /** Package of the installed OsmAnd, so a pin notification can open it there. */
    private val osmAndPackage: () -> String?,
) {
    private val appContext = context.applicationContext
    private val _pins = MutableStateFlow<List<Pin>>(emptyList())

    /** Newest first. */
    val pins: StateFlow<List<Pin>> = _pins.asStateFlow()

    init {
        NotificationManagerCompat.from(appContext).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Team pins")
                .setDescription("A teammate shared a place over the mesh")
                .build(),
        )
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                store.read(LocalStore.PINS_FILE)?.let(StoreCodec::decodePins).orEmpty()
            }
            val cutoff = Instant.now().minus(KEEP_FOR)
            // A send that was still in progress when the app stopped can't be tracked any more.
            val restored = saved.filter { it.time.isAfter(cutoff) }.map {
                if (it.delivery == Pin.Delivery.SENDING) it.copy(delivery = Pin.Delivery.SENT) else it
            }
            // Keep anything that arrived while loading.
            _pins.update { current -> current + restored.filter { r -> current.none { it.id == r.id } } }
            // Save every change from now on (pins change rarely).
            _pins.drop(1).collect { pins ->
                withContext(Dispatchers.IO) { store.write(LocalStore.PINS_FILE, StoreCodec.encodePins(pins)) }
            }
        }
        scope.launch {
            client.session.collectLatest { session -> session?.packets?.collect(::onPacket) }
        }
        scope.launch {
            while (true) {
                delay(10 * 60_000L)
                val cutoff = Instant.now().minus(KEEP_FOR)
                _pins.update { list -> list.filter { it.time.isAfter(cutoff) } }
            }
        }
    }

    private fun onPacket(packet: MeshPacket) {
        val text = packet.asText() ?: return
        val parsed = PinText.parseMessage(text) ?: return
        val from = MeshtasticMapper.nodeNumToLong(packet.from)
        val own = nodes.value.firstOrNull { it.isOwnNode }?.id
        val pin = Pin(
            id = "pin-%08x-%d".format(from, Instant.now().epochSecond),
            latitude = parsed.latitude,
            longitude = parsed.longitude,
            name = parsed.name,
            fromNodeId = from,
            time = Instant.now(),
            mine = from == own,
        )
        Log.i(TAG, "Pin received from !%08x: %.5f,%.5f".format(from, pin.latitude, pin.longitude))
        val isNew = add(pin)
        if (isNew && !pin.mine) notifyReceived(pin)
    }

    /**
     * Broadcasts a pin on the primary channel. Returns null when it was handed to the radio, or a
     * message saying why it couldn't be sent.
     */
    fun send(latitude: Double, longitude: Double, name: String?): String? {
        val session = client.session.value
        if (session == null || client.status.value !is ConnectionStatus.Connected) {
            return "MeshAnd isn't connected to your radio."
        }
        val text = PinText.format(latitude, longitude, name)
        // Store what the message says (5 decimals), so the radio's echo matches it.
        val parsed = PinText.parseMessage(text) ?: return "Invalid coordinates."
        val handle = try {
            session.sendText(text)
        } catch (e: MeshtasticException) {
            Log.w(TAG, "Sending pin failed", e)
            return "The radio didn't accept the pin: ${e.message}"
        }
        val own = nodes.value.firstOrNull { it.isOwnNode }?.id
        val pin = Pin(
            id = "pin-mine-${Instant.now().toEpochMilli()}",
            latitude = parsed.latitude,
            longitude = parsed.longitude,
            name = parsed.name,
            fromNodeId = own,
            time = Instant.now(),
            mine = true,
            delivery = Pin.Delivery.SENDING,
        )
        add(pin)
        Log.i(TAG, "Pin sent: %.5f,%.5f".format(pin.latitude, pin.longitude))
        scope.launch {
            // Broadcasts are "acked" when a neighbour is heard relaying them.
            val final = withTimeoutOrNull(ACK_WAIT) {
                handle.state.first { state ->
                    setDelivery(pin.id, state)
                    state is SendState.Acked || state is SendState.Delivered || state is SendState.Failed
                }
            }
            if (final == null) Log.i(TAG, "Pin ${pin.id}: no relay heard within $ACK_WAIT ms")
        }
        return null
    }

    fun remove(id: String) = _pins.update { list -> list.filterNot { it.id == id } }

    fun clear() {
        _pins.value = emptyList()
    }

    /** Adds [pin], replacing the same place from the same sender. Returns false for a repeat. */
    private fun add(pin: Pin): Boolean {
        var isNew = true
        _pins.update { list ->
            val same = list.firstOrNull { it.fromNodeId == pin.fromNodeId && it.latitude == pin.latitude && it.longitude == pin.longitude }
            if (same != null) {
                isNew = false
                // Keep our own copy (with its delivery state) when the radio echoes our message.
                if (same.mine && same.delivery != null) return@update list
            }
            listOf(pin) + list.filterNot { it === same }
        }
        return isNew
    }

    private fun setDelivery(id: String, state: SendState) {
        val delivery = when (state) {
            SendState.Queued -> Pin.Delivery.SENDING
            SendState.Sent -> Pin.Delivery.SENT
            SendState.Acked, SendState.Delivered -> Pin.Delivery.RELAYED
            is SendState.Failed -> Pin.Delivery.FAILED
        }
        _pins.update { list -> list.map { if (it.id == id) it.copy(delivery = delivery) else it } }
    }

    private fun notifyReceived(pin: Pin) {
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) return
        val sender = nodes.value.firstOrNull { it.id == pin.fromNodeId }
        val who = sender?.let { it.longName ?: it.shortName } ?: pin.fromNodeId?.let { "!%08x".format(it) } ?: "A teammate"
        // Tapping opens the place in OsmAnd (it handles geo: links), or MeshAnd without OsmAnd.
        val label = Uri.encode(pin.name ?: "Pin from $who")
        val geo = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(String.format(Locale.US, "geo:%.5f,%.5f?q=%.5f,%.5f(%s)", pin.latitude, pin.longitude, pin.latitude, pin.longitude, label)),
        )
        val target = osmAndPackage()?.let { geo.setPackage(it) } ?: Intent(appContext, MainActivity::class.java)
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setColor(sender?.let(NodeColors::colorFor) ?: NodeColors.colorForId(pin.fromNodeId ?: 0))
            .setContentTitle("$who shared a pin")
            .setContentText(pin.name ?: String.format(Locale.US, "%.5f, %.5f", pin.latitude, pin.longitude))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(
                PendingIntent.getActivity(appContext, pin.id.hashCode(), target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            .build()
        try {
            manager.notify(pin.id.hashCode(), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission missing", e)
        }
    }

    companion object {
        val KEEP_FOR: Duration = Duration.ofHours(24)
        private const val CHANNEL_ID = "team_pins"
        private const val ACK_WAIT = 120_000L
    }
}
