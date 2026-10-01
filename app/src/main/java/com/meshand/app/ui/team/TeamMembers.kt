package com.meshand.app.ui.team

import com.meshand.app.domain.Geo
import com.meshand.app.domain.model.MeshNode

/** One row of the team list: a teammate plus where they are relative to our own radio. */
data class TeamMember(
    val node: MeshNode,
    /** Metres from our radio's GPS position, if both positions are known. */
    val distanceMeters: Double?,
    /** e.g. "1.2 km NE"; null when it can't be computed. */
    val distanceText: String?,
)

/**
 * Team = every node except our own radio. Sorted nearest first; members without a usable
 * distance follow, most recently heard first.
 */
fun teamMembers(nodes: List<MeshNode>): List<TeamMember> {
    val me = nodes.firstOrNull { it.isOwnNode }?.takeIf { it.hasPosition }
    return nodes.filterNot { it.isOwnNode }
        .map { node ->
            if (me == null || !node.hasPosition) {
                TeamMember(node, null, null)
            } else {
                val d = Geo.distanceMeters(me.latitude!!, me.longitude!!, node.latitude!!, node.longitude!!)
                val b = Geo.bearingDegrees(me.latitude, me.longitude, node.latitude, node.longitude)
                TeamMember(node, d, "${Geo.formatDistance(d)} ${Geo.compassPoint(b)}")
            }
        }
        .sortedWith(
            compareBy<TeamMember> { it.distanceMeters == null }
                .thenBy { it.distanceMeters }
                .thenByDescending { it.node.lastSeen },
        )
}
