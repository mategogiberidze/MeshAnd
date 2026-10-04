package com.meshand.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun `parses tags and versions`() {
        assertEquals(listOf(0, 2, 0), AppVersion.parse("v0.2.0"))
        assertEquals(listOf(1, 10), AppVersion.parse("1.10-beta"))
        assertNull(AppVersion.parse("latest"))
    }

    @Test
    fun `compares numerically`() {
        assertTrue(AppVersion.isNewer("0.2.0", "0.1.0"))
        assertTrue(AppVersion.isNewer("v0.10.0", "0.9.1"))
        assertTrue(AppVersion.isNewer("1.0", "0.9.9"))
        assertFalse(AppVersion.isNewer("0.1.0", "0.1.0"))
        assertFalse(AppVersion.isNewer("0.1", "0.1.0"))
        assertFalse(AppVersion.isNewer("0.1.0", "0.2.0"))
        assertFalse(AppVersion.isNewer("garbage", "0.1.0"))
    }
}
