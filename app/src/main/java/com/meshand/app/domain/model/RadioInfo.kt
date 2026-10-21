package com.meshand.app.domain.model

import java.time.Instant

/** The connected radio itself: identity, firmware, battery and airtime. All fields optional. */
data class RadioInfo(
    val longName: String? = null,
    val shortName: String? = null,
    /** e.g. `!a1b2c3d4`. */
    val nodeIdHex: String? = null,
    /** Readable hardware model, e.g. "T Beam S3 Core". */
    val hardware: String? = null,
    val firmware: String? = null,
    /** Readable device role, e.g. "Client". */
    val role: String? = null,
    /** e.g. "EU 868". */
    val region: String? = null,
    /** e.g. "Long Fast"; null when the radio uses custom LoRa settings. */
    val modemPreset: String? = null,
    /** 0–100. */
    val batteryPercent: Int? = null,
    /** Running from USB/external power. */
    val pluggedIn: Boolean = false,
    val voltage: Float? = null,
    /** How busy the channel is (everyone's traffic), in percent. */
    val channelUtilization: Float? = null,
    /** This radio's own share of transmit airtime in the last hour, in percent. */
    val airUtilTx: Float? = null,
    val uptimeSeconds: Long? = null,
    /** When the battery/airtime numbers were last received from the radio. */
    val metricsAt: Instant? = null,
)
