package com.meshand.app.ui.nodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meshand.app.domain.model.DiscoveredRadio
import com.meshand.app.domain.model.MeshNode
import com.meshand.app.domain.model.OsmAndStatus
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.util.Locale

@Composable
fun NodesScreen(
    radio: DiscoveredRadio,
    nodes: List<MeshNode>,
    osmAnd: OsmAndStatus,
    onDisconnect: () -> Unit,
    onOsmAndEnabledChange: (Boolean) -> Unit,
    onShowOnOsmAnd: (MeshNode) -> Unit,
) {
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
                    Text("Connected: ${radio.name ?: radio.address}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Nodes: ${nodes.size} · with position: ${nodes.count { it.hasPosition }}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            }
        }
        item { OsmAndCard(osmAnd, onOsmAndEnabledChange) }
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
    }
}

@Composable
private fun OsmAndCard(status: OsmAndStatus, onEnabledChange: (Boolean) -> Unit) {
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
            Text(
                title + (node.shortName?.takeIf { node.longName != null }?.let { " ($it)" } ?: "") +
                    if (node.isOwnNode) "  (this radio)" else "",
                fontWeight = FontWeight.Bold,
            )
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
