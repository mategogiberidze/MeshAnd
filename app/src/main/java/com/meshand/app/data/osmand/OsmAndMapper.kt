package com.meshand.app.data.osmand

import com.meshand.app.domain.NodeColors
import com.meshand.app.domain.PositionAge
import com.meshand.app.domain.model.MeshNode
import com.meshand.app.domain.model.Pin
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Everything OsmAnd needs to draw one node, in plain Kotlin types so it can be unit-tested
 * without the Android runtime. [OsmAndBridge] turns it into an OsmAnd `AMapPoint`.
 */
data class MapPointSpec(
    /** Stable per node: the Meshtastic user id, e.g. `!a1b2c3d4`. */
    val id: String,
    /** Drawn on the map marker (OsmAnd shows only the first character on circle points). */
    val shortName: String,
    /** First line of OsmAnd's context menu. */
    val fullName: String,
    /** Second line of OsmAnd's context menu. */
    val typeName: String,
    /** ARGB marker background. */
    val color: Int,
    val latitude: Double,
    val longitude: Double,
    /** Extra lines shown in OsmAnd's context menu. */
    val details: List<String>,
    /** OsmAnd draws stale points greyed out. */
    val stale: Boolean,
)

object OsmAndMapper {
    /**
     * Nodes not heard from for this long are drawn greyed out. One hour matches the firmware's
     * minimum position interval on the default public channel, so idle teammates don't flicker.
     */
    val STALE_AFTER: Duration = Duration.ofMinutes(60)

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    /** Returns null for nodes without a position: OsmAnd can't place them. */
    fun toSpec(node: MeshNode, now: Instant, zone: ZoneId = ZoneId.systemDefault()): MapPointSpec? {
        val lat = node.latitude ?: return null
        val lon = node.longitude ?: return null
        val label = node.shortName ?: node.nodeIdHex.takeLast(4)
        val details = buildList {
            node.altitude?.let { add("Altitude: $it m") }
            node.batteryLevel?.let {
                add(if (it == MeshNode.BATTERY_POWERED) "Battery: powered" else "Battery: $it%")
            }
            node.snr?.let { add(String.format(Locale.US, "SNR: %.1f dB", it)) }
            node.hopsAway?.let { add("Hops: $it") }
            // Absolute times: OsmAnd's menu isn't refreshed every second, so "N sec ago" would lie.
            val updated = PositionAge.updatedAt(node, now)
            val reported = PositionAge.reportedAt(node, now)
            when {
                updated != null -> add("Position from: " + formatTime(updated, now, zone))
                reported != null -> add("Position reported: " + formatTime(reported, now, zone))
            }
            if (PositionAge.isStuck(node, now)) add("GPS not updating: same position re-sent since then")
            add("Last seen: " + (node.lastSeen?.let { formatTime(it, now, zone) } ?: "unknown"))
        }
        return MapPointSpec(
            id = node.nodeIdHex,
            shortName = label,
            fullName = node.longName ?: label,
            typeName = if (node.isOwnNode) "Meshtastic · this radio (${node.nodeIdHex})" else "Meshtastic node ${node.nodeIdHex}",
            color = NodeColors.colorFor(node),
            latitude = lat,
            longitude = lon,
            details = details,
            stale = isStale(node.lastSeen, now),
        )
    }

    /** A shared pin, drawn as a map pin in the sender's colour. */
    fun pinSpec(pin: Pin, sender: MeshNode?, now: Instant, zone: ZoneId = ZoneId.systemDefault()): MapPointSpec {
        val who = when {
            pin.mine -> "you"
            sender != null -> sender.longName ?: sender.shortName ?: sender.nodeIdHex
            pin.fromNodeId != null -> "!%08x".format(pin.fromNodeId)
            else -> "a teammate"
        }
        val time = formatTime(pin.time, now, zone)
        return MapPointSpec(
            id = pin.id,
            shortName = pin.name ?: "Pin",
            fullName = pin.name ?: "Pin",
            typeName = "Pin from $who · $time",
            color = sender?.let(NodeColors::colorFor) ?: NodeColors.colorForId(pin.fromNodeId ?: 0),
            latitude = pin.latitude,
            longitude = pin.longitude,
            details = listOf(
                String.format(Locale.US, "Coordinates: %.5f, %.5f", pin.latitude, pin.longitude),
                "Shared by $who at $time",
            ),
            stale = false,
        )
    }

    fun isStale(lastSeen: Instant?, now: Instant): Boolean =
        lastSeen == null || Duration.between(lastSeen, now) > STALE_AFTER

    private fun formatTime(time: Instant, now: Instant, zone: ZoneId): String {
        val local = time.atZone(zone)
        return if (local.toLocalDate() == now.atZone(zone).toLocalDate()) {
            local.format(timeFormat)
        } else {
            local.format(dateTimeFormat)
        }
    }
}
