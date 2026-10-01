package com.meshand.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshand.app.data.meshtastic.MeshtasticClient
import com.meshand.app.data.osmand.OsmAndBridge
import com.meshand.app.data.repository.NodeRepository
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.DiscoveredRadio
import com.meshand.app.domain.model.MeshNode
import com.meshand.app.domain.model.OsmAndStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EnvironmentState(
    val permissions: Map<String, Boolean> = emptyMap(),
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
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val client = MeshtasticClient(application)
    private val repository = NodeRepository(client, viewModelScope)
    private val osmAnd = OsmAndBridge(application)

    private val environment = MutableStateFlow(EnvironmentState())
    private val filterByService = MutableStateFlow(true)

    val uiState: StateFlow<UiState> = combine(
        combine(environment, client.status, client.radios, repository.nodes, filterByService) { env, status, radios, nodes, filter ->
            UiState(env, status, radios, nodes, filter)
        },
        osmAnd.status,
    ) { state, osmAndStatus ->
        state.copy(osmAnd = osmAndStatus)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        viewModelScope.launch {
            repository.nodes.collect(osmAnd::updateNodes)
        }
    }

    /** Re-read permission / adapter state; called on resume and after the permission dialog. */
    fun refreshEnvironment() {
        val app = getApplication<Application>()
        environment.value = EnvironmentState(
            permissions = BluetoothPermissions.status(app),
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
        if (enabled) osmAnd.enable() else osmAnd.disable()
    }

    fun showOnOsmAnd(node: MeshNode) = osmAnd.showOnMap(node)

    override fun onCleared() {
        osmAnd.close()
        client.close()
    }
}
