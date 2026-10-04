package com.meshand.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.ui.common.UpdateBanner
import com.meshand.app.ui.connection.ConnectionScreen
import com.meshand.app.ui.nodes.NodesScreen
import com.meshand.app.ui.team.TeamActivity

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.refreshEnvironment()
            viewModel.autoConnect()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) viewModel.autoConnect()
        setContent {
            MaterialTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val update by applicationContext.graph.updates.available.collectAsStateWithLifecycle()
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        update?.let {
                            UpdateBanner(
                                update = it,
                                onDownload = { openUrl(it.pageUrl) },
                                onLater = applicationContext.graph.updates::dismiss,
                            )
                        }
                        val status = state.status
                        if (status is ConnectionStatus.Connected || status is ConnectionStatus.Reconnecting) {
                            NodesScreen(
                                status = status,
                                nodes = state.nodes,
                                hiddenNodeCount = state.hiddenNodeCount,
                                silenceAlertMinutes = state.silenceAlertMinutes,
                                onSilenceAlertMinutesChange = viewModel::setSilenceAlertMinutes,
                                trailMinutes = state.trailMinutes,
                                onTrailMinutesChange = viewModel::setTrailMinutes,
                                onResetTrails = viewModel::resetAllTrails,
                                showOwnRadioOnMap = state.showOwnRadioOnMap,
                                onShowOwnRadioChange = viewModel::setShowOwnRadioOnMap,
                                pins = state.pins,
                                onShowPin = viewModel::showPin,
                                onRemovePin = viewModel::removePin,
                                onClearPins = viewModel::clearPins,
                                savedDataBytes = state.savedDataBytes,
                                onClearSavedData = viewModel::clearSavedData,
                                checkForUpdates = state.checkForUpdates,
                                onCheckForUpdatesChange = viewModel::setCheckForUpdates,
                                osmAnd = state.osmAnd,
                                onDisconnect = viewModel::disconnect,
                                onOsmAndEnabledChange = viewModel::setOsmAndEnabled,
                                onShowOnOsmAnd = viewModel::showOnOsmAnd,
                                onOpenTeam = { startActivity(Intent(this@MainActivity, TeamActivity::class.java)) },
                            )
                        } else {
                            ConnectionScreen(
                                state = state,
                                onRequestPermissions = {
                                    permissionLauncher.launch(
                                        (BluetoothPermissions.required + BluetoothPermissions.optional).toTypedArray(),
                                    )
                                },
                                onStartScan = viewModel::startScan,
                                onStopScan = viewModel::stopScan,
                                onFilterChange = viewModel::setFilterByService,
                                onConnect = viewModel::connect,
                                onCancel = viewModel::disconnect,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshEnvironment()
        applicationContext.graph.updates.checkIfDue()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }
}
