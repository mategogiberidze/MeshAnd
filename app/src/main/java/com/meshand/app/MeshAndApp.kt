package com.meshand.app

import android.app.Application
import android.content.Context
import com.meshand.app.data.alerts.SilenceAlertMonitor
import com.meshand.app.data.meshtastic.MeshtasticClient
import com.meshand.app.data.osmand.OsmAndBridge
import com.meshand.app.data.repository.NodeRepository
import com.meshand.app.data.repository.PinRepository
import com.meshand.app.data.repository.TrailRecorder
import com.meshand.app.data.settings.AppSettings
import com.meshand.app.data.storage.LocalStore
import com.meshand.app.data.update.UpdateChecker
import com.meshand.app.service.MeshConnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the long-lived objects (radio connection, node list, OsmAnd bridge) so they outlive
 * activities. [MeshConnectionService] keeps the process alive while a radio is wanted.
 * Manual wiring on purpose: no DI framework.
 */
class MeshAndApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = AppSettings(app)
    val client = MeshtasticClient(app, settings)
    val repository = NodeRepository(client, scope)
    val osmAnd = OsmAndBridge(app)
    val store = LocalStore(app)
    val trails = TrailRecorder(repository.nodes, settings.trailMinutes, scope, store)
    val updates = UpdateChecker(settings, scope)
    val pins = PinRepository(app, client, repository.nodes, scope, store, osmAnd::findOsmAndPackage)
    private val silenceAlerts = SilenceAlertMonitor(app, settings, repository.activeNodes, client.status)

    init {
        // OsmAnd only gets teammates heard within the last 24 h (plus our own radio).
        scope.launch { repository.activeNodes.collect(osmAnd::updateNodes) }
        scope.launch { trails.trails.collect(osmAnd::updateTrails) }
        scope.launch { settings.showOwnRadioOnMap.collect(osmAnd::setShowOwnRadio) }
        scope.launch { pins.pins.collect(osmAnd::updatePins) }
        osmAnd.onRemovePin = pins::remove
        silenceAlerts.start(scope)
        if (settings.osmAndEnabled) osmAnd.enable()

        // Run the foreground service exactly while the user wants a radio connected.
        // activeRadio only becomes non-null from a foreground action, so starting it is allowed.
        scope.launch {
            client.activeRadio.map { it != null }.distinctUntilChanged().collect { wanted ->
                if (wanted) MeshConnectionService.start(app)
            }
        }
    }
}

val Context.graph: AppGraph get() = (applicationContext as MeshAndApp).graph
