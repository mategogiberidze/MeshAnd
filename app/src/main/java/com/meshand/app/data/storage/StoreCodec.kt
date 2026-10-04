package com.meshand.app.data.storage

import com.meshand.app.domain.model.Pin
import com.meshand.app.domain.model.TrailPoint
import java.time.Instant

/**
 * Plain-text (tab-separated) file formats for trails and pins. Pure, unit-tested.
 * Malformed lines are skipped, so a damaged file loses at most those lines.
 */
object StoreCodec {
    private const val TRAILS_HEADER = "# MeshAnd trails v1"
    private const val PINS_HEADER = "# MeshAnd pins v1"

    fun encodeTrails(trails: Map<Long, List<TrailPoint>>): String = buildString {
        appendLine(TRAILS_HEADER)
        for ((nodeId, points) in trails) {
            for (p in points) {
                append(nodeId).append('\t').append(p.time.toEpochMilli()).append('\t')
                append(p.latitude).append('\t').append(p.longitude).append('\t')
                appendLine(p.altitude?.toString().orEmpty())
            }
        }
    }

    fun decodeTrails(text: String): Map<Long, List<TrailPoint>> {
        val trails = LinkedHashMap<Long, MutableList<TrailPoint>>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split('\t')
            if (f.size < 5) continue
            val nodeId = f[0].toLongOrNull() ?: continue
            val time = f[1].toLongOrNull()?.let(Instant::ofEpochMilli) ?: continue
            val lat = f[2].toDoubleOrNull() ?: continue
            val lon = f[3].toDoubleOrNull() ?: continue
            trails.getOrPut(nodeId) { ArrayList() }.add(TrailPoint(lat, lon, f[4].toIntOrNull(), time))
        }
        return trails.mapValues { (_, points) -> points.sortedBy { it.time } }
    }

    fun encodePins(pins: List<Pin>): String = buildString {
        appendLine(PINS_HEADER)
        for (pin in pins) {
            append(escape(pin.id)).append('\t')
            append(pin.latitude).append('\t').append(pin.longitude).append('\t')
            append(pin.fromNodeId?.toString().orEmpty()).append('\t')
            append(pin.time.toEpochMilli()).append('\t')
            append(if (pin.mine) "1" else "0").append('\t')
            append(pin.delivery?.name.orEmpty()).append('\t')
            appendLine(pin.name?.let(::escape).orEmpty())
        }
    }

    fun decodePins(text: String): List<Pin> = text.lineSequence().mapNotNull { line ->
        if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
        val f = line.split('\t')
        if (f.size < 8) return@mapNotNull null
        Pin(
            id = unescape(f[0]).ifEmpty { return@mapNotNull null },
            latitude = f[1].toDoubleOrNull() ?: return@mapNotNull null,
            longitude = f[2].toDoubleOrNull() ?: return@mapNotNull null,
            fromNodeId = f[3].toLongOrNull(),
            time = f[4].toLongOrNull()?.let(Instant::ofEpochMilli) ?: return@mapNotNull null,
            mine = f[5] == "1",
            delivery = Pin.Delivery.entries.firstOrNull { it.name == f[6] },
            name = unescape(f[7]).ifEmpty { null },
        )
    }.toList()

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                out.append(
                    when (s[i + 1]) {
                        't' -> '\t'
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> s[i + 1]
                    },
                )
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
