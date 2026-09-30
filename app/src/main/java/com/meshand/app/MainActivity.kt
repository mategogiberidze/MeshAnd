package com.meshand.app

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
import com.meshand.app.ui.connection.ConnectionScreen
import com.meshand.app.ui.nodes.NodesScreen

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.refreshEnvironment()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        val status = state.status
                        if (status is ConnectionStatus.Connected) {
                            NodesScreen(
                                radio = status.radio,
                                nodes = state.nodes,
                                onDisconnect = viewModel::disconnect,
                            )
                        } else {
                            ConnectionScreen(
                                state = state,
                                onRequestPermissions = {
                                    permissionLauncher.launch(BluetoothPermissions.required.toTypedArray())
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
    }
}
