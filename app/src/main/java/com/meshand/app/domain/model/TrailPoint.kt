package com.meshand.app.domain.model

import java.time.Instant

/** One recorded position of a node, used to draw its recent trail. */
data class TrailPoint(
    val latitude: Double,
    val longitude: Double,
    /** Metres above mean sea level, if the radio reported it. */
    val altitude: Int?,
    val time: Instant,
)
