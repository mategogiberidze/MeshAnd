package com.meshand.app.domain

import java.util.Locale

/**
 * The pin text message, and finding coordinates in text other apps share (OsmAnd's
 * Share sends a title, a `geo:` link and an osmand.net link). Pure, unit-tested.
 *
 * A pin is an ordinary Meshtastic text message, so teammates without MeshAnd read it in the
 * Meshtastic app: `meshand: 41.75002,44.77124` plus an optional description. Only messages
 * starting with `meshand:` are pins.
 */
object PinText {
    data class Parsed(val latitude: Double, val longitude: Double, val name: String?)

    const val PREFIX = "meshand:"

    /**
     * Description limit in UTF-8 bytes, not letters: Georgian letters take 3 bytes each, and airtime
     * grows with bytes. 48 bytes keeps a pin under about 1 s on LongFast (no name: 0.6 s).
     */
    const val MAX_NAME_BYTES = 48

    private const val NUM = """(-?\d{1,3}(?:\.\d+)?)"""
    private val MESSAGE = Regex("""^\s*meshand:\s*$NUM\s*[,\s]\s*$NUM(?:\s+(.*))?$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val GEO = Regex("""geo:$NUM,$NUM""", RegexOption.IGNORE_CASE)
    private val GEO_QUERY = Regex("""[?&]q=$NUM,$NUM""")
    private val PIN_PARAM = Regex("""[?&]pin=$NUM,$NUM""")
    private val LAT_PARAM = Regex("""[?&]lat=$NUM""")
    private val LON_PARAM = Regex("""[?&](?:lon|lng)=$NUM""")
    private val MAP_HASH = Regex("""#\d{1,2}/$NUM/$NUM""")
    /** A bare "41.75002, 44.77124"; needs 3+ decimals so ordinary numbers don't match. */
    private val PLAIN_PAIR = Regex("""(-?\d{1,2}\.\d{3,})\s*[,;\s]\s*(-?\d{1,3}\.\d{3,})""")

    /** The message sent over the mesh. Coordinates have 5 decimals (about 1 m). */
    fun format(latitude: Double, longitude: Double, name: String?): String {
        val clean = cleanName(name)
        return String.format(Locale.US, "%s %.5f,%.5f", PREFIX, latitude, longitude) + (clean?.let { " $it" } ?: "")
    }

    /** A pin message from the mesh, or null for any other text. */
    fun parseMessage(text: String): Parsed? {
        val m = MESSAGE.find(text) ?: return null
        return valid(m.groupValues[1], m.groupValues[2], cleanName(m.groupValues[3]))
    }

    /** Coordinates (and a name, if any) in text shared from another app, or null if none. */
    fun parseShared(text: String): Parsed? {
        parseMessage(text)?.let { return it }
        val name = sharedTitle(text)
        GEO.find(text)?.let { m ->
            val fromGeo = valid(m.groupValues[1], m.groupValues[2], name)
            if (fromGeo != null) return fromGeo
            // geo:0,0?q=lat,lon means "search for this place".
            GEO_QUERY.find(text)?.let { q -> valid(q.groupValues[1], q.groupValues[2], name)?.let { return it } }
        }
        PIN_PARAM.find(text)?.let { m -> valid(m.groupValues[1], m.groupValues[2], name)?.let { return it } }
        val lat = LAT_PARAM.find(text)
        val lon = LON_PARAM.find(text)
        if (lat != null && lon != null) valid(lat.groupValues[1], lon.groupValues[1], name)?.let { return it }
        MAP_HASH.find(text)?.let { m -> valid(m.groupValues[1], m.groupValues[2], name)?.let { return it } }
        PLAIN_PAIR.find(text)?.let { m -> valid(m.groupValues[1], m.groupValues[2], name)?.let { return it } }
        return null
    }

    /** OsmAnd puts the place's title on the first line, before the location lines. */
    private fun sharedTitle(text: String): String? {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val looksLikeLocation = first.contains("geo:", ignoreCase = true) ||
            first.contains("http", ignoreCase = true) || PLAIN_PAIR.containsMatchIn(first)
        return if (looksLikeLocation) null else cleanName(first)
    }

    private fun cleanName(name: String?): String? =
        name?.replace(Regex("""\s+"""), " ")?.trim()?.let { truncateToBytes(it, MAX_NAME_BYTES) }?.trim()?.takeIf { it.isNotEmpty() }

    /** UTF-8 size of [text]. */
    fun utf8Bytes(text: String): Int = text.encodeToByteArray().size

    /** Cuts [text] to at most [maxBytes] UTF-8 bytes without splitting a character. */
    fun truncateToBytes(text: String, maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val cp = text.codePointAt(end)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(cp)
        }
        return text.substring(0, end)
    }

    private fun valid(lat: String, lon: String, name: String?): Parsed? {
        val la = lat.toDoubleOrNull() ?: return null
        val lo = lon.toDoubleOrNull() ?: return null
        if (la !in -90.0..90.0 || lo !in -180.0..180.0 || (la == 0.0 && lo == 0.0)) return null
        return Parsed(la, lo, name)
    }
}
