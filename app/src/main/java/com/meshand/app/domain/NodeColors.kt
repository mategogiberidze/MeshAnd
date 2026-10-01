package com.meshand.app.domain

import com.meshand.app.domain.model.MeshNode

/**
 * A random-looking but stable colour per person, derived from the node number, so the same
 * teammate has the same colour on the OsmAnd map, in the team list and in MeshAnd, across restarts
 * and phones. Our own radio is always blue; blue is kept out of the palette so it stays unique.
 */
object NodeColors {
    const val OWN: Int = 0xFF1E88E5.toInt() // blue

    /** Distinct, map-readable colours (no blues). */
    val PALETTE: IntArray = intArrayOf(
        0xFFE53935.toInt(), // red
        0xFFFB8C00.toInt(), // orange
        0xFFF9A825.toInt(), // dark yellow
        0xFF43A047.toInt(), // green
        0xFF00897B.toInt(), // teal
        0xFF8E24AA.toInt(), // purple
        0xFFD81B60.toInt(), // pink
        0xFF6D4C41.toInt(), // brown
        0xFFC0CA33.toInt(), // lime
        0xFF546E7A.toInt(), // blue grey
    )

    fun colorFor(node: MeshNode): Int = if (node.isOwnNode) OWN else colorForId(node.id)

    fun colorForId(nodeId: Long): Int {
        // Mix the bits so consecutive or similar node numbers still get different colours.
        var x = nodeId and 0xFFFF_FFFFL
        x = ((x ushr 16) xor x) * 0x45D9F3BL and 0xFFFF_FFFFL
        x = ((x ushr 16) xor x) * 0x45D9F3BL and 0xFFFF_FFFFL
        x = (x ushr 16) xor x
        return PALETTE[(x % PALETTE.size).toInt()]
    }
}
