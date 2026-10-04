package com.meshand.app.ui.team

import com.meshand.app.domain.Geo
import com.meshand.app.domain.model.MeshNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class TeamMembersTest {
    private fun node(id: Long, lat: Double?, lon: Double?, own: Boolean = false, seen: Long = 0) = MeshNode(
        id = id, shortName = "N$id", longName = null, latitude = lat, longitude = lon, altitude = null,
        batteryLevel = null, snr = null, hopsAway = null, lastSeen = Instant.ofEpochSecond(seen), isOwnNode = own,
    )

    @Test
    fun `excludes own radio and sorts nearest first`() {
        val me = node(1, 41.7000, 44.8000, own = true)
        val far = node(2, 41.8000, 44.8000) // ~11 km N
        val near = node(3, 41.7000, 44.8100) // ~830 m E
        val members = teamMembers(listOf(far, me, near))
        assertEquals(listOf(3L, 2L), members.map { it.node.id })
        assertEquals("830 m", Geo.formatDistance(members[0].distanceMeters!!))
        assertEquals("11 km", Geo.formatDistance(members[1].distanceMeters!!))
    }

    @Test
    fun `members without position go last, most recently heard first`() {
        val me = node(1, 41.7, 44.8, own = true)
        val withPos = node(2, 41.71, 44.8)
        val oldNoPos = node(3, null, null, seen = 100)
        val newNoPos = node(4, null, null, seen = 200)
        val members = teamMembers(listOf(oldNoPos, newNoPos, withPos, me))
        assertEquals(listOf(2L, 4L, 3L), members.map { it.node.id })
        assertNull(members[1].distanceMeters)
    }

    @Test
    fun `no distances when own radio has no fix`() {
        val me = node(1, null, null, own = true)
        val other = node(2, 41.7, 44.8)
        val members = teamMembers(listOf(me, other))
        assertEquals(1, members.size)
        assertNull(members[0].distanceMeters)
    }
}
