package com.meshand.app.data.meshtastic

import org.meshtastic.proto.Channel
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.ConfigBundle
import org.meshtastic.sdk.DeviceStorage
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.SessionPasskey
import org.meshtastic.sdk.StorageProvider
import org.meshtastic.sdk.TransportIdentity
import java.util.concurrent.ConcurrentHashMap

/**
 * The Meshtastic SDK requires a [StorageProvider]; its only published implementation is
 * SQLDelight-backed. Phase 1 deliberately has no database, so this keeps everything in memory.
 * The radio re-sends its full NodeDB on every connect, so nothing is lost that matters here.
 *
 * Config and channels (which contain PSKs) are held only in memory and never logged.
 */
class InMemoryStorageProvider : StorageProvider {
    private val devices = ConcurrentHashMap<TransportIdentity, InMemoryDeviceStorage>()

    override suspend fun activate(identity: TransportIdentity): DeviceStorage =
        devices.getOrPut(identity) { InMemoryDeviceStorage() }
}

private class InMemoryDeviceStorage : DeviceStorage {
    private val nodes = ConcurrentHashMap<NodeId, NodeInfo>()
    private val heartbeats = ConcurrentHashMap<NodeId, Long>()

    @Volatile private var config: ConfigBundle? = null
    @Volatile private var channels: List<Channel> = emptyList()
    @Volatile private var passkey: SessionPasskey? = null

    override suspend fun loadNodes(): Map<NodeId, NodeInfo> = HashMap(nodes)
    override suspend fun saveNode(node: NodeInfo) {
        nodes[NodeId(node.num)] = node
    }
    override suspend fun removeNode(nodeId: NodeId) {
        nodes.remove(nodeId)
    }

    override suspend fun loadConfig(): ConfigBundle? = config
    override suspend fun saveConfig(config: ConfigBundle) {
        this.config = config
    }

    override suspend fun loadChannels(): List<Channel> = channels
    override suspend fun saveChannels(channels: List<Channel>) {
        this.channels = channels
    }

    override suspend fun recordOwnNode(nodeNum: NodeId, firmwareVersion: String) = Unit

    override suspend fun clear() {
        nodes.clear()
        heartbeats.clear()
        config = null
        channels = emptyList()
        passkey = null
    }

    override fun close() = Unit

    override suspend fun saveSessionPasskey(passkey: SessionPasskey) {
        this.passkey = passkey
    }
    override suspend fun loadSessionPasskey(): SessionPasskey? = passkey

    override suspend fun saveHeartbeat(nodeId: NodeId, epochMillis: Long) {
        heartbeats[nodeId] = epochMillis
    }
    override suspend fun loadHeartbeats(): Map<NodeId, Long> = HashMap(heartbeats)
}
