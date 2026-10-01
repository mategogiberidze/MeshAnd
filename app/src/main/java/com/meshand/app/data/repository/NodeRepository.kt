package com.meshand.app.data.repository

import android.util.Log
import com.meshand.app.data.meshtastic.LiveUpdate
import com.meshand.app.data.meshtastic.MeshtasticClient
import com.meshand.app.data.meshtastic.MeshtasticMapper
import com.meshand.app.domain.TeamRules
import com.meshand.app.domain.model.MeshNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.NodeChange
import org.meshtastic.sdk.NodeField
import org.meshtastic.sdk.RadioClient
import java.time.Instant

private const val TAG = "MeshAnd"

/**
 * Maintains the app's live node list for the current radio session.
 *
 * Lives for the whole process. Two inputs are combined:
 *  - [RadioClient.nodes]: the SDK's NodeDB (full snapshot at handshake, then deltas for
 *    unsolicited NodeInfo frames and telemetry merges);
 *  - [RadioClient.packets]: live mesh packets, from which we take position, names,
 *    SNR, hops and last-heard (the SDK does not fold these into its NodeDB).
 */
class NodeRepository(
    client: MeshtasticClient,
    scope: CoroutineScope,
) {
    private val baseNodes = MutableStateFlow<Map<Long, MeshNode>>(emptyMap())
    private val liveUpdates = MutableStateFlow<Map<Long, LiveUpdate>>(emptyMap())
    private val ownNodeId = MutableStateFlow<Long?>(null)

    /** All known nodes, most recently heard first. */
    val nodes: StateFlow<List<MeshNode>> =
        combine(baseNodes, liveUpdates, ownNodeId) { base, live, own ->
            (base.keys + live.keys).map { id -> MeshtasticMapper.merge(id, base[id], live[id], own) }
                .sortedWith(compareByDescending<MeshNode> { it.isOwnNode }.thenByDescending { it.lastSeen })
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * [nodes] minus anyone not heard within [TeamRules.ACTIVE_WINDOW] (own radio always kept).
     * Re-evaluated every minute, since nodes age out without any new data arriving.
     */
    val activeNodes: StateFlow<List<MeshNode>> =
        combine(nodes, minuteTicker()) { all, now -> TeamRules.activeNodes(all, now) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun minuteTicker() = flow {
        while (true) {
            emit(Instant.now())
            delay(60_000)
        }
    }

    init {
        // Nodes survive disconnects and reconnects (last-known positions stay on the map, going
        // stale with time); they are only cleared when the user switches to a different radio.
        scope.launch {
            var lastRadioAddress: String? = null
            client.activeRadio.collect { radio ->
                if (radio != null && lastRadioAddress != null && radio.address != lastRadioAddress) {
                    Log.i(TAG, "Different radio selected; clearing node list")
                    baseNodes.value = emptyMap()
                    liveUpdates.value = emptyMap()
                    ownNodeId.value = null
                }
                if (radio != null) lastRadioAddress = radio.address
            }
        }
        scope.launch {
            client.session.collectLatest { session ->
                if (session != null) observe(session)
            }
        }
    }

    /** Suspends for the life of [session]; cancelled by collectLatest when the session changes. */
    private suspend fun observe(session: RadioClient) = kotlinx.coroutines.coroutineScope {
        launch {
            session.ownNode.collect { info ->
                if (info != null) ownNodeId.value = MeshtasticMapper.nodeNumToLong(info.num)
            }
        }
        launch {
            session.nodes.collect(::onNodeChange)
        }
        launch {
            session.packets.collect { packet ->
                val update = try {
                    MeshtasticMapper.toLiveUpdate(packet, Instant.now())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse packet from 0x${packet.from.toUInt().toString(16)}", e)
                    null
                } ?: return@collect
                if (update.hasPosition) {
                    Log.i(
                        TAG,
                        "Position update received: !%08x lat=%.5f lon=%.5f alt=%s snr=%s hops=%s".format(
                            update.nodeId, update.latitude, update.longitude,
                            update.altitude, update.snr, update.hopsAway,
                        ),
                    )
                } else if (packet.decoded?.portnum == PortNum.POSITION_APP) {
                    Log.i(TAG, "Position packet without coordinates from !%08x".format(update.nodeId))
                } else {
                    Log.d(TAG, "Packet ${packet.decoded?.portnum} from !%08x".format(update.nodeId))
                }
                liveUpdates.update { current ->
                    current + (update.nodeId to (current[update.nodeId]?.mergedWith(update) ?: update))
                }
            }
        }
    }

    private fun onNodeChange(change: NodeChange) {
        when (change) {
            is NodeChange.Snapshot -> {
                // A new session first emits the SDK's (still empty) map before the handshake;
                // keep the last-known nodes until the radio sends its real NodeDB.
                if (change.nodes.isEmpty()) {
                    Log.d(TAG, "Empty NodeDB snapshot ignored (session starting)")
                    return
                }
                val mapped = change.nodes.values.mapNotNull(::mapSafely).associateBy { it.id }
                Log.i(TAG, "NodeDB received: ${change.nodes.size} node(s), ${mapped.values.count { it.hasPosition }} with position")
                baseNodes.value = mapped
            }
            is NodeChange.Added -> {
                val node = mapSafely(change.node) ?: return
                Log.i(TAG, "Node added: ${node.nodeIdHex} ${node.longName ?: ""}")
                baseNodes.update { it + (node.id to node) }
            }
            is NodeChange.Updated -> {
                val node = mapSafely(change.node) ?: return
                if (NodeField.Position in change.changed) {
                    Log.i(TAG, "Position update received (NodeDB): ${node.nodeIdHex} lat=${node.latitude} lon=${node.longitude}")
                } else {
                    Log.d(TAG, "Node updated: ${node.nodeIdHex} fields=${change.changed}")
                }
                baseNodes.update { it + (node.id to node) }
            }
            is NodeChange.Removed -> {
                val id = MeshtasticMapper.nodeNumToLong(change.nodeId.raw)
                Log.i(TAG, "Node removed: !%08x".format(id))
                baseNodes.update { it - id }
                liveUpdates.update { it - id }
            }
            is NodeChange.WentOffline ->
                Log.d(TAG, "Node offline: !%08x".format(MeshtasticMapper.nodeNumToLong(change.nodeId.raw)))
            is NodeChange.CameOnline ->
                Log.d(TAG, "Node online: !%08x".format(MeshtasticMapper.nodeNumToLong(change.nodeId.raw)))
        }
    }

    private fun mapSafely(info: NodeInfo): MeshNode? = try {
        MeshtasticMapper.toMeshNode(info, ownNodeNum = null)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to map NodeInfo num=0x${info.num.toUInt().toString(16)}", e)
        null
    }
}
