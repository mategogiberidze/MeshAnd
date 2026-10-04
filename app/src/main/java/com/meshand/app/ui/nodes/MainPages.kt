package com.meshand.app.ui.nodes

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meshand.app.R
import com.meshand.app.domain.Geo
import com.meshand.app.domain.GpsHealth
import com.meshand.app.domain.PositionAge
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.MeshNode
import com.meshand.app.domain.model.OsmAndStatus
import com.meshand.app.domain.model.Pin
import com.meshand.app.ui.common.ColorDot
import com.meshand.app.ui.common.DistanceBadge
import com.meshand.app.ui.common.HintText
import com.meshand.app.ui.common.PinIcon
import com.meshand.app.ui.common.SectionCard
import com.meshand.app.ui.common.StatusDot
import com.meshand.app.ui.common.SwitchRow
import com.meshand.app.ui.common.formatAgo
import com.meshand.app.ui.common.formatWhen
import com.meshand.app.ui.theme.good
import com.meshand.app.ui.theme.warning
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.util.Locale

/* The three main pages: Radio (status), Nodes and Pins. Each scrolls on its own. */

/** "N min ago" texts stay current without new data. */
@Composable
private fun rememberNow(): Instant {
    val now by produceState(Instant.now()) {
        while (true) {
            delay(1_000)
            value = Instant.now()
        }
    }
    return now
}

/** Radio page while connected: link, own GPS, OsmAnd, and shortcuts to the other pages. */
@Composable
fun StatusPage(
    status: ConnectionStatus,
    nodes: List<MeshNode>,
    osmAnd: OsmAndStatus,
    pinCount: Int,
    onDisconnect: () -> Unit,
    onOsmAndEnabledChange: (Boolean) -> Unit,
    onOpenTeam: () -> Unit,
    onOpenNodes: () -> Unit,
    onOpenPins: () -> Unit,
) {
    val now = rememberNow()
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatusCard(status, nodes, osmAnd, now, onDisconnect, onOsmAndEnabledChange) }
        item {
            Button(onClick = onOpenTeam, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(painterResource(R.drawable.ic_group), contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Team list", style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            SectionCard {
                OverviewRow(
                    "Nodes",
                    "${nodes.size} heard in the last 24 h · ${nodes.count { it.hasPosition }} with position",
                    onOpenNodes,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                OverviewRow("Pins", if (pinCount == 0) "none shared yet" else "$pinCount shared in the last 24 h", onOpenPins)
            }
        }
    }
}

@Composable
private fun OverviewRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HintText(detail)
        }
        Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Radio link, own GPS, and the OsmAnd switch: everything that must be OK in the field. */
@Composable
private fun StatusCard(
    status: ConnectionStatus,
    nodes: List<MeshNode>,
    osmAnd: OsmAndStatus,
    now: Instant,
    onDisconnect: () -> Unit,
    onOsmAndEnabledChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    SectionCard {
        // Radio
        val radio = (status as? ConnectionStatus.Connected)?.radio ?: (status as? ConnectionStatus.Reconnecting)?.radio
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    radio?.name ?: radio?.address ?: "Radio",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (status is ConnectionStatus.Reconnecting) {
                        StatusDot(colors.warning)
                        Text("Reconnecting · showing last-known positions", color = colors.warning, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        StatusDot(colors.good)
                        Text("Connected", color = colors.good, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
        }
        HorizontalDivider(color = colors.outlineVariant)

        // Own GPS
        val gps = GpsHealth.evaluate(nodes.firstOrNull { it.isOwnNode }, now)
        val sats = gps.satellites?.let { " · $it satellites" }.orEmpty()
        val age = gps.fixTime?.let { formatAgo(Duration.between(it, now).seconds) }
        val (line, color, hint) = when (gps.level) {
            GpsHealth.Level.GOOD -> Triple("Working · new position $age$sats", colors.good, null)
            GpsHealth.Level.STALE -> Triple(
                "No new position for ${age?.removeSuffix(" ago")}$sats",
                colors.warning,
                "The radio keeps re-sending its last position. Is it outdoors with a view of the sky?",
            )
            GpsHealth.Level.UNKNOWN -> Triple("Checking… waiting for the next position report", colors.onSurfaceVariant, null)
            GpsHealth.Level.NONE -> Triple(
                "No position yet",
                colors.warning,
                "Check that the radio's GPS is on, and give it a few minutes outdoors.",
            )
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(color, Modifier.padding(top = 6.dp))
            Column {
                Text("Your GPS", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                Text(line, style = MaterialTheme.typography.bodyLarge, color = color)
                if (hint != null) HintText(hint)
                if (gps.level != GpsHealth.Level.GOOD && gps.level != GpsHealth.Level.NONE && !gps.exact) {
                    HintText("Tip: switch on \"Timestamp\" in the radio's position flags for exact GPS times.")
                }
            }
        }
        HorizontalDivider(color = colors.outlineVariant)

        // OsmAnd
        val enabled = osmAnd !is OsmAndStatus.Off && osmAnd !is OsmAndStatus.NotInstalled
        SwitchRow(title = "Show on OsmAnd", checked = enabled, onCheckedChange = onOsmAndEnabledChange)
        val (osmText, osmColor) = when (osmAnd) {
            OsmAndStatus.Off -> null to colors.onSurfaceVariant
            OsmAndStatus.NotInstalled -> "OsmAnd is not installed (or too old)." to colors.error
            is OsmAndStatus.Connecting -> osmAnd.detail to colors.onSurfaceVariant
            is OsmAndStatus.NotAllowed ->
                "OsmAnd is blocking MeshAnd. Open OsmAnd → Menu → Plugins and switch MeshAnd on." to colors.error
            is OsmAndStatus.Showing -> "${osmAnd.nodeCount} on the map" to colors.good
            is OsmAndStatus.Error -> osmAnd.message to colors.error
        }
        if (osmText != null) HintText(osmText, color = osmColor)
    }
}

/** Every node heard in the last 24 h, with a search box once the list gets long. */
@Composable
fun NodesPage(
    nodes: List<MeshNode>,
    hiddenNodeCount: Int,
    osmAndShowing: Boolean,
    onShowOnOsmAnd: (MeshNode) -> Unit,
) {
    val now = rememberNow()
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(nodes, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            nodes
        } else {
            nodes.filter { n ->
                listOfNotNull(n.longName, n.shortName, n.nodeIdHex).any { it.contains(q, ignoreCase = true) }
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (nodes.size > SEARCH_FROM) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search by name or ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            HintText(
                when {
                    nodes.isEmpty() -> "No nodes heard yet."
                    query.isNotBlank() -> "${shown.size} of ${nodes.size} match. Tap a node for details."
                    else -> "Tap a node for details."
                } + if (hiddenNodeCount > 0) " $hiddenNodeCount more not heard in 24 h are hidden." else "",
            )
        }
        items(shown, key = { it.id }) { node ->
            NodeCard(node, now, onShowOnOsmAnd = if (osmAndShowing && node.hasPosition) ({ onShowOnOsmAnd(node) }) else null)
        }
    }
}

/** One line per node; tap for details. */
@Composable
private fun NodeCard(node: MeshNode, now: Instant, onShowOnOsmAnd: (() -> Unit)?) {
    var expanded by rememberSaveable(node.id) { mutableStateOf(false) }
    SectionCard(Modifier.animateContentSize().clickable { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ColorDot(node)
            Text(
                node.longName ?: node.shortName ?: node.nodeIdHex,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (node.isOwnNode) {
                Text("this radio", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            } else {
                node.lastSeen?.let { HintText(formatAgo(Duration.between(it, now).seconds)) }
            }
        }
        HintText(
            listOfNotNull(
                positionAgeText(node, now),
                node.batteryLevel?.let { if (it == MeshNode.BATTERY_POWERED) "powered" else "battery $it%" },
                node.hopsAway?.let { if (it == 0) "direct" else "$it hop(s)" },
            ).joinToString(" · "),
        )
        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Field("ID", node.nodeIdHex)
            Field(
                "Position",
                if (node.hasPosition) String.format(Locale.US, "%.5f, %.5f", node.latitude, node.longitude) else "unknown",
            )
            Field("Altitude", node.altitude?.let { "$it m" } ?: "—")
            Field("SNR", node.snr?.let { String.format(Locale.US, "%.1f dB", it) } ?: "—")
            Field("Last heard", node.lastSeen?.let { formatAgo(Duration.between(it, now).seconds) } ?: "never")
            if (onShowOnOsmAnd != null) {
                TextButton(onClick = onShowOnOsmAnd) { Text("Show on OsmAnd") }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row {
        Text("$label  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

private fun positionAgeText(node: MeshNode, now: Instant): String {
    if (!node.hasPosition) return "no position yet"
    val updated = PositionAge.updatedAt(node, now)
    val reported = PositionAge.reportedAt(node, now)
    return when {
        updated == null && reported == null -> "position age unknown"
        updated == null -> "position reported ${formatAgo(Duration.between(reported, now).seconds)}"
        PositionAge.isStuck(node, now) -> "GPS not updating"
        else -> "position ${formatAgo(Duration.between(updated, now).seconds)}"
    }
}

/** Pins shared over the mesh in the last 24 h, newest first, one card each. */
@Composable
fun PinsPage(
    pins: List<Pin>,
    nodes: List<MeshNode>,
    osmAndShowing: Boolean,
    onShow: (Pin) -> Unit,
    onRemove: (Pin) -> Unit,
    onClear: () -> Unit,
    /** Shown only where navigating makes sense (the team list opened from OsmAnd). */
    onNavigate: ((Pin) -> Unit)? = null,
) {
    val now = rememberNow()
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            HintText(
                (if (pins.isEmpty()) "No pins yet. " else "") +
                    "To share a place: tap it in OsmAnd, then Share → MeshAnd pin. Pins show on the OsmAnd map in the sender's colour.",
            )
        }
        items(pins, key = { it.id }) { pin -> PinCard(pin, nodes, now, osmAndShowing, onShow, onRemove, onNavigate) }
        if (pins.size > 1) {
            item { TextButton(onClick = { confirmClear = true }) { Text("Remove all pins") } }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Remove all pins?") },
            text = { Text("They disappear from this phone and its OsmAnd map. Teammates keep theirs.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("Remove all") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PinCard(
    pin: Pin,
    nodes: List<MeshNode>,
    now: Instant,
    osmAndReady: Boolean,
    onShow: (Pin) -> Unit,
    onRemove: (Pin) -> Unit,
    onNavigate: ((Pin) -> Unit)?,
) {
    val sender = nodes.firstOrNull { it.id == pin.fromNodeId }
    val me = nodes.firstOrNull { it.isOwnNode }?.takeIf { it.hasPosition }
    val meters = me?.let { Geo.distanceMeters(it.latitude!!, it.longitude!!, pin.latitude, pin.longitude) }
    val who = if (pin.mine) "you" else sender?.let { it.longName ?: it.shortName } ?: "a teammate"
    val delivery = when (pin.delivery) {
        Pin.Delivery.SENDING -> " · sending…"
        Pin.Delivery.SENT -> " · sent"
        Pin.Delivery.RELAYED -> " · relayed ✓"
        Pin.Delivery.FAILED -> " · not confirmed"
        null -> ""
    }
    SectionCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PinIcon(pin, sender)
            Column(Modifier.weight(1f)) {
                Text(
                    pin.name ?: String.format(Locale.US, "%.5f, %.5f", pin.latitude, pin.longitude),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HintText("from $who · ${formatWhen(pin.time, now)}$delivery")
            }
            if (meters != null) DistanceBadge(meters)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (osmAndReady) Button(onClick = { onShow(pin) }) { Text("Show on map") }
            if (osmAndReady && onNavigate != null) OutlinedButton(onClick = { onNavigate(pin) }) { Text("Navigate") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onRemove(pin) }) { Text("Remove") }
        }
    }
}

/** Below this many nodes a search box is just clutter. */
private const val SEARCH_FROM = 6
