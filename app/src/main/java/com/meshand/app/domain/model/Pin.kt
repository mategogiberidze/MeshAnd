package com.meshand.app.domain.model

import java.time.Instant

/** A place shared over the mesh as a `meshand: lat,lon` text message. */
data class Pin(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val name: String?,
    /** Sender's node number, if known. */
    val fromNodeId: Long?,
    val time: Instant,
    /** True for pins this phone sent. */
    val mine: Boolean,
    /** Progress of a pin this phone sent; null for received pins. */
    val delivery: Delivery? = null,
) {
    enum class Delivery { SENDING, SENT, RELAYED, FAILED }
}
