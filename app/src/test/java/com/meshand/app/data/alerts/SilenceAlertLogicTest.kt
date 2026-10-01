package com.meshand.app.data.alerts

import com.meshand.app.domain.model.MeshNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SilenceAlertLogicTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val threshold = Duration.ofMinutes(60)

    private fun node(id: Long, minutesAgo: Long?, own: Boolean = false) = MeshNode(
        id = id, shortName = "N$id", longName = null, latitude = null, longitude = null, altitude = null,
        batteryLevel = null, snr = null, hopsAway = null,
        lastSeen = minutesAgo?.let { now.minus(Duration.ofMinutes(it)) }, isOwnNode = own,
    )

    @Test
    fun `watched node silent past threshold alerts once`() {
        val nodes = listOf(node(1, 61), node(2, 5))
        val first = SilenceAlertLogic.evaluate(nodes, setOf(1L, 2L), threshold, now, emptySet())
        assertEquals(listOf(1L), first.wentSilent.map { it.id })
        assertEquals(setOf(1L), first.alerted)

        val second = SilenceAlertLogic.evaluate(nodes, setOf(1L, 2L), threshold, now, first.alerted)
        assertTrue(second.wentSilent.isEmpty())
        assertEquals(setOf(1L), second.alerted)
    }

    @Test
    fun `unwatched nodes never alert`() {
        val decisions = SilenceAlertLogic.evaluate(listOf(node(1, 600)), emptySet(), threshold, now, emptySet())
        assertTrue(decisions.wentSilent.isEmpty())
    }

    @Test
    fun `heard again produces back event`() {
        val decisions = SilenceAlertLogic.evaluate(listOf(node(1, 2)), setOf(1L), threshold, now, setOf(1L))
        assertEquals(listOf(1L), decisions.cameBack.map { it.id })
        assertTrue(decisions.alerted.isEmpty())
    }

    @Test
    fun `exactly at threshold is not silent, never-heard is not silent`() {
        val decisions = SilenceAlertLogic.evaluate(listOf(node(1, 60), node(2, null)), setOf(1L, 2L), threshold, now, emptySet())
        assertTrue(decisions.wentSilent.isEmpty())
    }

    @Test
    fun `own radio never alerts and unwatching clears alert state`() {
        val own = SilenceAlertLogic.evaluate(listOf(node(1, 600, own = true)), setOf(1L), threshold, now, emptySet())
        assertTrue(own.wentSilent.isEmpty())
        val unwatched = SilenceAlertLogic.evaluate(listOf(node(2, 600)), emptySet(), threshold, now, setOf(2L))
        assertTrue(unwatched.alerted.isEmpty())
        assertTrue(unwatched.cameBack.isEmpty())
    }
}
