package com.meshand.app.data.meshtastic

import com.meshand.app.domain.model.RadioInfo
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.ConfigBundle
import java.time.Instant
import java.util.Locale

/** Own node + config bundle + latest device telemetry → [RadioInfo]. Pure, unit-tested. */
object RadioInfoMapper {
    /**
     * [metrics] is the latest device telemetry (fresher than the NodeDB copy in [own]).
     * Only reads identity, firmware, role and LoRa region/preset from [bundle]; never keys.
     */
    fun toRadioInfo(own: NodeInfo?, bundle: ConfigBundle?, metrics: DeviceMetrics?, metricsAt: Instant?): RadioInfo {
        val user = own?.user
        val metadata = bundle?.metadata
        val deviceConfig = bundle?.configs?.firstNotNullOfOrNull { it.device }
        val lora = bundle?.configs?.firstNotNullOfOrNull { it.lora }
        val m = metrics ?: own?.device_metrics
        val battery = m?.battery_level?.takeIf { it >= 0 }
        val hardware = metadata?.hw_model?.name?.takeUnless { it == "UNSET" }
            ?: user?.hw_model?.name?.takeUnless { it == "UNSET" }
        return RadioInfo(
            longName = user?.long_name?.takeIf { it.isNotBlank() },
            shortName = user?.short_name?.takeIf { it.isNotBlank() },
            nodeIdHex = own?.let { "!%08x".format(MeshtasticMapper.nodeNumToLong(it.num)) },
            hardware = hardware?.let(::label),
            firmware = metadata?.firmware_version?.takeIf { it.isNotBlank() },
            role = (deviceConfig?.role ?: metadata?.role)?.name?.let(::label),
            region = lora?.region?.name?.takeUnless { it == "UNSET" }?.replace('_', ' '),
            modemPreset = lora?.takeIf { it.use_preset }?.modem_preset?.name?.let(::label),
            batteryPercent = battery?.coerceAtMost(100),
            pluggedIn = battery != null && battery > 100,
            voltage = m?.voltage?.takeIf { it > 0f },
            channelUtilization = m?.channel_utilization,
            airUtilTx = m?.air_util_tx,
            uptimeSeconds = m?.uptime_seconds?.toLong()?.and(0xFFFF_FFFFL),
            metricsAt = metricsAt.takeIf { metrics != null },
        )
    }

    /** `T_BEAM_S3_CORE` → "T Beam S3 Core", `CLIENT_MUTE` → "Client Mute". */
    fun label(enumName: String): String =
        enumName.split('_').filter { it.isNotEmpty() }.joinToString(" ") { word ->
            if (word.any(Char::isDigit)) word else word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }

    /** "3 d 4 h", "5 h 12 min", "8 min". */
    fun formatUptime(seconds: Long): String = when {
        seconds < 3600 -> "${seconds / 60} min"
        seconds < 86_400 -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
        else -> "${seconds / 86_400} d ${seconds % 86_400 / 3600} h"
    }
}
