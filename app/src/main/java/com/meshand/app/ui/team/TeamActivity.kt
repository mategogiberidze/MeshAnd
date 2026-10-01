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
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.OsmAndStatus
import com.meshand.app.graph
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

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
                val nodes by graph.repository.nodes.collectAsStateWithLifecycle()
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
                        onShow = { graph.osmAnd.showOnMap(it.node); finish() },
                        onNavigate = { graph.osmAnd.navigateTo(it.node); finish() },
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
    onShow: (TeamMember) -> Unit,
    onNavigate: (TeamMember) -> Unit,
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
            MemberCard(member, osmAndReady, now, onShow, onNavigate)
        }
    }
}

@Composable
private fun MemberCard(
    member: TeamMember,
    osmAndReady: Boolean,
    now: Instant,
    onShow: (TeamMember) -> Unit,
    onNavigate: (TeamMember) -> Unit,
) {
    val node = member.node
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                (node.longName ?: node.shortName ?: node.nodeIdHex) +
                    (node.shortName?.takeIf { node.longName != null }?.let { " ($it)" } ?: ""),
                fontWeight = FontWeight.Bold,
            )
            Text(
                listOfNotNull(
                    member.distanceText ?: if (node.hasPosition) "distance unknown" else "no position yet",
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
        }
    }
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
