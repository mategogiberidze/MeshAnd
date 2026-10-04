package com.meshand.app.ui.nodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meshand.app.BuildConfig
import com.meshand.app.data.settings.AppSettings
import com.meshand.app.domain.GpsHealth
import com.meshand.app.domain.PositionAge
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.ui.common.ColorDot
import com.meshand.app.domain.model.MeshNode
import com.meshand.app.domain.model.OsmAndStatus
import com.meshand.app.domain.model.Pin
import com.meshand.app.ui.common.PinIcon
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.util.Locale

@Composable
fun NodesScreen(
    status: ConnectionStatus,
    nodes: List<MeshNode>,
    hiddenNodeCount: Int,
    silenceAlertMinutes: Int,
    onSilenceAlertMinutesChange: (Int) -> Unit,
    trailMinutes: Int,
    onTrailMinutesChange: (Int) -> Unit,
    onResetTrails: () -> Unit,
    showOwnRadioOnMap: Boolean,
    onShowOwnRadioChange: (Boolean) -> Unit,
    pins: List<Pin>,
    onShowPin: (Pin) -> Unit,
    onRemovePin: (Pin) -> Unit,
    onClearPins: () -> Unit,
    savedDataBytes: Long,
    onClearSavedData: () -> Unit,
    checkForUpdates: Boolean,
    onCheckForUpdatesChange: (Boolean) -> Unit,
    osmAnd: OsmAndStatus,
    onDisconnect: () -> Unit,
    onOsmAndEnabledChange: (Boolean) -> Unit,
    onShowOnOsmAnd: (MeshNode) -> Unit,
    onOpenTeam: () -> Unit,
) {
    val radio = when (status) {
        is ConnectionStatus.Connected -> status.radio
        is ConnectionStatus.Reconnecting -> status.radio
        else -> null
    }
    // Ticks every second so "Last seen: N sec ago" stays current without new data.
    val now by produceState(Instant.now()) {
        while (true) {
            delay(1_000)
            value = Instant.now()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    val radioName = radio?.name ?: radio?.address ?: "radio"
                    if (status is ConnectionStatus.Reconnecting) {
                        Text("Reconnecting: $radioName", style = MaterialTheme.typography.titleMedium, color = Warn)
                        Text(status.detail, style = MaterialTheme.typography.bodySmall)
                        Text("Showing last-known positions.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Connected: $radioName", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "Nodes: ${nodes.size} · with position: ${nodes.count { it.hasPosition }}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            }
        }
        item { GpsCard(GpsHealth.evaluate(nodes.firstOrNull { it.isOwnNode }, now), now) }
        item { OsmAndCard(osmAnd, onOsmAndEnabledChange, showOwnRadioOnMap, onShowOwnRadioChange) }
        item { AlertsCard(silenceAlertMinutes, onSilenceAlertMinutesChange) }
        item { TrailsCard(trailMinutes, onTrailMinutesChange, onResetTrails) }
        item {
            PinsCard(pins, nodes, now, osmAnd is OsmAndStatus.Showing, onShowPin, onRemovePin, onClearPins)
        }
        item { SavedDataCard(savedDataBytes, onClearSavedData) }
        item { OutlinedButton(onClick = onOpenTeam) { Text("Team list") } }
        if (hiddenNodeCount > 0) {
            item {
                Text(
                    "$hiddenNodeCount node(s) not heard in the last 24 h are hidden.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(nodes, key = { it.id }) { node ->
            NodeCard(
                node = node,
                now = now,
                onShowOnOsmAnd = if (osmAnd is OsmAndStatus.Showing && node.hasPosition) {
                    { onShowOnOsmAnd(node) }
                } else {
                    null
                },
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("MeshAnd ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Check GitHub for new versions when MeshAnd opens",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = checkForUpdates, onCheckedChange = onCheckForUpdatesChange)
            }
        }
    }
}

@Composable
private fun TrailsCard(minutes: Int, onChange: (Int) -> Unit, onReset: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Trails", fontWeight = FontWeight.Bold)
            Text(
                "How much of each teammate's path to keep. Show a trail from the Team list, or with " +
                    "\"Trail\" when you tap someone on the OsmAnd map.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AppSettings.TRAIL_MINUTE_OPTIONS.forEach { option ->
                    val label = if (option % 60 == 0) "${option / 60}h" else "${option}m"
                    if (option == minutes) {
                        Button(onClick = {}) { Text(label) }
                    } else {
                        TextButton(onClick = { onChange(option) }) { Text(label) }
                    }
                }
            }
            OutlinedButton(onClick = onReset) { Text("Reset all trails") }
        }
    }
}

@Composable
private fun PinsCard(
    pins: List<Pin>,
    nodes: List<MeshNode>,
    now: Instant,
    osmAndReady: Boolean,
    onShow: (Pin) -> Unit,
    onRemove: (Pin) -> Unit,
    onClear: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Pins", fontWeight = FontWeight.Bold)
            Text(
                "To send a place to your team: in OsmAnd, tap it on the map, then Share → MeshAnd pin. " +
                    "Pins from the last 24 h are shown on the OsmAnd map as map pins in the sender's colour.",
                style = MaterialTheme.typography.bodySmall,
            )
            pins.forEach { pin ->
                val sender = nodes.firstOrNull { it.id == pin.fromNodeId }
                val who = if (pin.mine) "you" else sender?.let { it.longName ?: it.shortName } ?: "a teammate"
                val delivery = when (pin.delivery) {
                    Pin.Delivery.SENDING -> " · sending…"
                    Pin.Delivery.SENT -> " · sent"
                    Pin.Delivery.RELAYED -> " · relayed by the mesh ✓"
                    Pin.Delivery.FAILED -> " · not confirmed"
                    null -> ""
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PinIcon(pin, sender)
                    Column(Modifier.weight(1f)) {
                        Text(pin.name ?: "Pin", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "from $who, ${formatAgo(pin.time, now)}$delivery",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (osmAndReady) TextButton(onClick = { onShow(pin) }) { Text("Show") }
                    TextButton(onClick = { onRemove(pin) }) { Text("Remove") }
                }
            }
            if (pins.isNotEmpty()) OutlinedButton(onClick = onClear) { Text("Remove all pins") }
        }
    }
}

@Composable
private fun SavedDataCard(bytes: Long, onClear: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Saved data", fontWeight = FontWeight.Bold)
            Text(
                "Trails and pins are saved on this phone, so they're still there after MeshAnd or the " +
                    "phone restarts. Using ${formatBytes(bytes)}.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { confirming = true }) { Text("Clear saved data") }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Clear saved data?") },
            text = { Text("All trails and pins are deleted from this phone. Trails start again from everyone's current position.") },
            confirmButton = {
                TextButton(onClick = { confirming = false; onClear() }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes bytes"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** Is our own radio's GPS producing fresh positions? */
@Composable
private fun GpsCard(status: GpsHealth.Status, now: Instant) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Your radio's GPS", fontWeight = FontWeight.Bold)
            val sats = status.satellites?.let { " · $it satellites" } ?: ""
            val (text, color) = when (status.level) {
                GpsHealth.Level.GOOD ->
                    "OK · new position ${formatAgo(status.fixTime!!, now)}$sats" to Good
                GpsHealth.Level.STALE ->
                    "No new GPS position for ${formatAgo(status.fixTime!!, now).removeSuffix(" ago")}$sats. " +
                        "The radio is re-sending an old one. Is it outdoors with a view of the sky?" to Warn
                GpsHealth.Level.UNKNOWN ->
                    "Checking… waiting for your radio's next position report (up to 10 min)." to Color.Unspecified
                GpsHealth.Level.NONE ->
                    "No position from your radio yet. Check that its GPS is enabled and give it a few minutes outdoors." to Warn
            }
            Text(text, color = color, style = MaterialTheme.typography.bodySmall)
            Text(
                if (status.exact) {
                    "Uses the GPS fix time your radio sends."
                } else {
                    "A working GPS moves the position by a few metres on every fix, so MeshAnd checks " +
                        "whether the position changes between reports. For exact fix times, switch on " +
                        "\"Timestamp\" in the radio's position flags."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AlertsCard(minutes: Int, onChange: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Teammate alerts", fontWeight = FontWeight.Bold)
            Text(
                "Notify when a teammate you switched \"Alert\" on for (in the Team list) isn't heard for:",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AppSettings.SILENCE_MINUTE_OPTIONS.forEach { option ->
                    val label = when {
                        option == 0 -> "Off"
                        option % 60 == 0 -> "${option / 60}h"
                        else -> "${option}m"
                    }
                    if (option == minutes) {
                        Button(onClick = {}) { Text(label) }
                    } else {
                        TextButton(onClick = { onChange(option) }) { Text(label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OsmAndCard(
    status: OsmAndStatus,
    onEnabledChange: (Boolean) -> Unit,
    showOwnRadio: Boolean,
    onShowOwnRadioChange: (Boolean) -> Unit,
) {
    val enabled = status !is OsmAndStatus.Off && status !is OsmAndStatus.NotInstalled
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Show on OsmAnd", fontWeight = FontWeight.Bold)
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            val (text, color) = when (status) {
                OsmAndStatus.Off -> "Off" to Color.Unspecified
                OsmAndStatus.NotInstalled -> "OsmAnd is not installed (or too old: needs the AIDL V2 API)." to Warn
                is OsmAndStatus.Connecting -> status.detail to Color.Unspecified
                is OsmAndStatus.NotAllowed ->
                    "OsmAnd is blocking MeshAnd. Open OsmAnd → Menu → Plugins, switch MeshAnd on, " +
                        "then come back. (${status.packageName})" to Warn
                is OsmAndStatus.Showing -> "Showing ${status.nodeCount} node(s) on the map (${status.packageName})" to Good
                is OsmAndStatus.Error -> status.message to Warn
            }
            Text(text, color = color, style = MaterialTheme.typography.bodySmall)
            if (enabled) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Show my radio on the map", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = showOwnRadio, onCheckedChange = onShowOwnRadioChange)
                }
            }
        }
    }
}

private val Good = Color(0xFF2E7D32)
private val Warn = Color(0xFFC62828)

@Composable
private fun NodeCard(node: MeshNode, now: Instant, onShowOnOsmAnd: (() -> Unit)?) {
    val colors = if (node.isOwnNode) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val title = node.longName ?: node.shortName ?: "Unknown"
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ColorDot(node)
                Text(
                    title + (node.shortName?.takeIf { node.longName != null }?.let { " ($it)" } ?: "") +
                        if (node.isOwnNode) "  (this radio)" else "",
                    fontWeight = FontWeight.Bold,
                )
            }
            Field("ID", node.nodeIdHex)
            Field(
                "Position",
                if (node.hasPosition) {
                    String.format(Locale.US, "%.5f, %.5f", node.latitude, node.longitude)
                } else {
                    "unknown"
                },
            )
            Field("Altitude", node.altitude?.let { "$it m" } ?: "—")
            Field(
                "Battery",
                when (val b = node.batteryLevel) {
                    null -> "—"
                    MeshNode.BATTERY_POWERED -> "Powered"
                    else -> "$b%"
                },
            )
            Field("SNR", node.snr?.let { String.format(Locale.US, "%.1f dB", it) } ?: "—")
            Field("Hops", node.hopsAway?.toString() ?: "—")
            Field("Position from", positionAgeText(node, now))
            Field("Last seen", node.lastSeen?.let { formatAgo(it, now) } ?: "never")
            if (onShowOnOsmAnd != null) {
                TextButton(onClick = onShowOnOsmAnd) { Text("Show on OsmAnd") }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row {
        Text("$label: ", style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private fun positionAgeText(node: MeshNode, now: Instant): String {
    if (!node.hasPosition) return "—"
    val updated = PositionAge.updatedAt(node, now)
    val reported = PositionAge.reportedAt(node, now)
    return when {
        updated == null && reported == null -> "unknown"
        updated == null -> "reported ${formatAgo(reported!!, now)}"
        PositionAge.isStuck(node, now) -> "${formatAgo(updated, now)} (GPS not updating)"
        else -> formatAgo(updated, now)
    }
}

private fun formatAgo(then: Instant, now: Instant): String {
    val s = Duration.between(then, now).seconds
    return when {
        s < 0 -> "just now"
        s < 60 -> "$s sec ago"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ${s % 3600 / 60} min ago"
        else -> "${s / 86_400} d ago"
    }
}
