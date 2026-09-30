package com.meshand.app.domain.model

/** A BLE device advertising the Meshtastic service UUID. */
data class DiscoveredRadio(
    val name: String?,
    val address: String,
    val rssi: Int,
    val bonded: Boolean,
)
