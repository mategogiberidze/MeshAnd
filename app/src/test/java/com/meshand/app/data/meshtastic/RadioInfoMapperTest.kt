package com.meshand.app.data.meshtastic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.User
import org.meshtastic.sdk.ConfigBundle
import java.time.Instant

// Protobufs 2.8 are builder-only (no public constructors), hence Type.build { field(value) }.
class RadioInfoMapperTest {
    private val now = Instant.parse("2026-10-22T10:00:00Z")

    private val own = NodeInfo.build {
        num(0xa1b2c3d4.toInt())
        user(User.build { long_name("Base"); short_name("BASE"); hw_model(HardwareModel.T_ECHO) })
        device_metrics(DeviceMetrics.build { battery_level(55); voltage(3.7f) })
    }

    private fun bundle(metadata: DeviceMetadata = DeviceMetadata.build { }) = ConfigBundle(
        myInfo = MyNodeInfo.build { my_node_num(0xa1b2c3d4.toInt()) },
        metadata = metadata,
        configs = listOf(
            Config.build { device(Config.DeviceConfig.build { role(Config.DeviceConfig.Role.CLIENT_MUTE) }) },
            Config.build {
                lora(
                    Config.LoRaConfig.build {
                        use_preset(true)
                        modem_preset(Config.LoRaConfig.ModemPreset.LONG_FAST)
                        region(Config.LoRaConfig.RegionCode.EU_868)
                    },
                )
            },
        ),
        moduleConfigs = emptyList(),
    )

    @Test
    fun `maps identity, config and fresh telemetry`() {
        val metadata = DeviceMetadata.build { firmware_version("2.7.15.567b8ea"); hw_model(HardwareModel.TBEAM) }
        val metrics = DeviceMetrics.build {
            battery_level(82); voltage(4.01f); channel_utilization(12.5f); air_util_tx(1.25f); uptime_seconds(7_300)
        }
        val info = RadioInfoMapper.toRadioInfo(own, bundle(metadata), metrics, now)
        assertEquals("Base", info.longName)
        assertEquals("BASE", info.shortName)
        assertEquals("!a1b2c3d4", info.nodeIdHex)
        assertEquals("Tbeam", info.hardware) // metadata wins over the user record
        assertEquals("2.7.15.567b8ea", info.firmware)
        assertEquals("Client Mute", info.role)
        assertEquals("EU 868", info.region)
        assertEquals("Long Fast", info.modemPreset)
        assertEquals(82, info.batteryPercent)
        assertFalse(info.pluggedIn)
        assertEquals(4.01f, info.voltage!!, 1e-6f)
        assertEquals(12.5f, info.channelUtilization!!, 1e-6f)
        assertEquals(1.25f, info.airUtilTx!!, 1e-6f)
        assertEquals(7_300L, info.uptimeSeconds)
        assertEquals(now, info.metricsAt)
    }

    @Test
    fun `falls back to NodeDB metrics and user hardware`() {
        val info = RadioInfoMapper.toRadioInfo(own, bundle(), metrics = null, metricsAt = now)
        assertEquals(55, info.batteryPercent)
        assertEquals("T Echo", info.hardware)
        assertNull(info.firmware)
        assertNull(info.metricsAt) // no fresh telemetry yet
    }

    @Test
    fun `battery over 100 means plugged in`() {
        val info = RadioInfoMapper.toRadioInfo(own, null, DeviceMetrics.build { battery_level(101) }, now)
        assertTrue(info.pluggedIn)
        assertEquals(100, info.batteryPercent)
    }

    @Test
    fun `custom LoRa settings have no preset`() {
        val custom = bundle().copy(configs = listOf(Config.build { lora(Config.LoRaConfig.build { use_preset(false) }) }))
        assertNull(RadioInfoMapper.toRadioInfo(own, custom, null, null).modemPreset)
    }

    @Test
    fun `nothing known`() {
        val info = RadioInfoMapper.toRadioInfo(null, null, null, null)
        assertNull(info.nodeIdHex)
        assertNull(info.batteryPercent)
        assertNull(info.role)
    }

    @Test
    fun labels() {
        assertEquals("T Beam S3 Core", RadioInfoMapper.label("T_BEAM_S3_CORE"))
        assertEquals("Client", RadioInfoMapper.label("CLIENT"))
    }

    @Test
    fun uptime() {
        assertEquals("8 min", RadioInfoMapper.formatUptime(500))
        assertEquals("2 h 1 min", RadioInfoMapper.formatUptime(7_300))
        assertEquals("3 d 4 h", RadioInfoMapper.formatUptime(3 * 86_400L + 4 * 3600 + 59))
    }
}
