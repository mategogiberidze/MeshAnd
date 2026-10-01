package com.meshand.app.data.osmand

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.meshand.app.domain.model.MeshNode
import com.meshand.app.domain.model.OsmAndStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.map.ALatLon
import net.osmand.aidlapi.maplayer.AMapLayer
import net.osmand.aidlapi.maplayer.AddMapLayerParams
import net.osmand.aidlapi.maplayer.RemoveMapLayerParams
import net.osmand.aidlapi.maplayer.UpdateMapLayerParams
import net.osmand.aidlapi.maplayer.point.AMapPoint
import net.osmand.aidlapi.maplayer.point.RemoveMapPointParams
import net.osmand.aidlapi.maplayer.point.ShowMapPointParams
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private const val TAG = "MeshAnd/OsmAnd"

/**
 * Mirrors mesh nodes onto the OsmAnd map through OsmAnd's AIDL V2 API (`net.osmand.aidlapi`):
 * one custom map layer, one point per node with a known position, updated in place.
 *
 * - OsmAnd keeps custom layers in memory only, so the whole layer is re-sent on (re)connect and
 *   every [REFRESH_INTERVAL] (which also refreshes the "stale" flag).
 * - Updates are throttled to one push per [MIN_PUSH_INTERVAL]; every call redraws OsmAnd's map.
 * - Binder calls run on a single background thread, never on the main thread.
 */
class OsmAndBridge(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val binderDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _status = MutableStateFlow<OsmAndStatus>(OsmAndStatus.Off)
    val status: StateFlow<OsmAndStatus> = _status.asStateFlow()

    @Volatile private var service: IOsmAndAidlInterface? = null
    @Volatile private var latestNodes: List<MeshNode> = emptyList()
    private var packageName: String? = null
    private var bound = false
    private var syncJob: Job? = null
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)

    // Only touched on binderDispatcher.
    private var sentPointIds: Set<String> = emptySet()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "Connected to OsmAnd service (${name?.packageName})")
            service = IOsmAndAidlInterface.Stub.asInterface(binder)
            _status.value = OsmAndStatus.Connecting("Connected, sending nodes")
            syncRequests.trySend(Unit)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // OsmAnd's process died; Android rebinds automatically when it comes back.
            Log.w(TAG, "OsmAnd service disconnected (${name?.packageName}); waiting for it to restart")
            service = null
            _status.value = OsmAndStatus.Connecting("OsmAnd stopped, waiting for it to restart")
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.w(TAG, "OsmAnd binding died; rebinding")
            service = null
            unbind()
            bind()
        }
    }

    /** First installed OsmAnd build exposing the AIDL V2 service, or null. */
    fun findOsmAndPackage(): String? = OSMAND_PACKAGES.firstOrNull { pkg ->
        appContext.packageManager.queryIntentServices(Intent(SERVICE_ACTION).setPackage(pkg), 0).isNotEmpty()
    }

    fun enable() {
        if (packageName != null) return
        val pkg = findOsmAndPackage()
        if (pkg == null) {
            Log.w(TAG, "No OsmAnd with AIDL V2 service installed (checked $OSMAND_PACKAGES)")
            _status.value = OsmAndStatus.NotInstalled
            return
        }
        packageName = pkg
        _status.value = OsmAndStatus.Connecting("Connecting to OsmAnd ($pkg)")
        if (!bind()) return
        syncJob = scope.launch {
            launch {
                while (true) {
                    delay(REFRESH_INTERVAL)
                    syncRequests.trySend(Unit)
                }
            }
            for (request in syncRequests) {
                val nodes = latestNodes
                withContext(binderDispatcher) { push(nodes) }
                delay(MIN_PUSH_INTERVAL)
            }
        }
    }

    fun disable() {
        if (packageName == null) return
        Log.i(TAG, "Disabling OsmAnd bridge, removing layer")
        syncJob?.cancel()
        syncJob = null
        val svc = service
        scope.launch(binderDispatcher) {
            try {
                svc?.removeMapLayer(RemoveMapLayerParams(LAYER_ID))
            } catch (e: Exception) {
                Log.w(TAG, "removeMapLayer failed", e)
            }
            sentPointIds = emptySet()
        }
        unbind()
        service = null
        packageName = null
        _status.value = OsmAndStatus.Off
    }

    /** Called with every node-list change; pushed to OsmAnd at most once per [MIN_PUSH_INTERVAL]. */
    fun updateNodes(nodes: List<MeshNode>) {
        latestNodes = nodes
        if (packageName != null) syncRequests.trySend(Unit)
    }

    /** Opens OsmAnd centred on [node]. */
    fun showOnMap(node: MeshNode) {
        val spec = OsmAndMapper.toSpec(node, Instant.now()) ?: return
        val svc = service ?: return
        scope.launch(binderDispatcher) {
            try {
                val ok = svc.showMapPoint(ShowMapPointParams(LAYER_ID, spec.toAMapPoint()))
                Log.i(TAG, "showMapPoint ${spec.id}: $ok")
            } catch (e: Exception) {
                Log.w(TAG, "showMapPoint failed", e)
            }
        }
    }

    fun close() {
        disable()
        scope.launch {
            delay(1.seconds) // let the removeMapLayer call go out
            scope.cancel()
        }
    }

    private fun bind(): Boolean {
        val pkg = packageName ?: return false
        var flags = Context.BIND_AUTO_CREATE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = flags or Context.BIND_ALLOW_ACTIVITY_STARTS // lets showMapPoint bring OsmAnd forward
        }
        val ok = try {
            appContext.bindService(Intent(SERVICE_ACTION).setPackage(pkg), connection, flags)
        } catch (e: SecurityException) {
            Log.e(TAG, "bindService to $pkg rejected", e)
            false
        }
        bound = true // per the docs, unbindService is required even when bindService returns false
        if (!ok) {
            Log.e(TAG, "bindService to $pkg returned false")
            _status.value = OsmAndStatus.Error("Could not connect to OsmAnd ($pkg)")
        }
        return ok
    }

    private fun unbind() {
        if (!bound) return
        bound = false
        try {
            appContext.unbindService(connection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "unbindService: not bound", e)
        }
    }

    /** Runs on [binderDispatcher]. Sends the full layer, then removes points for vanished nodes. */
    private fun push(nodes: List<MeshNode>) {
        val svc = service ?: return
        val pkg = packageName ?: return
        val now = Instant.now()
        val specs = nodes.mapNotNull { node ->
            try {
                OsmAndMapper.toSpec(node, now)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to map node ${node.nodeIdHex}", e)
                null
            }
        }
        try {
            val layer = AMapLayer(LAYER_ID, LAYER_NAME, LAYER_Z_ORDER, specs.map { it.toAMapPoint() })
            // updateMapLayer fails when OsmAnd doesn't have our layer yet (first push, or OsmAnd
            // restarted); then add it. Re-adding after a remove covers a half-registered layer.
            var ok = svc.updateMapLayer(UpdateMapLayerParams(layer))
            if (!ok) ok = svc.addMapLayer(AddMapLayerParams(layer))
            if (!ok) {
                svc.removeMapLayer(RemoveMapLayerParams(LAYER_ID))
                ok = svc.addMapLayer(AddMapLayerParams(layer))
            }
            if (!ok) {
                // Every call failing means OsmAnd has our app switched off (OsmAnd 5.3+ default).
                Log.w(TAG, "OsmAnd rejected the layer: enable MeshAnd in OsmAnd → Menu → Plugins")
                sentPointIds = emptySet()
                _status.value = OsmAndStatus.NotAllowed(pkg)
                return
            }
            val newIds = specs.mapTo(HashSet()) { it.id }
            for (gone in sentPointIds - newIds) {
                svc.removeMapPoint(RemoveMapPointParams(LAYER_ID, gone))
                Log.d(TAG, "Removed point $gone")
            }
            if (newIds != sentPointIds) Log.i(TAG, "Layer synced: ${specs.size} node(s) on the OsmAnd map")
            sentPointIds = newIds
            _status.value = OsmAndStatus.Showing(pkg, specs.size)
        } catch (e: RemoteException) {
            Log.w(TAG, "OsmAnd call failed; will retry on reconnect", e)
            _status.value = OsmAndStatus.Connecting("OsmAnd not responding, retrying")
        }
    }

    private fun MapPointSpec.toAMapPoint(): AMapPoint {
        val params = HashMap<String, String>()
        if (stale) params[AMapPoint.POINT_STALE_LOC_PARAM] = "true"
        return AMapPoint(
            id, shortName, fullName, typeName, LAYER_ID, color,
            ALatLon(latitude, longitude), details, params,
        )
    }

    companion object {
        const val SERVICE_ACTION = "net.osmand.aidl.OsmandAidlServiceV2"

        /** OsmAnd+ / F-Droid, Google Play free, nightly, Huawei; first installed wins. */
        val OSMAND_PACKAGES = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev", "net.osmand.huawei")

        const val LAYER_ID = "meshand_nodes"
        const val LAYER_NAME = "Meshtastic nodes"

        /** Just below OsmAnd's own "my location" layer (6.0), above favourites (4.0). */
        const val LAYER_Z_ORDER = 5.5f

        val MIN_PUSH_INTERVAL = 1.seconds
        val REFRESH_INTERVAL = 30.seconds
    }
}
