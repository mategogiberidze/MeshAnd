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
) {
    /** Canonical Meshtastic user id, e.g. `!a1b2c3d4`. */
    val nodeIdHex: String get() = "!%08x".format(id)

    val hasPosition: Boolean get() = latitude != null && longitude != null

    companion object {
        /** Firmware reports battery_level 101 when running from external power. */
        const val BATTERY_POWERED = 101
    }
}
