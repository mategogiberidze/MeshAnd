package com.meshand.app.data.meshtastic

import com.meshand.app.domain.model.MeshNode
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.Position
import org.meshtastic.sdk.asNodeInfoUser
import org.meshtastic.sdk.asPosition
import java.time.Instant

/**
 * What a single live mesh packet tells us about its sender. The SDK (0.1.0) merges handshake
 * NodeInfo frames and telemetry into its node map, but not POSITION_APP / NODEINFO_APP packets,
 * nor per-packet SNR/hops/last-heard — so we fold those in ourselves.
 */
data class LiveUpdate(
    val nodeId: Long,
    val heardAt: Instant,
    val snr: Float?,
    val hopsAway: Int?,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Int? = null,
    val shortName: String? = null,
    val longName: String? = null,
    /** GPS fix time (`Position.timestamp`), only sent with the radio's "Timestamp" flag. */
    val fixTime: Instant? = null,
    val satellites: Int? = null,
    /** When the latest position packet arrived. */
    val positionHeardAt: Instant? = null,
    /** When the first packet carrying the current coordinates arrived. */
    val positionChangedAt: Instant? = null,
    /** True once a live packet changed the coordinates (not just the first live position). */
    val positionChangeSeen: Boolean = false,
    /** False when the channel truncates coordinates, so GPS jitter can't be seen. */
    val precisePosition: Boolean = true,
) {
    val hasPosition: Boolean get() = latitude != null && longitude != null

    /** Newer packet data wins; fields absent from [newer] keep their previous value. */
    fun mergedWith(newer: LiveUpdate): LiveUpdate {
        val moved = newer.hasPosition && hasPosition && (newer.latitude != latitude || newer.longitude != longitude)
        return LiveUpdate(
            nodeId = nodeId,
            heardAt = maxOf(heardAt, newer.heardAt),
            snr = newer.snr ?: snr,
            hopsAway = newer.hopsAway ?: hopsAway,
            latitude = if (newer.hasPosition) newer.latitude else latitude,
            longitude = if (newer.hasPosition) newer.longitude else longitude,
            altitude = if (newer.hasPosition) newer.altitude else altitude,
            shortName = newer.shortName ?: shortName,
            longName = newer.longName ?: longName,
            fixTime = if (newer.hasPosition) newer.fixTime else fixTime,
            satellites = if (newer.hasPosition) newer.satellites else satellites,
            positionHeardAt = if (newer.hasPosition) newer.positionHeardAt else positionHeardAt,
            positionChangedAt = when {
                !newer.hasPosition -> positionChangedAt
                !hasPosition || moved -> newer.positionChangedAt
                else -> positionChangedAt
            },
            positionChangeSeen = positionChangeSeen || moved,
            precisePosition = if (newer.hasPosition) newer.precisePosition else precisePosition,
        )
    }
}

/**
 * Converts Meshtastic protobuf types (Wire-generated, `org.meshtastic.proto`) into our
 * [MeshNode]. This is the only place that knows the wire representation of node data.
 */
object MeshtasticMapper {

    private const val COORD_SCALE = 1e-7

    /** Node numbers are uint32 on the wire but surface as a signed Kotlin Int. */
    fun nodeNumToLong(num: Int): Long = num.toLong() and 0xFFFF_FFFFL

    fun toMeshNode(info: NodeInfo, ownNodeNum: Int?): MeshNode {
        val user = info.user
        val position = info.position
        return MeshNode(
            id = nodeNumToLong(info.num),
            shortName = user?.short_name?.takeIf { it.isNotBlank() },
            longName = user?.long_name?.takeIf { it.isNotBlank() },
            latitude = position?.let(::latitudeOf),
            longitude = position?.let(::longitudeOf),
            altitude = position?.altitude,
            batteryLevel = info.device_metrics?.battery_level?.takeIf { it >= 0 }
                ?.let { if (it > 100) MeshNode.BATTERY_POWERED else it },
            // snr is a plain float (default 0) — 0 dB is indistinguishable from "never heard
            // directly", so only report it once the node has been heard at all.
            snr = info.snr.takeIf { info.last_heard != 0 },
            hopsAway = info.hops_away,
            lastSeen = epochSecondsToInstant(info.last_heard),
            isOwnNode = ownNodeNum != null && info.num == ownNodeNum,
            fixTime = position?.timestamp?.let(::epochSecondsToInstant),
            // NodeDB positions carry the time the radio last stored them (its clock).
            positionReportedAt = position?.takeIf { latitudeOf(it) != null }?.time?.let(::epochSecondsToInstant),
        )
    }

    /**
     * Firmware sends latitude_i/longitude_i as degrees * 1e7. A missing field or an exact 0
     * means "no fix"; (0,0) is in the Gulf of Guinea and treated as unknown, matching the
     * official clients.
     */
    fun latitudeOf(position: Position): Double? =
        position.latitude_i?.takeIf { it != 0 }?.let { it * COORD_SCALE }

    fun longitudeOf(position: Position): Double? =
        position.longitude_i?.takeIf { it != 0 }?.let { it * COORD_SCALE }

    /** `last_heard` / `time` are fixed32 epoch seconds; 0 means unknown. */
    fun epochSecondsToInstant(seconds: Int): Instant? =
        seconds.takeIf { it != 0 }?.let { Instant.ofEpochSecond(it.toLong() and 0xFFFF_FFFFL) }

    /**
     * Extracts sender info from a decoded inbound packet. [receivedAt] is the phone's clock:
     * `rx_time` comes from the radio's clock, which may be unset without a GPS fix.
     * Returns null for packets with no usable sender.
     */
    fun toLiveUpdate(packet: MeshPacket, receivedAt: Instant): LiveUpdate? {
        if (packet.from == 0) return null
        val position = packet.asPosition()
        val user = packet.asNodeInfoUser()
        val hasPosition = position != null && latitudeOf(position) != null && longitudeOf(position) != null
        return LiveUpdate(
            nodeId = nodeNumToLong(packet.from),
            heardAt = receivedAt,
            // rx_snr is 0 for packets the radio generated itself / not received over LoRa.
            snr = packet.rx_snr.takeIf { it != 0f },
            hopsAway = hopsAway(packet.hop_start, packet.hop_limit),
            latitude = position?.let(::latitudeOf),
            longitude = position?.let(::longitudeOf),
            altitude = position?.altitude,
            shortName = user?.short_name?.takeIf { it.isNotBlank() },
            longName = user?.long_name?.takeIf { it.isNotBlank() },
            // `time` is when the packet was sent, not when the GPS got the fix; `timestamp` is
            // the fix time (only with the radio's "Timestamp" position flag).
            fixTime = position?.timestamp?.let(::epochSecondsToInstant),
            satellites = position?.sats_in_view?.takeIf { it > 0 },
            positionHeardAt = receivedAt.takeIf { hasPosition },
            positionChangedAt = receivedAt.takeIf { hasPosition },
            // 32 bits (or 0 on old firmware) means full precision.
            precisePosition = position?.precision_bits?.let { it == 0 || it >= 32 } ?: true,
        )
    }

    /** hop_start is only set by firmware >= 2.3; without it hops can't be derived. */
    fun hopsAway(hopStart: Int, hopLimit: Int): Int? =
        if (hopStart > 0 && hopStart >= hopLimit) hopStart - hopLimit else null

    /**
     * Combines the SDK's NodeDB view ([base]) with packets received since connecting ([live]).
     * Live data was received after the NodeDB download, so its fields take precedence.
     */
    fun merge(id: Long, base: MeshNode?, live: LiveUpdate?, ownNodeId: Long?): MeshNode {
        val node = base ?: MeshNode(
            id = id, shortName = null, longName = null, latitude = null, longitude = null,
            altitude = null, batteryLevel = null, snr = null, hopsAway = null, lastSeen = null,
        )
        val merged = if (live == null) node else node.copy(
            shortName = live.shortName ?: node.shortName,
            longName = live.longName ?: node.longName,
            latitude = if (live.hasPosition) live.latitude else node.latitude,
            longitude = if (live.hasPosition) live.longitude else node.longitude,
            altitude = if (live.hasPosition) live.altitude else node.altitude,
            fixTime = if (live.hasPosition) live.fixTime ?: node.fixTime else node.fixTime,
            satellites = if (live.hasPosition) live.satellites else node.satellites,
            positionReportedAt = if (live.hasPosition) live.positionHeardAt else node.positionReportedAt,
            positionChangedAt = if (live.hasPosition) positionChangedAt(node, live) else node.positionChangedAt,
            snr = live.snr ?: node.snr,
            hopsAway = live.hopsAway ?: node.hopsAway,
            lastSeen = maxOfNullable(node.lastSeen, live.heardAt),
        )
        return merged.copy(isOwnNode = ownNodeId != null && id == ownNodeId)
    }

    /**
     * Before any live change, compare the first live position with the radio's NodeDB: different
     * coordinates are a change; the same ones were already known when the NodeDB entry was stored.
     */
    private fun positionChangedAt(base: MeshNode, live: LiveUpdate): Instant? = when {
        !live.precisePosition -> null
        live.positionChangeSeen || !base.hasPosition -> live.positionChangedAt
        base.latitude != live.latitude || base.longitude != live.longitude -> live.positionChangedAt
        else -> base.positionReportedAt
    }

    private fun maxOfNullable(a: Instant?, b: Instant): Instant = if (a == null || b > a) b else a
}
