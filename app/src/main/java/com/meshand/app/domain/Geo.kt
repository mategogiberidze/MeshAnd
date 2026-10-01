package com.meshand.app.domain

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Small spherical-earth helpers for "how far and which way is my teammate". */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres (haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from north in [0, 360). */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** 8-point compass direction for a bearing, e.g. 44° → "NE". */
    fun compassPoint(bearing: Double): String = COMPASS[((bearing / 45.0).roundToInt()) % 8]

    /** "850 m", "1.2 km", "23 km". */
    fun formatDistance(meters: Double): String = when {
        meters < 1_000 -> "${meters.roundToInt()} m"
        meters < 10_000 -> String.format(Locale.US, "%.1f km", meters / 1_000)
        else -> "${(meters / 1_000).roundToInt()} km"
    }
}
