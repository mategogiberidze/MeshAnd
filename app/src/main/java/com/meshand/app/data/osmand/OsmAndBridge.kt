package com.meshand.app.data.osmand

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import android.view.KeyEvent
import com.meshand.app.domain.NodeColors
import com.meshand.app.domain.model.MeshNode
import com.meshand.app.domain.model.OsmAndStatus
import com.meshand.app.domain.model.Pin
import com.meshand.app.domain.model.TrailPoint
import com.meshand.app.ui.team.TeamActivity
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.osmand.aidlapi.IOsmAndAidlCallback
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.contextmenu.AContextMenuButton
import net.osmand.aidlapi.contextmenu.ContextMenuButtonsParams
import net.osmand.aidlapi.contextmenu.RemoveContextMenuButtonsParams
import net.osmand.aidlapi.gpx.AGpxBitmap
import net.osmand.aidlapi.gpx.AGpxFile
import net.osmand.aidlapi.gpx.ImportGpxParams
import net.osmand.aidlapi.gpx.RemoveGpxParams
import net.osmand.aidlapi.logcat.OnLogcatMessageParams
import net.osmand.aidlapi.map.ALatLon
import net.osmand.aidlapi.mapwidget.AMapWidget
import net.osmand.aidlapi.mapwidget.AddMapWidgetParams
import net.osmand.aidlapi.mapwidget.RemoveMapWidgetParams
import net.osmand.aidlapi.mapwidget.UpdateMapWidgetParams
import net.osmand.aidlapi.navdrawer.NavDrawerItem
import net.osmand.aidlapi.navdrawer.SetNavDrawerItemsParams
import net.osmand.aidlapi.navigation.ADirectionInfo
import net.osmand.aidlapi.navigation.NavigateParams
import net.osmand.aidlapi.navigation.OnVoiceNavigationParams
import net.osmand.aidlapi.maplayer.AMapLayer
import net.osmand.aidlapi.maplayer.AddMapLayerParams
import net.osmand.aidlapi.maplayer.RemoveMapLayerParams
import net.osmand.aidlapi.maplayer.UpdateMapLayerParams
import net.osmand.aidlapi.maplayer.point.AMapPoint
import net.osmand.aidlapi.maplayer.point.RemoveMapPointParams
import net.osmand.aidlapi.maplayer.point.ShowMapPointParams
import net.osmand.aidlapi.search.SearchResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

private const val TAG = "MeshAnd/OsmAnd"

/**
 * Mirrors mesh nodes onto the OsmAnd map through OsmAnd's AIDL V2 API (`net.osmand.aidlapi`):
 * one custom map layer, one point per node with a known position, updated in place; plus a
 * "Meshtastic team" map widget (group icon + member count) and an OsmAnd side-menu item, both
 * opening [TeamActivity] to list team members and jump to / navigate to them.
 *
 * Trails: a teammate's recent positions are drawn as a GPX track in their colour. It's toggled
 * from the team list or from the "Trail" button OsmAnd shows when you tap their point, and
 * re-imported as it grows. Trail files live in OsmAnd's tracks folder only while shown.
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

    private val _shownTrails = MutableStateFlow<Set<Long>>(emptySet())

    /** Node ids whose trail is currently drawn in OsmAnd. */
    val shownTrails: StateFlow<Set<Long>> = _shownTrails.asStateFlow()

    @Volatile private var latestTrails: Map<Long, List<TrailPoint>> = emptyMap()

    /** Your own radio is hidden from the map unless switched on (mostly useful for debugging). */
    @Volatile private var showOwnRadio = false

    /** Shared pins, drawn on their own layer. */
    @Volatile private var latestPins: List<Pin> = emptyList()

    /** Pin point ids OsmAnd currently has; only touched on [binderDispatcher]. */
    private var sentPinIds: Set<String> = emptySet()
    private var pinMenuCallbackId = NO_CALLBACK

    /** True once this OsmAnd connection has a pins layer we created (see [syncPins]). */
    private var pinsLayerCreated = false

    /** Called when the user taps "Remove" on a pin in OsmAnd. Set by [com.meshand.app.AppGraph]. */
    var onRemovePin: ((String) -> Unit)? = null

    // Only touched on binderDispatcher.
    private var sentPointIds: Set<String> = emptySet()
    private var navDrawerRegistered = false
    private var menuCallbackId = NO_CALLBACK
    private var staleTrailsCleaned = false
    private val importedTrails = HashMap<Long, ImportedTrail>()

    /** What was last sent to OsmAnd for one trail, to skip re-imports when nothing changed. */
    private data class ImportedTrail(
        /** OsmAnd titles the track by this file name ("MeshAnd trail - Giorgi.gpx"). */
        val fileName: String,
        val pointCount: Int,
        val lastTime: Instant?,
        val firstImportAt: Instant,
        /** True once an import happened after OsmAnd had time to index the new file. */
        val followUpDone: Boolean,
    )

    /** Receives taps on our buttons in OsmAnd's point menu. Called on a binder thread. */
    private val menuCallback = object : IOsmAndAidlCallback.Stub() {
        override fun onContextMenuButtonClicked(buttonId: Int, pointId: String?, layerId: String?) {
            if (pointId == null) return
            when (layerId) {
                LAYER_ID -> scope.launch { onMenuButton(buttonId, pointId) }
                PINS_LAYER_ID -> scope.launch { onPinMenuButton(buttonId, pointId) }
            }
        }

        override fun onSearchComplete(resultSet: MutableList<SearchResult>?) = Unit
        override fun onUpdate() = Unit
        override fun onAppInitialized() = Unit
        override fun onGpxBitmapCreated(bitmap: AGpxBitmap?) = Unit
        override fun updateNavigationInfo(directionInfo: ADirectionInfo?) = Unit
        override fun onVoiceRouterNotify(params: OnVoiceNavigationParams?) = Unit
        override fun onKeyEvent(keyEvent: KeyEvent?) = Unit
        override fun onLogcatMessage(params: OnLogcatMessageParams?) = Unit
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "Connected to OsmAnd service (${name?.packageName})")
            service = IOsmAndAidlInterface.Stub.asInterface(binder)
            scope.launch(binderDispatcher) {
                // A (re)started OsmAnd has none of our widgets, buttons or trails registered.
                navDrawerRegistered = false
                menuCallbackId = NO_CALLBACK
                pinMenuCallbackId = NO_CALLBACK
                sentPinIds = emptySet()
                pinsLayerCreated = false
                staleTrailsCleaned = false
                importedTrails.clear()
            }
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
                svc?.removeMapLayer(RemoveMapLayerParams(PINS_LAYER_ID))
                svc?.removeMapWidget(RemoveMapWidgetParams(WIDGET_ID))
                svc?.setNavDrawerItems(SetNavDrawerItemsParams(appContext.packageName, emptyList()))
                if (menuCallbackId != NO_CALLBACK) {
                    svc?.removeContextMenuButtons(RemoveContextMenuButtonsParams(MENU_BUTTONS_ID, menuCallbackId))
                }
                if (pinMenuCallbackId != NO_CALLBACK) {
                    svc?.removeContextMenuButtons(RemoveContextMenuButtonsParams(PIN_BUTTONS_ID, pinMenuCallbackId))
                }
                if (svc != null) importedTrails.keys.toList().forEach { removeTrail(svc, it) }
            } catch (e: Exception) {
                Log.w(TAG, "Removing layer/widget/menu item failed", e)
            }
            sentPointIds = emptySet()
            navDrawerRegistered = false
            menuCallbackId = NO_CALLBACK
            pinMenuCallbackId = NO_CALLBACK
            sentPinIds = emptySet()
            pinsLayerCreated = false
            importedTrails.clear()
        }
        unbind()
        service = null
        packageName = null
        _shownTrails.value = emptySet()
        _status.value = OsmAndStatus.Off
    }

    /** Called with every node-list change; pushed to OsmAnd at most once per [MIN_PUSH_INTERVAL]. */
    fun updateNodes(nodes: List<MeshNode>) {
        latestNodes = nodes
        if (packageName != null) syncRequests.trySend(Unit)
    }

    /** Latest trails from the recorder; shown trails are re-imported when they grow. */
    fun updateTrails(trails: Map<Long, List<TrailPoint>>) {
        latestTrails = trails
        if (packageName != null && _shownTrails.value.isNotEmpty()) syncRequests.trySend(Unit)
    }

    fun updatePins(pins: List<Pin>) {
        latestPins = pins
        if (packageName != null) syncRequests.trySend(Unit)
    }

    fun setShowOwnRadio(show: Boolean) {
        showOwnRadio = show
        if (packageName != null) syncRequests.trySend(Unit)
    }

    fun showTrail(nodeId: Long) {
        Log.i(TAG, "Show trail !%08x".format(nodeId))
        _shownTrails.update { it + nodeId }
        syncRequests.trySend(Unit)
    }

    fun hideTrail(nodeId: Long) {
        Log.i(TAG, "Hide trail !%08x".format(nodeId))
        _shownTrails.update { it - nodeId }
        syncRequests.trySend(Unit)
    }

    fun toggleTrail(nodeId: Long) {
        if (nodeId in _shownTrails.value) hideTrail(nodeId) else showTrail(nodeId)
    }

    private fun onMenuButton(buttonId: Int, pointId: String) {
        val node = latestNodes.firstOrNull { it.nodeIdHex == pointId } ?: return
        when (buttonId) {
            BUTTON_TRAIL -> toggleTrail(node.id)
            BUTTON_NAVIGATE -> navigateTo(node)
        }
    }

    private fun onPinMenuButton(buttonId: Int, pointId: String) {
        val pin = latestPins.firstOrNull { it.id == pointId } ?: return
        when (buttonId) {
            BUTTON_NAVIGATE -> navigate(pin.latitude, pin.longitude, pin.name ?: "Pin", pin.id)
            BUTTON_REMOVE_PIN -> onRemovePin?.invoke(pin.id)
        }
    }

    /** Starts OsmAnd navigation to [pin] (walking profile). */
    fun navigateToPin(pin: Pin) = navigate(pin.latitude, pin.longitude, pin.name ?: "Pin", pin.id)

    /** Opens OsmAnd centred on [pin]. */
    fun showPin(pin: Pin) {
        val svc = service ?: return
        val point = pinPoint(pin, latestNodes, Instant.now())
        scope.launch(binderDispatcher) {
            try {
                Log.i(TAG, "showMapPoint ${pin.id}: ${svc.showMapPoint(ShowMapPointParams(PINS_LAYER_ID, point))}")
            } catch (e: Exception) {
                Log.w(TAG, "showMapPoint failed", e)
            }
        }
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

    /** Starts OsmAnd navigation from the phone's current location to [node] (walking profile). */
    fun navigateTo(node: MeshNode) {
        val lat = node.latitude ?: return
        val lon = node.longitude ?: return
        navigate(lat, lon, node.longName ?: node.shortName ?: node.nodeIdHex, node.nodeIdHex)
    }

    private fun navigate(lat: Double, lon: Double, name: String, logId: String) {
        val svc = service ?: return
        scope.launch(binderDispatcher) {
            try {
                // Start (0,0) = OsmAnd uses the phone's current location; force=false lets OsmAnd
                // ask before replacing an existing route.
                val ok = svc.navigate(NavigateParams(null, 0.0, 0.0, name, lat, lon, NAVIGATION_PROFILE, false, true))
                Log.i(TAG, "navigate to $logId: $ok")
            } catch (e: Exception) {
                Log.w(TAG, "navigate failed", e)
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
        val specs = nodes.filter { showOwnRadio || !it.isOwnNode }.mapNotNull { node ->
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
            pushTeamEntryPoints(svc, nodes)
            registerMenuButtons(svc)
            syncTrails(svc, nodes)
            syncPins(svc, nodes)
            _status.value = OsmAndStatus.Showing(pkg, specs.size)
        } catch (e: RemoteException) {
            Log.w(TAG, "OsmAnd call failed; will retry on reconnect", e)
            _status.value = OsmAndStatus.Connecting("OsmAnd not responding, retrying")
        }
    }

    /**
     * The map widget (group icon, number of team members heard) and the side-menu item, both
     * opening [TeamActivity]. Like layers, OsmAnd keeps them in memory only, so they're re-added
     * when an update fails. Runs on [binderDispatcher].
     */
    private fun pushTeamEntryPoints(svc: IOsmAndAidlInterface, nodes: List<MeshNode>) {
        val members = nodes.count { !it.isOwnNode }
        val widget = AMapWidget(
            WIDGET_ID,
            TEAM_ICON, // menu icon (OsmAnd drawable name)
            "Meshtastic team", // title in OsmAnd's widget settings
            TEAM_ICON, // widget icon, day
            TEAM_ICON, // widget icon, night
            members.toString(),
            "team",
            WIDGET_ORDER,
            teamIntent(),
        )
        if (!svc.updateMapWidget(UpdateMapWidgetParams(widget))) {
            val added = svc.addMapWidget(AddMapWidgetParams(widget))
            Log.i(TAG, "Team widget added: $added")
        }
        if (!navDrawerRegistered) {
            val item = NavDrawerItem("Meshtastic team", TeamActivity.DEEP_LINK, TEAM_ICON)
            navDrawerRegistered = svc.setNavDrawerItems(SetNavDrawerItemsParams(appContext.packageName, listOf(item)))
            Log.i(TAG, "Team menu item registered: $navDrawerRegistered")
        }
    }

    /**
     * Adds "Trail" and "Navigate" to the menu OsmAnd opens when one of our points is tapped.
     * Like layers, OsmAnd forgets them when it restarts. Runs on [binderDispatcher].
     */
    private fun registerMenuButtons(svc: IOsmAndAidlInterface) {
        if (menuCallbackId != NO_CALLBACK) return
        val params = ContextMenuButtonsParams(
            AContextMenuButton(BUTTON_TRAIL, "Trail", null, TRAIL_ICON, null, true, true),
            AContextMenuButton(BUTTON_NAVIGATE, "Navigate", null, NAVIGATE_ICON, null, true, true),
            MENU_BUTTONS_ID,
            appContext.packageName,
            LAYER_ID,
            NO_CALLBACK,
            emptyList(),
        )
        val id = svc.addContextMenuButtons(params, menuCallback)
        if (id >= 0) menuCallbackId = id
        Log.i(TAG, "Point menu buttons registered: ${id >= 0}")
    }

    /** Draws shared pins on their own layer, with "Navigate"/"Remove" buttons. Runs on [binderDispatcher]. */
    private fun syncPins(svc: IOsmAndAidlInterface, nodes: List<MeshNode>) {
        val pins = latestPins
        if (pins.isEmpty() && sentPinIds.isEmpty()) return
        val now = Instant.now()
        val points = pins.map { pin -> pinPoint(pin, nodes, now) }
        val layer = AMapLayer(PINS_LAYER_ID, PINS_LAYER_NAME, PINS_LAYER_Z_ORDER, points).apply {
            // Image points: OsmAnd draws a pin-shaped marker with our icon inside (a coloured dot
            // when zoomed far out), instead of the plain circles used for teammates.
            isImagePoints = true
            setCirclePointZoomBounds(1, 6)
            setSmallPointZoomBounds(7, 11)
            setBigPointZoomBounds(12, 22)
        }
        // OsmAnd's updateMapLayer copies points and zoom bounds but not the image-points flag, so a
        // layer left over from an older MeshAnd (or created before) is re-created once per connection.
        val ok = if (pinsLayerCreated) {
            svc.updateMapLayer(UpdateMapLayerParams(layer)) || svc.addMapLayer(AddMapLayerParams(layer))
        } else {
            svc.removeMapLayer(RemoveMapLayerParams(PINS_LAYER_ID))
            svc.addMapLayer(AddMapLayerParams(layer)).also { pinsLayerCreated = it }
        }
        if (!ok) {
            Log.w(TAG, "OsmAnd rejected the pins layer")
            return
        }
        val ids = pins.mapTo(HashSet()) { it.id }
        for (gone in sentPinIds - ids) svc.removeMapPoint(RemoveMapPointParams(PINS_LAYER_ID, gone))
        if (ids != sentPinIds) Log.i(TAG, "Pins synced: ${pins.size} on the OsmAnd map")
        sentPinIds = ids
        if (pinMenuCallbackId == NO_CALLBACK) {
            val params = ContextMenuButtonsParams(
                AContextMenuButton(BUTTON_NAVIGATE, "Navigate", null, NAVIGATE_ICON, null, true, true),
                AContextMenuButton(BUTTON_REMOVE_PIN, "Remove", null, REMOVE_ICON, null, true, true),
                PIN_BUTTONS_ID,
                appContext.packageName,
                PINS_LAYER_ID,
                NO_CALLBACK,
                emptyList(),
            )
            val id = svc.addContextMenuButtons(params, menuCallback)
            if (id >= 0) pinMenuCallbackId = id
            Log.i(TAG, "Pin menu buttons registered: ${id >= 0}")
        }
    }

    /**
     * Imports shown trails that changed, and removes trails that were hidden. On a fresh OsmAnd
     * connection it first deletes trail files left over from an earlier session (for example when
     * Android killed MeshAnd while a trail was shown). Runs on [binderDispatcher].
     */
    private fun syncTrails(svc: IOsmAndAidlInterface, nodes: List<MeshNode>) {
        if (!staleTrailsCleaned) {
            staleTrailsCleaned = true
            val files = ArrayList<AGpxFile>()
            if (svc.getImportedGpx(files)) {
                files.map { it.fileName }.filter { name -> TRAIL_FILE_PREFIXES.any { name.startsWith(it) } }.forEach {
                    Log.i(TAG, "Removing leftover trail file $it")
                    svc.removeGpx(RemoveGpxParams(it))
                }
            }
        }
        val shown = _shownTrails.value
        (importedTrails.keys - shown).forEach { removeTrail(svc, it) }
        for (id in shown) {
            val points = latestTrails[id].orEmpty()
            val previous = importedTrails[id]
            val lastTime = points.lastOrNull()?.time
            val changed = previous == null || previous.pointCount != points.size || previous.lastTime != lastTime
            // OsmAnd skips the colour and its "imported via API" mark on a track's first import,
            // so every trail gets a follow-up import once OsmAnd has indexed the new file.
            if (!changed && previous.followUpDone) continue
            val node = nodes.firstOrNull { it.id == id }
            val name = displayName(node, id)
            val fileName = previous?.fileName ?: trailFileName(name, id, nodes)
            val color = node?.let(NodeColors::colorFor) ?: NodeColors.colorForId(id)
            val since = points.firstOrNull()?.time?.let(::formatTime)
            val description = buildString {
                append("Trail of $name (!%08x), recorded by MeshAnd".format(id))
                if (since != null) append(" since $since")
                append(" · ${points.size} position(s)")
            }
            val gpx = TrailGpx.build(
                name = "$name trail",
                description = description,
                color = color,
                points = points,
                startLabel = since?.let { "$name · start $it" },
            )
            val ok = svc.importGpx(ImportGpxParams(gpx, fileName, TrailGpx.colorHex(color), true))
            val now = Instant.now()
            val firstImportAt = previous?.firstImportAt ?: now
            val followUpDone = previous?.followUpDone == true ||
                (previous != null && !now.isBefore(firstImportAt.plusMillis(TRAIL_FOLLOW_UP_DELAY.inWholeMilliseconds)))
            importedTrails[id] = ImportedTrail(fileName, points.size, lastTime, firstImportAt, followUpDone)
            Log.i(TAG, "Trail $fileName: ${points.size} point(s), import ok=$ok")
            if (!followUpDone) {
                scope.launch {
                    delay(TRAIL_FOLLOW_UP_DELAY)
                    syncRequests.trySend(Unit)
                }
            }
        }
    }

    /** Hides a trail from the map, then deletes its file. Runs on [binderDispatcher]. */
    private fun removeTrail(svc: IOsmAndAidlInterface, id: Long) {
        val file = importedTrails[id]?.fileName ?: return
        // Re-importing with show=false hides it even when OsmAnd won't delete the file.
        svc.importGpx(ImportGpxParams(TrailGpx.build("", "", 0, emptyList()), file, "", false))
        val removed = svc.removeGpx(RemoveGpxParams(file))
        importedTrails.remove(id)
        Log.i(TAG, "Trail $file hidden, file removed=$removed")
    }

    private fun displayName(node: MeshNode?, id: Long) =
        node?.longName ?: node?.shortName ?: "!%08x".format(id)

    /** "MeshAnd trail - Giorgi.gpx"; the node id is added only if two teammates share a name. */
    private fun trailFileName(name: String, id: Long, nodes: List<MeshNode>): String {
        val clash = nodes.any { it.id != id && displayName(it, it.id) == name }
        val label = if (clash) "$name (%04x)".format(id and 0xFFFF) else name
        return TrailGpx.fileName(TRAIL_FILE_PREFIX, label)
    }

    /** "14:05" today, otherwise "Oct 3 14:05" (phone's time zone). */
    private fun formatTime(time: Instant): String {
        val zone = ZoneId.systemDefault()
        val local = time.atZone(zone)
        val today = Instant.now().atZone(zone).toLocalDate()
        val pattern = if (local.toLocalDate() == today) "HH:mm" else "MMM d HH:mm"
        return local.format(DateTimeFormatter.ofPattern(pattern, Locale.US))
    }

    /** OsmAnd starts this with its application context, so it must be a new task. */
    private fun teamIntent() = Intent(appContext, TeamActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** A pin as an OsmAnd image point, drawn with [PinIconProvider]'s icon in the sender's colour. */
    private fun pinPoint(pin: Pin, nodes: List<MeshNode>, now: Instant): AMapPoint {
        val spec = OsmAndMapper.pinSpec(pin, nodes.firstOrNull { it.id == pin.fromNodeId }, now)
        return spec.toAMapPoint(PINS_LAYER_ID, mapOf(AMapPoint.POINT_IMAGE_URI_PARAM to PinIconProvider.uriFor(appContext, spec.color)))
    }

    private fun MapPointSpec.toAMapPoint(layerId: String = LAYER_ID, extra: Map<String, String> = emptyMap()): AMapPoint {
        val params = HashMap(extra)
        if (stale) params[AMapPoint.POINT_STALE_LOC_PARAM] = "true"
        return AMapPoint(
            id, shortName, fullName, typeName, layerId, color,
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

        const val WIDGET_ID = "meshand_team"
        const val WIDGET_ORDER = 100

        /** OsmAnd built-in drawable (OsmAnd/res/drawable/ic_action_group2.xml, present in 5.4). */
        const val TEAM_ICON = "ic_action_group2"

        const val PINS_LAYER_ID = "meshand_pins"
        const val PINS_LAYER_NAME = "MeshAnd pins"

        /** Just under the team layer, so a teammate standing on a pin stays tappable. */
        const val PINS_LAYER_Z_ORDER = 5.4f

        const val MENU_BUTTONS_ID = "meshand_point_buttons"
        const val PIN_BUTTONS_ID = "meshand_pin_buttons"
        const val BUTTON_REMOVE_PIN = 3
        const val BUTTON_TRAIL = 1
        const val BUTTON_NAVIGATE = 2
        private const val NO_CALLBACK = -1L

        /** OsmAnd built-in drawables (present in 5.4). */
        const val TRAIL_ICON = "ic_action_polygom_dark"
        const val NAVIGATE_ICON = "ic_action_gdirections_dark"
        const val REMOVE_ICON = "ic_action_delete_dark"

        /** Trail tracks are written to OsmAnd's tracks folder under this name prefix. */
        const val TRAIL_FILE_PREFIX = "MeshAnd trail - "

        /** Current and earlier prefixes, so leftover files from older builds are cleaned up too. */
        val TRAIL_FILE_PREFIXES = listOf(TRAIL_FILE_PREFIX, "meshand-trail-")
        val TRAIL_FOLLOW_UP_DELAY = 3.seconds

        /** OsmAnd routing profile key used for "Navigate" to a teammate. */
        const val NAVIGATION_PROFILE = "pedestrian"

        val MIN_PUSH_INTERVAL = 1.seconds
        val REFRESH_INTERVAL = 30.seconds
    }
}
