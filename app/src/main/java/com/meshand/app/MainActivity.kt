package com.meshand.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshand.app.domain.ChatRules
import com.meshand.app.domain.model.ChatThread
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.OsmAndStatus
import com.meshand.app.domain.model.PhoneGpsStatus
import com.meshand.app.service.MeshConnectionService
import com.meshand.app.ui.chat.ChatListPage
import com.meshand.app.ui.chat.ConversationPage
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

    /** Conversation to open, from a message notification or the team list. */
    private var pendingChat by mutableStateOf<String?>(null)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.refreshEnvironment()
            viewModel.autoConnect()
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            viewModel.refreshEnvironment()
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                viewModel.setUsePhoneGps(true)
            } else {
                Toast.makeText(
                    this,
                    "Phone GPS needs precise location. Allow it in Android Settings → Apps → MeshAnd → Permissions.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    /** Switching phone GPS on asks for the location permission first, if needed. */
    private fun setUsePhoneGps(enabled: Boolean) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!enabled || granted) {
            viewModel.setUsePhoneGps(enabled)
        } else {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            viewModel.autoConnect()
            pendingChat = intent.getStringExtra(EXTRA_OPEN_CHAT)
        }
        setContent {
            MeshAndTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val update by applicationContext.graph.updates.available.collectAsStateWithLifecycle()
                val radioInfo by applicationContext.graph.radioInfo.info.collectAsStateWithLifecycle()
                val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
                val chatChannels by viewModel.chatChannels.collectAsStateWithLifecycle()
                val chatUnread by viewModel.chatUnread.collectAsStateWithLifecycle()
                val allNodes by viewModel.allNodes.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableStateOf(Tab.Radio) }
                // The open conversation on the Chat tab (ChatThread.key), or null for the list.
                var chatKey by rememberSaveable { mutableStateOf<String?>(null) }
                val chatThread = chatKey?.let(ChatThread::fromKey)
                val inConversation = tab == Tab.Chat && chatThread != null
                var confirmDelete by remember { mutableStateOf(false) }
                LaunchedEffect(pendingChat) {
                    pendingChat?.let {
                        chatKey = it
                        tab = Tab.Chat
                        pendingChat = null
                    }
                }
                // Back from another page returns to Radio before leaving the app; in a conversation, to the list.
                BackHandler(enabled = tab != Tab.Radio) { tab = Tab.Radio }
                BackHandler(enabled = inConversation) { chatKey = null }
                val openChat = { thread: ChatThread ->
                    chatKey = thread.key
                    tab = Tab.Chat
                }
                val status = state.status
                val connected = status is ConnectionStatus.Connected || status is ConnectionStatus.Reconnecting
                val osmAndShowing = state.osmAnd is OsmAndStatus.Showing
                val openTeam = { startActivity(Intent(this@MainActivity, TeamActivity::class.java)) }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    when {
                                        chatThread != null && inConversation -> ChatRules.title(chatThread, chatChannels, allNodes)
                                        tab == Tab.Radio -> "MeshAnd"
                                        else -> tab.label
                                    },
                                    maxLines = 1,
                                )
                            },
                            navigationIcon = {
                                if (inConversation) {
                                    IconButton(onClick = { chatKey = null }) {
                                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "All conversations")
                                    }
                                }
                            },
                            actions = {
                                if (inConversation && chatMessages.any { it.thread == chatThread }) {
                                    IconButton(onClick = { confirmDelete = true }) {
                                        Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete conversation")
                                    }
                                } else if (connected) {
                                    IconButton(onClick = openTeam) {
                                        Icon(painterResource(R.drawable.ic_group), contentDescription = "Team list")
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        )
                    },
                    bottomBar = {
                        // A conversation gets the whole height (and the keyboard sits where the bar was).
                        if (!inConversation) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    label = { Text(t.label) },
                                    icon = {
                                        val count = when (t) {
                                            Tab.Pins -> state.pins.size
                                            Tab.Chat -> chatUnread
                                            else -> 0
                                        }
                                        BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) {
                                            Icon(painterResource(t.icon), contentDescription = null)
                                        }
                                    },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
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
                                    phoneGps = state.phoneGps,
                                    radioInfo = radioInfo,
                                    onUsePhoneGpsChange = ::setUsePhoneGps,
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
                                onMessage = { openChat(ChatThread.Direct(it.id)) },
                            )
                            Tab.Chat -> if (chatThread == null) {
                                ChatListPage(
                                    conversations = ChatRules.conversations(chatMessages, chatChannels, allNodes),
                                    onOpen = openChat,
                                )
                            } else {
                                // Each conversation keeps its own draft.
                                key(chatThread.key) {
                                    ConversationPage(
                                        thread = chatThread,
                                        messages = chatMessages.filter { it.thread == chatThread },
                                        nodes = allNodes,
                                        connected = status is ConnectionStatus.Connected,
                                        onVisible = viewModel::setChatOnScreen,
                                        onSend = { viewModel.sendChat(chatThread, it) },
                                    )
                                }
                            }
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
                                usePhoneGps = state.usePhoneGps,
                                onUsePhoneGpsChange = ::setUsePhoneGps,
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
                if (confirmDelete && chatThread != null) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text("Delete this conversation?") },
                        text = { Text("Its messages are deleted from this phone. Others keep theirs.") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmDelete = false
                                viewModel.deleteChat(chatThread)
                                chatKey = null
                            }) { Text("Delete") }
                        },
                        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_CHAT)?.let { pendingChat = it }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshEnvironment()
        applicationContext.graph.updates.checkIfDue()
        // Android refused location in the background; now that we're in front, the service can retry.
        if (applicationContext.graph.phoneGps.status.value == PhoneGpsStatus.NeedsApp) MeshConnectionService.start(this)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        /** Intent extra: a ChatThread.key to open on the Chat tab. */
        const val EXTRA_OPEN_CHAT = "com.meshand.app.OPEN_CHAT"
    }
}

/** Bottom-bar pages of the main screen. */
private enum class Tab(val label: String, @param:DrawableRes val icon: Int) {
    Radio("Radio", R.drawable.ic_stat_mesh),
    Nodes("Nodes", R.drawable.ic_list),
    Pins("Pins", R.drawable.ic_pin),
    Chat("Chat", R.drawable.ic_chat),
    Settings("Settings", R.drawable.ic_settings),
}
