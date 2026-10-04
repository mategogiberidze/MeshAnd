package com.meshand.app.ui.team

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshand.app.MainActivity
import com.meshand.app.R
import com.meshand.app.domain.PositionAge
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.MeshNode
import com.meshand.app.domain.model.OsmAndStatus
import com.meshand.app.graph
import com.meshand.app.ui.common.ColorDot
import com.meshand.app.ui.common.DistanceBadge
import com.meshand.app.ui.common.HintText
import com.meshand.app.ui.common.SectionCard
import com.meshand.app.ui.common.formatAgo
import com.meshand.app.ui.nodes.PinsPage
import com.meshand.app.ui.theme.MeshAndTheme
import com.meshand.app.ui.theme.good
import com.meshand.app.ui.theme.warning
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * "Meshtastic team", opened from OsmAnd (map widget or side-menu item) or from MeshAnd: the
 * team nearest first, and shared pins. Show / Navigate hand the member or pin to OsmAnd and close
 * this screen, so the user lands back on the OsmAnd map. Runs in its own task (see manifest) so
 * Back returns to OsmAnd.
 */
@OptIn(ExperimentalMaterial3Api::class)
class TeamActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = applicationContext.graph
        setContent {
            MeshAndTheme {
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
                var page by rememberSaveable { mutableStateOf(TeamPage.Team) }
                val members = teamMembers(nodes)
                val osmAndReady = osmAnd is OsmAndStatus.Showing
                val openMeshAnd = {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text("Meshtastic team")
                                    ConnectionLine(status)
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Back to the map")
                                }
                            },
                            actions = {
                                IconButton(onClick = openMeshAnd) {
                                    Icon(painterResource(R.drawable.ic_stat_mesh), contentDescription = "Open MeshAnd")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        )
                    },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            TeamPage.entries.forEach { p ->
                                val count = if (p == TeamPage.Team) members.size else pins.size
                                NavigationBarItem(
                                    selected = page == p,
                                    onClick = { page = p },
                                    label = { Text(p.label) },
                                    icon = {
                                        BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) {
                                            Icon(painterResource(p.icon), contentDescription = null)
                                        }
                                    },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        when (page) {
                            TeamPage.Team -> TeamList(
                                members = members,
                                osmAndReady = osmAndReady,
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
                                onOpenMeshAnd = openMeshAnd,
                            )
                            TeamPage.Pins -> PinsPage(
                                pins = pins,
                                nodes = nodes,
                                osmAndShowing = osmAndReady,
                                onShow = { graph.osmAnd.showPin(it); finish() },
                                onRemove = { graph.pins.remove(it.id) },
                                onClear = { graph.pins.clear() },
                                onNavigate = { graph.osmAnd.navigateToPin(it); finish() },
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Opened by OsmAnd's side-menu item (ACTION_VIEW). */
        const val DEEP_LINK = "meshand://team"
    }
}

private enum class TeamPage(val label: String, @param:DrawableRes val icon: Int) {
    Team("Team", R.drawable.ic_group),
    Pins("Pins", R.drawable.ic_pin),
}

@Composable
private fun ConnectionLine(status: ConnectionStatus) {
    val colors = MaterialTheme.colorScheme
    val (text, color) = when (status) {
        is ConnectionStatus.Connected -> "Live via ${status.radio.name ?: status.radio.address}" to colors.good
        is ConnectionStatus.Reconnecting -> "Reconnecting · last-known positions" to colors.warning
        is ConnectionStatus.Connecting -> "Connecting to the radio…" to colors.onSurfaceVariant
        else -> "Not connected · last-known positions" to colors.warning
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun TeamList(
    members: List<TeamMember>,
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
    onOpenMeshAnd: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            HintText(
                "Heard in the last 24 h, nearest first. " + if (silenceMinutes > 0) {
                    "\"Alert\" notifies you when someone isn't heard for $silenceMinutes min."
                } else {
                    "Teammate alerts are off (MeshAnd → Settings)."
                },
            )
            if (!osmAndReady) {
                HintText(
                    "OsmAnd link not active: turn on \"Show on OsmAnd\" in MeshAnd to jump to members.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (members.isEmpty()) {
            item {
                SectionCard {
                    Text("No team members heard yet.", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = onOpenMeshAnd) { Text("Open MeshAnd") }
                }
            }
        }
        items(members, key = { it.node.id }) { member ->
            MemberCard(
                member = member,
                osmAndReady = osmAndReady,
                now = now,
                watched = member.node.id in watched,
                alertsEnabled = silenceMinutes > 0,
                onWatchChange = { onWatchChange(member, it) },
                onShow = { onShow(member) },
                onNavigate = { onNavigate(member) },
                trailPoints = trailPointCount(member),
                trailShown = trailShown(member),
                onTrail = { onTrail(member) },
                onResetTrail = { onResetTrail(member) },
            )
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
    onShow: () -> Unit,
    onNavigate: () -> Unit,
    trailPoints: Int,
    trailShown: Boolean,
    onTrail: () -> Unit,
    onResetTrail: () -> Unit,
) {
    val node = member.node
    SectionCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ColorDot(node, Modifier.padding(top = 6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (node.longName ?: node.shortName ?: node.nodeIdHex) +
                        (node.shortName?.takeIf { node.longName != null }?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HintText(
                    listOfNotNull(
                        positionAge(node, now),
                        node.lastSeen?.let { "heard ${formatAgo(Duration.between(it, now).seconds)}" },
                        node.batteryLevel?.let { if (it > 100) "powered" else "battery $it%" },
                    ).joinToString(" · "),
                )
            }
            member.distanceMeters?.let { DistanceBadge(it) }
        }
        val canGo = osmAndReady && node.hasPosition
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onShow, enabled = canGo) { Text("Show on map") }
            OutlinedButton(onClick = onNavigate, enabled = canGo) { Text("Navigate") }
        }
        // A trail needs at least two recorded positions to draw a line.
        val canDraw = osmAndReady && (trailShown || trailPoints >= 2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = trailShown,
                onClick = onTrail,
                enabled = canDraw,
                label = { Text(if (trailPoints > 0) "Trail · $trailPoints" else "Trail") },
            )
            if (trailPoints > 0) TextButton(onClick = onResetTrail) { Text("Reset") }
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = watched,
                onClick = { onWatchChange(!watched) },
                enabled = alertsEnabled,
                label = { Text("Alert") },
            )
        }
    }
}

/** "position 4 min old", flagged when the radio keeps re-sending an old position. */
private fun positionAge(node: MeshNode, now: Instant): String? {
    if (!node.hasPosition) return null
    val updated = PositionAge.updatedAt(node, now) ?: return null
    val seconds = Duration.between(updated, now).seconds
    val age = if (seconds < 60) "position new" else "position ${formatAgo(seconds).removeSuffix(" ago")} old"
    return if (PositionAge.isStuck(node, now)) "$age (GPS not updating)" else age
}
