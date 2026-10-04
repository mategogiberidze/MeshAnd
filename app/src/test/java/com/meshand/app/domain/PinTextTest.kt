package com.meshand.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinTextTest {
    @Test
    fun `formats a short readable message`() {
        assertEquals("meshand: 41.75002,44.77124 Camp", PinText.format(41.750021, 44.771239, "  Camp \n"))
        assertEquals("meshand: -33.86880,-70.00000", PinText.format(-33.8688, -70.0, null))
        assertEquals(PinText.MAX_NAME_BYTES, PinText.format(1.0, 2.0, "x".repeat(100)).substringAfter("2.00000 ").length)
        // Georgian letters are 3 bytes each: 48 bytes = 16 letters.
        assertEquals(16, PinText.format(1.0, 2.0, "ა".repeat(40)).substringAfter("2.00000 ").length)
    }

    @Test
    fun `truncation never splits a character`() {
        assertEquals("ab", PinText.truncateToBytes("abც", 4))
        assertEquals("abც", PinText.truncateToBytes("abც", 5))
        assertEquals("", PinText.truncateToBytes("😀", 3))
    }

    @Test
    fun `parses pin messages and round-trips`() {
        val parsed = PinText.parseMessage(PinText.format(41.75002, 44.77124, "ვაშლიჯვარი"))!!
        assertEquals(41.75002, parsed.latitude, 0.0)
        assertEquals(44.77124, parsed.longitude, 0.0)
        assertEquals("ვაშლიჯვარი", parsed.name)
        // Hand-typed with a space instead of a comma.
        val typed = PinText.parseMessage("meshand: 41.75002 44.77124")!!
        assertEquals(44.77124, typed.longitude, 0.0)
        assertNull(typed.name)
    }

    @Test
    fun `ordinary chat is not a pin`() {
        assertNull(PinText.parseMessage("hello, meet at 41.75002,44.77124"))
        assertNull(PinText.parseMessage("MeshAnd is great"))
        assertNull(PinText.parseMessage("meshand: 95.0,44.0"))
        assertNull(PinText.parseMessage("meshand: 0,0"))
        // Only "meshand:" counts.
        assertNull(PinText.parseMessage("MeshAnd pin 41.750050,44.567216"))
        assertNull(PinText.parseMessage("meshand 41.75002,44.77124"))
    }

    @Test
    fun `finds coordinates and title in OsmAnd share text`() {
        val text = """
            Vashlijvari park
            Location: geo:41.75002,44.77124?z=16&q=41.75002,44.77124(Vashlijvari%20park)
            https://osmand.net/map?pin=41.75002,44.77124#16/41.75002/44.77124
        """.trimIndent()
        val parsed = PinText.parseShared(text)!!
        assertEquals(41.75002, parsed.latitude, 0.0)
        assertEquals(44.77124, parsed.longitude, 0.0)
        assertEquals("Vashlijvari park", parsed.name)
    }

    @Test
    fun `share text without a title has no name`() {
        val parsed = PinText.parseShared("Location: geo:41.75002,44.77124?z=16\nhttps://osmand.net/map?pin=41.75002,44.77124")!!
        assertNull(parsed.name)
    }

    @Test
    fun `other share formats`() {
        assertEquals(44.8, PinText.parseShared("geo:0,0?q=41.7,44.8(Somewhere)")!!.longitude, 0.0)
        assertEquals(41.7, PinText.parseShared("https://osmand.net/map?pin=41.7,44.8")!!.latitude, 0.0)
        assertEquals(44.8, PinText.parseShared("https://example.com/map?lat=41.7&lon=44.8")!!.longitude, 0.0)
        assertEquals(41.75002, PinText.parseShared("41.75002, 44.77124")!!.latitude, 0.0)
        assertNull(PinText.parseShared("Just some text with 12 numbers"))
    }
}
