package com.meshand.app.domain

import com.meshand.app.domain.model.MeshNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class TeamRulesAndColorsTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")

    private fun node(id: Long, hoursAgo: Long?, own: Boolean = false) = MeshNode(
        id = id, shortName = null, longName = null, latitude = null, longitude = null, altitude = null,
        batteryLevel = null, snr = null, hopsAway = null,
        lastSeen = hoursAgo?.let { now.minus(Duration.ofHours(it)) }, isOwnNode = own,
    )

    @Test
    fun `only nodes heard within 24 h are active, own radio always`() {
        assertTrue(TeamRules.isActive(node(1, 23), now))
        assertTrue(TeamRules.isActive(node(2, 24), now))
        assertFalse(TeamRules.isActive(node(3, 25), now))
        assertFalse(TeamRules.isActive(node(4, null), now))
        assertTrue(TeamRules.isActive(node(5, null, own = true), now))
        assertEquals(listOf(1L, 5L), TeamRules.activeNodes(listOf(node(1, 1), node(3, 30), node(5, 99, own = true)), now).map { it.id })
    }

    @Test
    fun `colours are stable, from the palette, and own radio is blue`() {
        val id = 0x12345678L
        assertEquals(NodeColors.colorForId(id), NodeColors.colorForId(id))
        assertTrue(NodeColors.colorForId(id) in NodeColors.PALETTE)
        assertFalse(NodeColors.OWN in NodeColors.PALETTE)
        assertEquals(NodeColors.OWN, NodeColors.colorFor(node(id, 1, own = true)))
    }

    @Test
    fun `nearby node numbers spread over several colours`() {
        val colours = (0x0A0B0C00L..0x0A0B0C1FL).map { NodeColors.colorForId(it) }.toSet()
        assertTrue("only ${colours.size} colours", colours.size >= 6)
    }
}
