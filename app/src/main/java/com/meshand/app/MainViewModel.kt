package com.meshand.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.DiscoveredRadio
import com.meshand.app.domain.model.MeshNode
import com.meshand.app.domain.model.OsmAndStatus
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
        repository.nodes,
        graph.settings.silenceAlertMinutes,
    ) { state, osmAndStatus, activeRadio, allNodes, silenceMinutes ->
        state.copy(
            osmAnd = osmAndStatus,
            hiddenNodeCount = allNodes.size - state.nodes.size,
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
}
