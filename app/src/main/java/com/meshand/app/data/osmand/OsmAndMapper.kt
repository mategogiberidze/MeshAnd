package com.meshand.app.data.osmand

import com.meshand.app.domain.model.MeshNode
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
    /** Nodes not heard from for this long are drawn as stale. */
    val STALE_AFTER: Duration = Duration.ofMinutes(30)

    const val OWN_NODE_COLOR: Int = 0xFF1E88E5.toInt() // blue
    const val NODE_COLOR: Int = 0xFFF4511E.toInt() // orange

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
            // Absolute time: OsmAnd's menu isn't refreshed every second, so "N sec ago" would lie.
            add("Last seen: " + (node.lastSeen?.let { formatTime(it, now, zone) } ?: "unknown"))
        }
        return MapPointSpec(
            id = node.nodeIdHex,
            shortName = label,
            fullName = node.longName ?: label,
            typeName = if (node.isOwnNode) "Meshtastic · this radio (${node.nodeIdHex})" else "Meshtastic node ${node.nodeIdHex}",
            color = if (node.isOwnNode) OWN_NODE_COLOR else NODE_COLOR,
            latitude = lat,
            longitude = lon,
            details = details,
            stale = isStale(node.lastSeen, now),
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
