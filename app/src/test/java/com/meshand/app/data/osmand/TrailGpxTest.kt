package com.meshand.app.data.osmand

import com.meshand.app.domain.model.TrailPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TrailGpxTest {
    private val points = listOf(
        TrailPoint(41.7151234, 44.8271, 850, Instant.parse("2026-10-03T10:00:00Z")),
        TrailPoint(41.72, 44.83, null, Instant.parse("2026-10-03T10:01:00Z")),
    )

    @Test
    fun `builds a GPX track with points, elevation, time, name, description and appearance`() {
        val gpx = TrailGpx.build(
            name = "Giorgi & co <1 h>",
            description = "Trail of Giorgi since 14:00",
            color = 0xFFE53935.toInt(),
            points = points,
            startLabel = "Giorgi start 14:00",
        )
        assertTrue(gpx.startsWith("<?xml"))
        assertTrue(gpx.contains("""<trkpt lat="41.7151234" lon="44.8271000"><ele>850</ele><time>2026-10-03T10:00:00Z</time></trkpt>"""))
        assertTrue(gpx.contains("""<trkpt lat="41.7200000" lon="44.8300000"><time>2026-10-03T10:01:00Z</time></trkpt>"""))
        assertTrue(gpx.contains("<name>Giorgi &amp; co &lt;1 h&gt;</name>"))
        assertTrue(gpx.contains("<desc>Trail of Giorgi since 14:00</desc>"))
        assertTrue(gpx.contains("<osmand:color>#E53935</osmand:color><osmand:width>medium</osmand:width>"))
        assertTrue(gpx.contains("""<wpt lat="41.7151234" lon="44.8271000"><time>2026-10-03T10:00:00Z</time><name>Giorgi start 14:00</name>"""))
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
    }

    @Test
    fun `no start waypoint without points or label`() {
        assertFalse(TrailGpx.build("n", "d", 0, emptyList(), startLabel = "x").contains("<wpt"))
        assertFalse(TrailGpx.build("n", "d", 0, points, startLabel = null).contains("<wpt"))
    }

    @Test
    fun `colour hex drops alpha`() {
        assertEquals("#1E88E5", TrailGpx.colorHex(0xFF1E88E5.toInt()))
        assertEquals("#00796B", TrailGpx.colorHex(0x8000796B.toInt()))
    }

    @Test
    fun `file name carries the person's name and stays file-safe`() {
        assertEquals("MeshAnd trail - Giorgi.gpx", TrailGpx.fileName("MeshAnd trail - ", "Giorgi"))
        assertEquals("MeshAnd trail - a_b_c.gpx", TrailGpx.fileName("MeshAnd trail - ", "a/b:c"))
        assertEquals("MeshAnd trail - node.gpx", TrailGpx.fileName("MeshAnd trail - ", "  "))
    }
}
