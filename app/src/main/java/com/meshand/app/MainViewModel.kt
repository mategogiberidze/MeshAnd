package com.meshand.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshand.app.data.settings.ThemeMode
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.DiscoveredRadio
import com.meshand.app.domain.model.MeshNode
import com.meshand.app.domain.model.OsmAndStatus
import com.meshand.app.domain.model.Pin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class EnvironmentState(
    val permissions: Map<String, Boolean> = emptyMap(),
    /** Nice to have, not required (notification permission on Android 13+). */
    val optionalPermissions: Map<String, Boolean> = emptyMap(),
    val bluetoothOn: Boolean = false,
    /** Null when the Android version doesn't need Location on for BLE scans. */
    val locationServicesOn: Boolean? = null,
) {
    val allPermissionsGranted: Boolean get() = permissions.isNotEmpty() && permissions.values.all { it }
}

data class UiState(
    val environment: EnvironmentState = EnvironmentState(),
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val radios: List<DiscoveredRadio> = emptyList(),
    val nodes: List<MeshNode> = emptyList(),
    val filterByService: Boolean = true,
    val osmAnd: OsmAndStatus = OsmAndStatus.Off,
    /** Last radio, offered for one-tap reconnect while not connected. */
    val savedRadio: DiscoveredRadio? = null,
    /** Nodes known but not heard within 24 h (hidden from every list and the map). */
    val hiddenNodeCount: Int = 0,
    /** "Teammate not heard" alert threshold; 0 = off. */
    val silenceAlertMinutes: Int = 0,
    /** How much history each trail keeps. */
    val trailMinutes: Int = 60,
    /** Whether our own radio is drawn on the OsmAnd map. */
    val showOwnRadioOnMap: Boolean = false,
    /** Ask GitHub for new MeshAnd versions when the app opens. */
    val checkForUpdates: Boolean = true,
    /** Pins shared over the mesh, newest first. */
    val pins: List<Pin> = emptyList(),
    /** Size of the saved trails and pins on this phone. */
    val savedDataBytes: Long = 0,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

private data class Extras(
    val all: List<MeshNode>,
    val checkForUpdates: Boolean,
    val pins: List<Pin>,
    val savedBytes: Long,
    val themeMode: ThemeMode,
)

/**
 * Screen state only. The radio connection, node list and OsmAnd bridge live in [AppGraph]
 * (process-wide, kept alive by the foreground service), so they survive this ViewModel.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph
    private val client = graph.client
    private val repository = graph.repository
    private val osmAnd = graph.osmAnd

    private val environment = MutableStateFlow(EnvironmentState())
    private val filterByService = MutableStateFlow(true)

    val uiState: StateFlow<UiState> = combine(
        combine(environment, client.status, client.radios, repository.activeNodes, filterByService) { env, status, radios, nodes, filter ->
            UiState(env, status, radios, nodes, filter)
        },
        osmAnd.status,
        client.activeRadio,
        // All known nodes (for the hidden count), the update-check switch and pins.
        combine(repository.nodes, graph.settings.checkForUpdates, graph.pins.pins, graph.store.sizeBytes, graph.settings.themeMode, ::Extras),
        combine(graph.settings.silenceAlertMinutes, graph.settings.trailMinutes, graph.settings.showOwnRadioOnMap, ::Triple),
    ) { state, osmAndStatus, activeRadio, (all, checkForUpdates, pins, savedBytes, themeMode), (silenceMinutes, trailMinutes, showOwnRadio) ->
        val allCount = all.size
        state.copy(
            checkForUpdates = checkForUpdates,
            pins = pins,
            savedDataBytes = savedBytes,
            themeMode = themeMode,
            trailMinutes = trailMinutes,
            showOwnRadioOnMap = showOwnRadio,
            osmAnd = osmAndStatus,
            hiddenNodeCount = allCount - state.nodes.size,
            silenceAlertMinutes = silenceMinutes,
            // Offer a one-tap reconnect to the last radio when idle.
            savedRadio = if (activeRadio == null) client.savedRadio else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** On app start: reconnect to the last radio unless the user disconnected it explicitly. */
    fun autoConnect() {
        refreshEnvironment()
        if (environment.value.allPermissionsGranted && client.isBluetoothEnabled()) client.connectSavedIfWanted()
    }

    /** Re-read permission / adapter state; called on resume and after the permission dialog. */
    fun refreshEnvironment() {
        val app = getApplication<Application>()
        environment.value = EnvironmentState(
            permissions = BluetoothPermissions.status(app),
            optionalPermissions = BluetoothPermissions.optionalStatus(app),
            bluetoothOn = client.isBluetoothEnabled(),
            locationServicesOn = if (BluetoothPermissions.needsLocationServices) {
                BluetoothPermissions.locationServicesEnabled(app)
            } else {
                null
            },
        )
    }

    fun setFilterByService(enabled: Boolean) {
        filterByService.value = enabled
    }

    fun startScan() {
        refreshEnvironment()
        if (environment.value.allPermissionsGranted) client.startScan(filterByService.value)
    }

    fun stopScan() = client.stopScan()

    fun connect(radio: DiscoveredRadio) {
        refreshEnvironment()
        if (environment.value.allPermissionsGranted) client.connect(radio)
    }

    fun disconnect() = client.disconnect()

    fun setOsmAndEnabled(enabled: Boolean) {
        graph.settings.osmAndEnabled = enabled
        if (enabled) osmAnd.enable() else osmAnd.disable()
    }

    fun showOnOsmAnd(node: MeshNode) = osmAnd.showOnMap(node)

    fun setSilenceAlertMinutes(minutes: Int) = graph.settings.setSilenceAlertMinutes(minutes)

    fun setTrailMinutes(minutes: Int) = graph.settings.setTrailMinutes(minutes)

    fun resetAllTrails() = graph.trails.reset(null)

    fun setShowOwnRadioOnMap(show: Boolean) = graph.settings.setShowOwnRadioOnMap(show)

    fun showPin(pin: Pin) = osmAnd.showPin(pin)

    fun removePin(pin: Pin) = graph.pins.remove(pin.id)

    fun clearPins() = graph.pins.clear()

    /** Deletes saved trails and pins; trails restart from everyone's current position. */
    fun clearSavedData() {
        graph.trails.clearSaved()
        graph.pins.clear()
    }

    fun setThemeMode(mode: ThemeMode) = graph.settings.setThemeMode(mode)

    fun setCheckForUpdates(enabled: Boolean) = graph.settings.setCheckForUpdates(enabled)
}
