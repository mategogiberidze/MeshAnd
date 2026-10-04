package com.meshand.app.data.meshtastic

import com.meshand.app.domain.model.MeshNode
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.User
import java.time.Instant

class MeshtasticMapperTest {

    @Test
    fun `maps full NodeInfo`() {
        val info = NodeInfo(
            num = 0x12345678,
            user = User(id = "!12345678", long_name = "Giorgi", short_name = "GIO"),
            position = Position(latitude_i = 417_151_234, longitude_i = 447_827_000, altitude = 850),
            snr = 7.5f,
            last_heard = 1_790_000_000,
            device_metrics = DeviceMetrics(battery_level = 78),
            hops_away = 1,
        )
        val node = MeshtasticMapper.toMeshNode(info, ownNodeNum = null)
        assertEquals(0x12345678L, node.id)
        assertEquals("!12345678", node.nodeIdHex)
        assertEquals("Giorgi", node.longName)
        assertEquals("GIO", node.shortName)
        assertEquals(41.7151234, node.latitude!!, 1e-9)
        assertEquals(44.7827, node.longitude!!, 1e-9)
        assertEquals(850, node.altitude)
        assertEquals(78, node.batteryLevel)
        assertEquals(7.5f, node.snr!!, 0f)
        assertEquals(1, node.hopsAway)
        assertEquals(Instant.ofEpochSecond(1_790_000_000), node.lastSeen)
    }

    @Test
    fun `missing fields map to null`() {
        val node = MeshtasticMapper.toMeshNode(NodeInfo(num = 42), ownNodeNum = null)
        assertNull(node.shortName)
        assertNull(node.longName)
        assertNull(node.latitude)
        assertNull(node.longitude)
        assertNull(node.altitude)
        assertNull(node.batteryLevel)
        assertNull(node.snr)
        assertNull(node.hopsAway)
        assertNull(node.lastSeen)
        assertFalse(node.hasPosition)
    }

    @Test
    fun `node numbers above Int MAX are unsigned`() {
        val node = MeshtasticMapper.toMeshNode(NodeInfo(num = 0xDEADBEEF.toInt()), ownNodeNum = null)
        assertEquals(0xDEADBEEFL, node.id)
        assertEquals("!deadbeef", node.nodeIdHex)
    }

    @Test
    fun `battery above 100 means external power`() {
        val node = MeshtasticMapper.toMeshNode(
            NodeInfo(num = 1, device_metrics = DeviceMetrics(battery_level = 101)),
            ownNodeNum = null,
        )
        assertEquals(MeshNode.BATTERY_POWERED, node.batteryLevel)
    }

    @Test
    fun `zero coordinates are treated as no fix`() {
        val node = MeshtasticMapper.toMeshNode(
            NodeInfo(num = 1, position = Position(latitude_i = 0, longitude_i = 0)),
            ownNodeNum = null,
        )
        assertFalse(node.hasPosition)
    }

    @Test
    fun `negative coordinates are preserved`() {
        val pos = Position(latitude_i = -338_688_000, longitude_i = -700_000_000)
        assertEquals(-33.8688, MeshtasticMapper.latitudeOf(pos)!!, 1e-9)
        assertEquals(-70.0, MeshtasticMapper.longitudeOf(pos)!!, 1e-9)
    }

    @Test
    fun `position packet becomes live update`() {
        val payload = Position.ADAPTER.encode(Position(latitude_i = 417_000_000, longitude_i = 447_000_000, altitude = 500))
        val packet = MeshPacket(
            from = 0x0A0B0C0D,
            decoded = Data(portnum = PortNum.POSITION_APP, payload = payload.toByteString()),
            rx_snr = 6.25f,
            hop_start = 3,
            hop_limit = 1,
        )
        val now = Instant.ofEpochSecond(1_800_000_000)
        val update = MeshtasticMapper.toLiveUpdate(packet, now)!!
        assertEquals(0x0A0B0C0DL, update.nodeId)
        assertEquals(41.7, update.latitude!!, 1e-9)
        assertEquals(44.7, update.longitude!!, 1e-9)
        assertEquals(500, update.altitude)
        assertEquals(6.25f, update.snr!!, 0f)
        assertEquals(2, update.hopsAway)
        assertEquals(now, update.heardAt)
    }

    @Test
    fun `hops unknown without hop_start`() {
        assertNull(MeshtasticMapper.hopsAway(hopStart = 0, hopLimit = 3))
        assertEquals(0, MeshtasticMapper.hopsAway(hopStart = 3, hopLimit = 3))
    }

    @Test
    fun `live position overrides NodeDB position, non-position packet keeps it`() {
        val base = MeshtasticMapper.toMeshNode(
            NodeInfo(
                num = 7,
                user = User(long_name = "Base"),
                position = Position(latitude_i = 10_000_000, longitude_i = 20_000_000),
                last_heard = 1_000,
                device_metrics = DeviceMetrics(battery_level = 50),
            ),
            ownNodeNum = null,
        )
        val heard = Instant.ofEpochSecond(2_000)
        val positionUpdate = LiveUpdate(7, heard, snr = 3f, hopsAway = 0, latitude = 1.5, longitude = 2.5, altitude = 100)
        val merged = MeshtasticMapper.merge(7, base, positionUpdate, ownNodeId = 7)
        assertEquals(1.5, merged.latitude!!, 0.0)
        assertEquals(2.5, merged.longitude!!, 0.0)
        assertEquals(100, merged.altitude)
        assertEquals(50, merged.batteryLevel) // untouched by packets
        assertEquals("Base", merged.longName)
        assertEquals(heard, merged.lastSeen)
        assertTrue(merged.isOwnNode)

        val later = LiveUpdate(7, Instant.ofEpochSecond(3_000), snr = null, hopsAway = null)
        val accumulated = positionUpdate.mergedWith(later)
        val merged2 = MeshtasticMapper.merge(7, base, accumulated, ownNodeId = null)
        assertEquals(1.5, merged2.latitude!!, 0.0) // position kept
        assertEquals(3f, merged2.snr!!, 0f) // snr kept
        assertEquals(Instant.ofEpochSecond(3_000), merged2.lastSeen)
        assertFalse(merged2.isOwnNode)
    }

    @Test
    fun `node heard only via packets still appears`() {
        val update = LiveUpdate(99, Instant.ofEpochSecond(5), snr = 1f, hopsAway = 2, shortName = "NEW")
        val node = MeshtasticMapper.merge(99, base = null, live = update, ownNodeId = null)
        assertEquals("NEW", node.shortName)
        assertEquals(2, node.hopsAway)
        assertFalse(node.hasPosition)
    }

    private fun positionPacket(latI: Int, time: Int = 0, timestamp: Int = 0, precisionBits: Int = 32): MeshPacket {
        val payload = Position.ADAPTER.encode(
            Position(latitude_i = latI, longitude_i = 447_000_000, time = time, timestamp = timestamp, precision_bits = precisionBits),
        )
        return MeshPacket(from = 7, decoded = Data(portnum = PortNum.POSITION_APP, payload = payload.toByteString()))
    }

    @Test
    fun `fix time comes from timestamp, not from the send time`() {
        val now = Instant.ofEpochSecond(1_800_000_000)
        val sentOnly = MeshtasticMapper.toLiveUpdate(positionPacket(417_000_000, time = 1_800_000_000), now)!!
        assertNull(sentOnly.fixTime)
        assertEquals(now, sentOnly.positionHeardAt)
        val withFix = MeshtasticMapper.toLiveUpdate(positionPacket(417_000_000, timestamp = 1_799_999_900), now)!!
        assertEquals(Instant.ofEpochSecond(1_799_999_900), withFix.fixTime)
    }

    @Test
    fun `position change is tracked across repeated reports`() {
        fun at(s: Long) = Instant.ofEpochSecond(1_800_000_000 + s)
        val first = MeshtasticMapper.toLiveUpdate(positionPacket(417_000_000), at(0))!!
        val same = first.mergedWith(MeshtasticMapper.toLiveUpdate(positionPacket(417_000_000), at(600))!!)
        // Base without a position: the first live position counts as new.
        val fresh = MeshtasticMapper.merge(7, null, same, null)
        assertEquals(at(0), fresh.positionChangedAt)
        assertEquals(at(600), fresh.positionReportedAt)

        val moved = same.mergedWith(MeshtasticMapper.toLiveUpdate(positionPacket(417_000_010), at(1200))!!)
        assertEquals(at(1200), MeshtasticMapper.merge(7, null, moved, null).positionChangedAt)
        // A packet without position keeps everything.
        val ping = moved.mergedWith(LiveUpdate(7, at(1300), snr = null, hopsAway = null))
        assertEquals(at(1200), MeshtasticMapper.merge(7, null, ping, null).positionChangedAt)
    }

    @Test
    fun `first live position equal to NodeDB keeps the NodeDB time as upper bound`() {
        val base = MeshtasticMapper.toMeshNode(
            NodeInfo(num = 7, position = Position(latitude_i = 417_000_000, longitude_i = 447_000_000, time = 1_799_990_000)),
            ownNodeNum = null,
        )
        val now = Instant.ofEpochSecond(1_800_000_000)
        val same = MeshtasticMapper.toLiveUpdate(positionPacket(417_000_000), now)!!
        assertEquals(Instant.ofEpochSecond(1_799_990_000), MeshtasticMapper.merge(7, base, same, null).positionChangedAt)
        val different = MeshtasticMapper.toLiveUpdate(positionPacket(417_000_500), now)!!
        assertEquals(now, MeshtasticMapper.merge(7, base, different, null).positionChangedAt)
    }

    @Test
    fun `imprecise channel positions don't track changes`() {
        val update = MeshtasticMapper.toLiveUpdate(positionPacket(417_000_000, precisionBits = 13), Instant.ofEpochSecond(1_800_000_000))!!
        assertNull(MeshtasticMapper.merge(7, null, update, null).positionChangedAt)
    }
}
