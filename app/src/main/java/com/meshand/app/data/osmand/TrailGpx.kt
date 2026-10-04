package com.meshand.app.data.osmand

import com.meshand.app.domain.model.TrailPoint
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the GPX file OsmAnd draws as a node's trail. Colour and line width are written into the
 * file as OsmAnd's own track-appearance extensions, because OsmAnd ignores the API's colour
 * argument the first time a new track file is imported. A waypoint marks where the trail starts.
 */
object TrailGpx {
    /** OsmAnd's track widths are thin, medium, bold or custom; medium is the second level. */
    const val DEFAULT_WIDTH = "medium"

    fun build(
        name: String,
        description: String,
        color: Int,
        points: List<TrailPoint>,
        startLabel: String? = null,
        width: String = DEFAULT_WIDTH,
    ): String = buildString {
        val hex = colorHex(color)
        val appearance = "<extensions><osmand:color>$hex</osmand:color><osmand:width>${escape(width)}</osmand:width></extensions>"
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="MeshAnd" xmlns="http://www.topografix.com/GPX/1/1" xmlns:osmand="https://osmand.net">""").append('\n')
        append("  <metadata><name>").append(escape(name)).append("</name><desc>").append(escape(description)).append("</desc></metadata>\n")
        val start = points.firstOrNull()
        if (start != null && startLabel != null) {
            append(String.format(Locale.US, "  <wpt lat=\"%.7f\" lon=\"%.7f\">", start.latitude, start.longitude))
            append("<time>").append(DateTimeFormatter.ISO_INSTANT.format(start.time)).append("</time>")
            append("<name>").append(escape(startLabel)).append("</name>")
            append("<extensions><osmand:color>").append(hex).append("</osmand:color></extensions>")
            append("</wpt>\n")
        }
        append("  ").append(appearance).append('\n')
        append("  <trk>\n")
        append("    <name>").append(escape(name)).append("</name>\n")
        append("    <desc>").append(escape(description)).append("</desc>\n")
        append("    ").append(appearance).append('\n')
        append("    <trkseg>\n")
        for (p in points) {
            append(String.format(Locale.US, "      <trkpt lat=\"%.7f\" lon=\"%.7f\">", p.latitude, p.longitude))
            p.altitude?.let { append("<ele>").append(it).append("</ele>") }
            append("<time>").append(DateTimeFormatter.ISO_INSTANT.format(p.time)).append("</time>")
            append("</trkpt>\n")
        }
        append("    </trkseg>\n")
        append("  </trk>\n")
        append("</gpx>\n")
    }

    /** "#RRGGBB" for an ARGB colour int. */
    fun colorHex(color: Int): String = String.format(Locale.US, "#%06X", color and 0xFFFFFF)

    /**
     * OsmAnd titles a track by its file name, so the file name says whose trail it is:
     * "MeshAnd trail - Giorgi". Characters that aren't safe in file names are replaced.
     */
    fun fileName(prefix: String, personName: String): String {
        val safe = personName.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim().take(40).ifEmpty { "node" }
        return "$prefix$safe.gpx"
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
