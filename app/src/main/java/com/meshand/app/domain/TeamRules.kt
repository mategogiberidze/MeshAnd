package com.meshand.app.domain

import com.meshand.app.domain.model.MeshNode
import java.time.Duration
import java.time.Instant

/** Who counts as part of the team right now. */
object TeamRules {
    /** Nodes not heard for longer than this are hidden (map, team list, node list). */
    val ACTIVE_WINDOW: Duration = Duration.ofHours(24)

    /** Our own radio is always shown; others only if heard within [ACTIVE_WINDOW]. */
    fun isActive(node: MeshNode, now: Instant): Boolean =
        node.isOwnNode || node.lastSeen?.let { Duration.between(it, now) <= ACTIVE_WINDOW } == true

    fun activeNodes(nodes: List<MeshNode>, now: Instant): List<MeshNode> = nodes.filter { isActive(it, now) }
}
