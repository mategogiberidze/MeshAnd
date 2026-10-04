package com.meshand.app.domain

import com.meshand.app.domain.model.MeshNode
import com.meshand.app.domain.model.TrailPoint
import java.time.Duration
import java.time.Instant

/**
 * Remembers where each node has been, so its recent path can be drawn. `TrailRecorder`
 * saves it to disk and [restore]s it when the app starts.
 *
 * A point is added whenever a node's reported position changes. Its time is the node's
 * last-heard time, which for live packets is when the phone received it.
 */
class TrailBook(private val maxPointsPerNode: Int = 2_000) {
    private val trails = HashMap<Long, ArrayDeque<TrailPoint>>()

    /** Records the current position of every node that has moved (or is new). Returns true if anything changed. */
    fun record(nodes: List<MeshNode>, now: Instant): Boolean {
        var changed = false
        for (node in nodes) {
            val lat = node.latitude ?: continue
            val lon = node.longitude ?: continue
            val time = node.lastSeen ?: now
            val trail = trails.getOrPut(node.id) { ArrayDeque() }
            val last = trail.lastOrNull()
            if (last != null && last.latitude == lat && last.longitude == lon) continue
            if (last != null && time < last.time) continue // older than what we already have
            trail.addLast(TrailPoint(lat, lon, node.altitude, time))
            if (trail.size > maxPointsPerNode) trail.removeFirst()
            changed = true
        }
        return changed
    }

    /** Drops points older than [window]. Returns true if anything was removed. */
    fun prune(window: Duration, now: Instant): Boolean {
        val cutoff = now.minus(window)
        var changed = false
        val iterator = trails.entries.iterator()
        while (iterator.hasNext()) {
            val trail = iterator.next().value
            while (trail.isNotEmpty() && trail.first().time < cutoff) {
                trail.removeFirst()
                changed = true
            }
            if (trail.isEmpty()) iterator.remove()
        }
        return changed
    }

    /** An immutable copy of all trails, oldest point first. */
    fun snapshot(): Map<Long, List<TrailPoint>> = trails.mapValues { it.value.toList() }

    /** Forgets one node's trail; it restarts from that node's next reported position. */
    fun reset(nodeId: Long) {
        trails.remove(nodeId)
    }

    fun clear() = trails.clear()

    /** Replaces everything with [saved] (oldest point first per node), e.g. loaded from disk. */
    fun restore(saved: Map<Long, List<TrailPoint>>) {
        trails.clear()
        for ((id, points) in saved) {
            if (points.isNotEmpty()) trails[id] = ArrayDeque(points.takeLast(maxPointsPerNode))
        }
    }
}
