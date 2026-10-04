package com.meshand.app.ui.team

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshand.app.MainActivity
import com.meshand.app.domain.Geo
import com.meshand.app.domain.PositionAge
import com.meshand.app.domain.model.Pin
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.MeshNode
import com.meshand.app.domain.model.OsmAndStatus
import com.meshand.app.graph
import com.meshand.app.ui.common.ColorDot
import com.meshand.app.ui.common.PinIcon
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * "Meshtastic team" list, opened from OsmAnd (map widget or side-menu item) or from MeshAnd.
 * Tapping Show / Navigate hands the member to OsmAnd and closes this screen, so the user lands
 * back on the OsmAnd map. Runs in its own task (see manifest) so Back returns to OsmAnd.
 */
class TeamActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = applicationContext.graph
        setContent {
            MaterialTheme {
                val nodes by graph.repository.activeNodes.collectAsStateWithLifecycle()
                val watched by graph.settings.watchedNodeIds.collectAsStateWithLifecycle()
                val silenceMinutes by graph.settings.silenceAlertMinutes.collectAsStateWithLifecycle()
                val trails by graph.trails.trails.collectAsStateWithLifecycle()
                val shownTrails by graph.osmAnd.shownTrails.collectAsStateWithLifecycle()
                val pins by graph.pins.pins.collectAsStateWithLifecycle()
                val status by graph.client.status.collectAsStateWithLifecycle()
                val osmAnd by graph.osmAnd.status.collectAsStateWithLifecycle()
                val now by produceState(Instant.now()) {
                    while (true) {
                        delay(1_000)
                        value = Instant.now()
                    }
                }
                Scaffold(Modifier.fillMaxSize()) { padding ->
                    TeamScreen(
                        modifier = Modifier.padding(padding),
                        members = teamMembers(nodes),
                        status = status,
                        osmAndReady = osmAnd is OsmAndStatus.Showing,
                        now = now,
                        watched = watched,
                        silenceMinutes = silenceMinutes,
                        onWatchChange = { member, on -> graph.settings.setWatched(member.node.id, on) },
                        onShow = { graph.osmAnd.showOnMap(it.node); finish() },
                        onNavigate = { graph.osmAnd.navigateTo(it.node); finish() },
                        trailPointCount = { trails[it.node.id]?.size ?: 0 },
                        trailShown = { it.node.id in shownTrails },
                        onTrail = { member ->
                            if (member.node.id in shownTrails) {
                                graph.osmAnd.hideTrail(member.node.id)
                            } else {
                                graph.osmAnd.showTrail(member.node.id)
                                graph.osmAnd.showOnMap(member.node)
                                finish()
                            }
                        },
                        onResetTrail = { graph.trails.reset(it.node.id) },
                        pins = pins,
                        nodes = nodes,
                        onShowPin = { graph.osmAnd.showPin(it); finish() },
                        onNavigatePin = { graph.osmAnd.navigateToPin(it); finish() },
                        onRemovePin = { graph.pins.remove(it.id) },
                        onOpenMeshAnd = {
                            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            finish()
                        },
                    )
                }
            }
        }
    }

    companion object {
        /** Opened by OsmAnd's side-menu item (ACTION_VIEW). */
        const val DEEP_LINK = "meshand://team"
    }
}

@Composable
private fun TeamScreen(
    modifier: Modifier,
    members: List<TeamMember>,
    status: ConnectionStatus,
    osmAndReady: Boolean,
    now: Instant,
    watched: Set<Long>,
    silenceMinutes: Int,
    onWatchChange: (TeamMember, Boolean) -> Unit,
    onShow: (TeamMember) -> Unit,
    onNavigate: (TeamMember) -> Unit,
    trailPointCount: (TeamMember) -> Int,
    trailShown: (TeamMember) -> Boolean,
    onTrail: (TeamMember) -> Unit,
    onResetTrail: (TeamMember) -> Unit,
    pins: List<Pin>,
    nodes: List<MeshNode>,
    onShowPin: (Pin) -> Unit,
    onNavigatePin: (Pin) -> Unit,
    onRemovePin: (Pin) -> Unit,
    onOpenMeshAnd: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Meshtastic team (${members.size})", style = MaterialTheme.typography.headlineSmall)
            val line = when (status) {
                is ConnectionStatus.Connected -> "Live via ${status.radio.name ?: status.radio.address}. Distances from your radio."
                is ConnectionStatus.Reconnecting -> "Radio link lost, reconnecting. Showing last-known positions."
                is ConnectionStatus.Connecting -> "Connecting to the radio…"
                else -> "MeshAnd isn't connected to a radio. Showing last-known positions, if any."
            }
            Text(line, style = MaterialTheme.typography.bodySmall)
            Text(
                "Only members heard in the last 24 h are listed. " + if (silenceMinutes > 0) {
                    "Switch on \"Alert\" to be notified when someone isn't heard for $silenceMinutes min."
                } else {
                    "Teammate alerts are off (turn them on in MeshAnd)."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (!osmAndReady) {
                Text(
                    "OsmAnd link not active: turn on \"Show on OsmAnd\" in MeshAnd to jump to members.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (members.isEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No team members heard yet.")
                    OutlinedButton(onClick = onOpenMeshAnd) { Text("Open MeshAnd") }
                }
            }
        }
        items(members, key = { it.node.id }) { member ->
            MemberCard(
                member, osmAndReady, now,
                watched = member.node.id in watched,
                alertsEnabled = silenceMinutes > 0,
                onWatchChange = { onWatchChange(member, it) },
                onShow = onShow,
                onNavigate = onNavigate,
                trailPoints = trailPointCount(member),
                trailShown = trailShown(member),
                onTrail = { onTrail(member) },
                onResetTrail = { onResetTrail(member) },
            )
        }
        item {
            Text(
                "Shared pins (${pins.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                if (pins.isEmpty()) {
                    "None yet. To share a place: tap it in OsmAnd, then Share → MeshAnd pin."
                } else {
                    "Places shared over the mesh in the last 24 h, newest first."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(pins, key = { it.id }) { pin ->
            PinCard(pin, nodes, osmAndReady, now, onShowPin, onNavigatePin, onRemovePin)
        }
    }
}

@Composable
private fun PinCard(
    pin: Pin,
    nodes: List<MeshNode>,
    osmAndReady: Boolean,
    now: Instant,
    onShow: (Pin) -> Unit,
    onNavigate: (Pin) -> Unit,
    onRemove: (Pin) -> Unit,
) {
    val sender = nodes.firstOrNull { it.id == pin.fromNodeId }
    val who = if (pin.mine) "you" else sender?.let { it.longName ?: it.shortName } ?: "a teammate"
    val me = nodes.firstOrNull { it.isOwnNode }?.takeIf { it.hasPosition }
    val where = me?.let {
        val d = Geo.distanceMeters(it.latitude!!, it.longitude!!, pin.latitude, pin.longitude)
        val b = Geo.bearingDegrees(it.latitude, it.longitude, pin.latitude, pin.longitude)
        "${Geo.formatDistance(d)} ${Geo.compassPoint(b)}"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PinIcon(pin, sender)
                Text(
                    pin.name ?: String.format(Locale.US, "%.5f, %.5f", pin.latitude, pin.longitude),
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                listOfNotNull(where, "from $who", ago(pin.time, now)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onShow(pin) }, enabled = osmAndReady) { Text("Show on map") }
                OutlinedButton(onClick = { onNavigate(pin) }, enabled = osmAndReady) { Text("Navigate") }
                TextButton(onClick = { onRemove(pin) }) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun MemberCard(
    member: TeamMember,
    osmAndReady: Boolean,
    now: Instant,
    watched: Boolean,
    alertsEnabled: Boolean,
    onWatchChange: (Boolean) -> Unit,
    onShow: (TeamMember) -> Unit,
    onNavigate: (TeamMember) -> Unit,
    trailPoints: Int,
    trailShown: Boolean,
    onTrail: () -> Unit,
    onResetTrail: () -> Unit,
) {
    val node = member.node
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ColorDot(node)
                Text(
                    (node.longName ?: node.shortName ?: node.nodeIdHex) +
                        (node.shortName?.takeIf { node.longName != null }?.let { " ($it)" } ?: ""),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text("Alert", style = MaterialTheme.typography.bodySmall)
                Switch(checked = watched, onCheckedChange = onWatchChange, enabled = alertsEnabled)
            }
            Text(
                listOfNotNull(
                    member.distanceText ?: if (node.hasPosition) "distance unknown" else "no position yet",
                    positionAge(node, now),
                    node.lastSeen?.let { "heard ${ago(it, now)}" },
                    node.batteryLevel?.let { if (it > 100) "powered" else "battery $it%" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val enabled = osmAndReady && node.hasPosition
                Button(onClick = { onShow(member) }, enabled = enabled) { Text("Show on map") }
                OutlinedButton(onClick = { onNavigate(member) }, enabled = enabled) { Text("Navigate") }
            }
            // A trail needs at least two recorded positions to draw a line.
            val canDraw = osmAndReady && (trailShown || trailPoints >= 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTrail, enabled = canDraw) {
                    Text(if (trailShown) "Hide trail" else "Show trail")
                }
                TextButton(onClick = onResetTrail, enabled = trailPoints > 0) { Text("Reset") }
                Text(
                    when {
                        trailPoints >= 2 -> "$trailPoints positions recorded"
                        trailPoints == 1 -> "1 position so far"
                        else -> "no trail yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** "position 4 min old", flagged when the radio keeps re-sending an old position. */
private fun positionAge(node: MeshNode, now: Instant): String? {
    if (!node.hasPosition) return null
    val updated = PositionAge.updatedAt(node, now) ?: return null
    val age = if (Duration.between(updated, now).seconds < 60) {
        "position new"
    } else {
        "position ${ago(updated, now).removeSuffix(" ago")} old"
    }
    return if (PositionAge.isStuck(node, now)) "$age (GPS not updating)" else age
}

private fun ago(then: Instant, now: Instant): String {
    val s = Duration.between(then, now).seconds
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}
