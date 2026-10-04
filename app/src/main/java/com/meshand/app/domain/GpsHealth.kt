package com.meshand.app.domain

import com.meshand.app.domain.model.MeshNode
import java.time.Duration
import java.time.Instant

/** How old a node's position is, from what its radio reports. Pure, unit-tested. */
object PositionAge {
    /** Reports keep arriving but the position is older than this: the GPS has probably lost its fix. */
    val STUCK_AFTER: Duration = Duration.ofMinutes(15)

    /** Radio clocks without GPS time can report nonsense; ignore times before this. */
    private val PLAUSIBLE_AFTER: Instant = Instant.parse("2020-01-01T00:00:00Z")

    /**
     * Best estimate of when the GPS took the current position: the fix time if the radio sends
     * it, otherwise when the coordinates last changed. Null if neither is known.
     */
    fun updatedAt(node: MeshNode, now: Instant): Instant? =
        plausible(node.fixTime, now) ?: plausible(node.positionChangedAt, now)

    /** When the node last reported a position (possibly an old one, re-sent). */
    fun reportedAt(node: MeshNode, now: Instant): Instant? = plausible(node.positionReportedAt, now)

    /** True when the radio keeps re-sending a position that is much older than its reports. */
    fun isStuck(node: MeshNode, now: Instant): Boolean {
        val updated = updatedAt(node, now) ?: return false
        val reported = reportedAt(node, now) ?: return false
        return Duration.between(updated, reported) > STUCK_AFTER
    }

    private fun plausible(time: Instant?, now: Instant): Instant? =
        time?.takeIf { it.isAfter(PLAUSIBLE_AFTER) && !it.isAfter(now.plusSeconds(300)) }
}

/**
 * Is our own radio's GPS producing fresh positions? MeshAnd only learns the radio's position when
 * the radio reports it (each position broadcast, roughly every 1–10 minutes with the suggested
 * settings), so "fresh" allows for that interval.
 */
object GpsHealth {
    enum class Level { GOOD, STALE, UNKNOWN, NONE }

    data class Status(
        val level: Level,
        /** When the GPS took the last position, if known. */
        val fixTime: Instant?,
        val satellites: Int?,
        /** True when the time is the radio's real fix time, not when the coordinates changed. */
        val exact: Boolean = false,
    )

    /** A fix older than this is "stale": the radio broadcasts at least every 10 min when set up as suggested. */
    val FRESH_FOR: Duration = Duration.ofMinutes(15)

    fun evaluate(ownNode: MeshNode?, now: Instant): Status {
        if (ownNode == null || !ownNode.hasPosition) return Status(Level.NONE, null, ownNode?.satellites)
        val updated = PositionAge.updatedAt(ownNode, now)
            ?: return Status(Level.UNKNOWN, null, ownNode.satellites)
        val level = if (Duration.between(updated, now) <= FRESH_FOR) Level.GOOD else Level.STALE
        return Status(level, updated, ownNode.satellites, exact = updated == ownNode.fixTime)
    }
}
