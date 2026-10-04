package com.meshand.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.OsmAndStatus
import com.meshand.app.ui.common.UpdateBanner
import com.meshand.app.ui.connection.ConnectionScreen
import com.meshand.app.ui.nodes.NodesPage
import com.meshand.app.ui.nodes.PinsPage
import com.meshand.app.ui.nodes.StatusPage
import com.meshand.app.ui.settings.SettingsScreen
import com.meshand.app.ui.theme.MeshAndTheme
import com.meshand.app.ui.team.TeamActivity

@OptIn(ExperimentalMaterial3Api::class)
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
            MeshAndTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val update by applicationContext.graph.updates.available.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableStateOf(Tab.Radio) }
                // Back from another page returns to Radio before leaving the app.
                BackHandler(enabled = tab != Tab.Radio) { tab = Tab.Radio }
                val status = state.status
                val connected = status is ConnectionStatus.Connected || status is ConnectionStatus.Reconnecting
                val osmAndShowing = state.osmAnd is OsmAndStatus.Showing
                val openTeam = { startActivity(Intent(this@MainActivity, TeamActivity::class.java)) }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text(if (tab == Tab.Radio) "MeshAnd" else tab.label) },
                            actions = {
                                if (connected) {
                                    IconButton(onClick = openTeam) {
                                        Icon(painterResource(R.drawable.ic_group), contentDescription = "Team list")
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        )
                    },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    label = { Text(t.label) },
                                    icon = {
                                        val count = if (t == Tab.Pins) state.pins.size else 0
                                        BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) {
                                            Icon(painterResource(t.icon), contentDescription = null)
                                        }
                                    },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        update?.let {
                            UpdateBanner(
                                update = it,
                                onDownload = { openUrl(it.pageUrl) },
                                onLater = applicationContext.graph.updates::dismiss,
                            )
                        }
                        when (tab) {
                            Tab.Radio -> if (connected) {
                                StatusPage(
                                    status = status,
                                    nodes = state.nodes,
                                    osmAnd = state.osmAnd,
                                    pinCount = state.pins.size,
                                    onDisconnect = viewModel::disconnect,
                                    onOsmAndEnabledChange = viewModel::setOsmAndEnabled,
                                    onOpenTeam = openTeam,
                                    onOpenNodes = { tab = Tab.Nodes },
                                    onOpenPins = { tab = Tab.Pins },
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
                            Tab.Nodes -> NodesPage(
                                nodes = state.nodes,
                                hiddenNodeCount = state.hiddenNodeCount,
                                osmAndShowing = osmAndShowing,
                                onShowOnOsmAnd = viewModel::showOnOsmAnd,
                            )
                            Tab.Pins -> PinsPage(
                                pins = state.pins,
                                nodes = state.nodes,
                                osmAndShowing = osmAndShowing,
                                onShow = viewModel::showPin,
                                onRemove = viewModel::removePin,
                                onClear = viewModel::clearPins,
                            )
                            Tab.Settings -> SettingsScreen(
                                themeMode = state.themeMode,
                                onThemeModeChange = viewModel::setThemeMode,
                                showOwnRadioOnMap = state.showOwnRadioOnMap,
                                onShowOwnRadioChange = viewModel::setShowOwnRadioOnMap,
                                silenceAlertMinutes = state.silenceAlertMinutes,
                                onSilenceAlertMinutesChange = viewModel::setSilenceAlertMinutes,
                                trailMinutes = state.trailMinutes,
                                onTrailMinutesChange = viewModel::setTrailMinutes,
                                onResetTrails = viewModel::resetAllTrails,
                                savedDataBytes = state.savedDataBytes,
                                onClearSavedData = viewModel::clearSavedData,
                                checkForUpdates = state.checkForUpdates,
                                onCheckForUpdatesChange = viewModel::setCheckForUpdates,
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

/** Bottom-bar pages of the main screen. */
private enum class Tab(val label: String, @param:DrawableRes val icon: Int) {
    Radio("Radio", R.drawable.ic_stat_mesh),
    Nodes("Nodes", R.drawable.ic_list),
    Pins("Pins", R.drawable.ic_pin),
    Settings("Settings", R.drawable.ic_settings),
}
