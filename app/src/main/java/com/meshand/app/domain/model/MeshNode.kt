package com.meshand.app.domain.model

import java.time.Instant

/**
 * App-owned view of a Meshtastic node. Deliberately free of SDK/protobuf types so the UI
 * (and later the OsmAnd bridge) never depends on the Meshtastic wire format.
 *
 * All fields except [id] are optional: the radio's NodeDB is frequently partial.
 */
data class MeshNode(
    /** Node number as an unsigned 32-bit value (protobuf `NodeInfo.num`). */
    val id: Long,
    val shortName: String?,
    val longName: String?,
    val latitude: Double?,
    val longitude: Double?,
    /** Metres above mean sea level. */
    val altitude: Int?,
    /** 0–100 %, or [BATTERY_POWERED] when the node reports external power. */
    val batteryLevel: Int?,
    val snr: Float?,
    val hopsAway: Int?,
    val lastSeen: Instant?,
    /** True for the radio the phone is connected to. */
    val isOwnNode: Boolean = false,
    /**
     * When the GPS took the reported position (`Position.timestamp`). Radios only send it with the
     * "Timestamp" position flag, which is off by default.
     */
    val fixTime: Instant? = null,
    /** When the node last reported its position (phone clock for live packets). */
    val positionReportedAt: Instant? = null,
    /**
     * When MeshAnd saw the coordinates change, or the best known upper bound. A GPS with a fix
     * jitters by a few metres, so repeated identical coordinates mean the radio is re-sending an
     * old position. Null when unknown or when the channel sends imprecise positions.
     */
    val positionChangedAt: Instant? = null,
    /** Satellites in view for the last position, if the radio reports it. */
    val satellites: Int? = null,
) {
    /** Canonical Meshtastic user id, e.g. `!a1b2c3d4`. */
    val nodeIdHex: String get() = "!%08x".format(id)

    val hasPosition: Boolean get() = latitude != null && longitude != null

    companion object {
        /** Firmware reports battery_level 101 when running from external power. */
        const val BATTERY_POWERED = 101
    }
}
