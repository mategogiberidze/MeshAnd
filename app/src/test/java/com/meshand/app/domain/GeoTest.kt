package com.meshand.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    @Test
    fun `one degree of latitude is about 111 km`() {
        assertEquals(111_195.0, Geo.distanceMeters(41.0, 44.0, 42.0, 44.0), 50.0)
    }

    @Test
    fun `Tbilisi to Kazbegi distance`() {
        // Tbilisi (41.7151, 44.8271) to Stepantsminda (42.6573, 44.6427): ~106 km
        assertEquals(106.0, Geo.distanceMeters(41.7151, 44.8271, 42.6573, 44.6427) / 1000, 1.5)
    }

    @Test
    fun `same point is zero metres`() {
        assertEquals(0.0, Geo.distanceMeters(41.7, 44.8, 41.7, 44.8), 1e-6)
    }

    @Test
    fun `distance formatting`() {
        assertEquals("850 m", Geo.formatDistance(849.6))
        assertEquals("1.2 km", Geo.formatDistance(1_234.0))
        assertEquals("23 km", Geo.formatDistance(23_400.0))
    }
}
