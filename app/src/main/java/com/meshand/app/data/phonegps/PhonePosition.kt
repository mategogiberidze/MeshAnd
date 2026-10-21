package com.meshand.app.data.phonegps

import org.meshtastic.proto.Position
import kotlin.math.roundToInt

/** Phone fix → the `Position` the radio takes as its own. Pure, unit-tested. */
object PhonePosition {
    /** Fixes less accurate than this would move the radio's point around too much. */
    const val MAX_ACCURACY_M = 100f

    /** Older fixes are leftovers from before the GPS lost the sky. */
    const val MAX_AGE_MS = 60_000L

    /** The firmware drops phone positions sent less than 10 s apart; stay well above that. */
    const val MIN_INTERVAL_MS = 15_000L

    /** How often the phone is asked for a fix (Meshtastic's own app uses the same). */
    const val REQUEST_INTERVAL_MS = 30_000L

    /**
     * [altitudeHae] is Android's altitude (above the WGS84 ellipsoid); [altitudeMsl] is above sea
     * level, known on Android 14+. Meshtastic's `altitude` is MSL, so HAE stands in when MSL is
     * unknown (the difference is tens of metres at most).
     */
    fun build(
        latitude: Double,
        longitude: Double,
        altitudeHae: Double?,
        altitudeMsl: Double?,
        timeMillis: Long,
    ): Position = Position(
        latitude_i = (latitude * 1e7).roundToInt(),
        longitude_i = (longitude * 1e7).roundToInt(),
        altitude = (altitudeMsl ?: altitudeHae)?.roundToInt(),
        altitude_hae = altitudeHae?.roundToInt(),
        time = (timeMillis / 1000).toInt(),
        location_source = Position.LocSource.LOC_EXTERNAL,
    )

    /** Whether a fix is worth sending now. [lastSentAtMs] is null before the first send. */
    fun isUsable(accuracyM: Float?, fixAgeMs: Long, lastSentAtMs: Long?, nowMs: Long): Boolean =
        accuracyM != null && accuracyM <= MAX_ACCURACY_M &&
            fixAgeMs in 0..MAX_AGE_MS &&
            (lastSentAtMs == null || nowMs - lastSentAtMs >= MIN_INTERVAL_MS)
}
