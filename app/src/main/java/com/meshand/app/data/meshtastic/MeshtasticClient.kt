package com.meshand.app.data.meshtastic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.juul.kable.Peripheral
import com.juul.kable.PlatformAdvertisement
import com.juul.kable.Scanner
import com.meshand.app.data.settings.AppSettings
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.DiscoveredRadio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.LogLevel
import org.meshtastic.sdk.LogSink
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.transport.ble.BleConstants
import org.meshtastic.sdk.transport.ble.BleTransport
import kotlin.time.Duration.Companion.seconds

private const val TAG = "MeshAnd"
private const val SDK_TAG = "MeshAnd/SDK"

/**
 * Owns the BLE side of the app: scanning for Meshtastic radios, OS bonding, and keeping one radio
 * connected through Meshtastic SDK [RadioClient] sessions (with automatic reconnect). Exposes
 * app-level state ([ConnectionStatus], [DiscoveredRadio], [activeRadio]) plus the current [session]
 * for [com.meshand.app.data.repository.NodeRepository]. Lives for the whole process (see MeshAndApp).
 *
 * Callers must hold the Bluetooth runtime permissions before calling [startScan]/[connect]; the UI
 * gates both on [com.meshand.app.MainActivity]'s permission check.
 */
@SuppressLint("MissingPermission")
class MeshtasticClient(context: Context, private val settings: AppSettings) {
    /** All state mutations happen on the main thread; SDK/BLE work runs on their own dispatchers. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _radios = MutableStateFlow<List<DiscoveredRadio>>(emptyList())
    val radios: StateFlow<List<DiscoveredRadio>> = _radios.asStateFlow()

    /** The SDK client for the current connection attempt/session, or null when idle. */
    private val _session = MutableStateFlow<RadioClient?>(null)
    val session: StateFlow<RadioClient?> = _session.asStateFlow()

    /** One in-memory NodeDB store for the app's lifetime (keyed per radio by the SDK). */
    private val storage = InMemoryStorageProvider()

    private var scanJob: Job? = null
    private var connectJob: Job? = null

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    // ── Scanning ───────────────────────────────────────────────────────────

    /**
     * Scans for [SCAN_DURATION]. With [filterByService] (default) only devices advertising the
     * Meshtastic service UUID are reported; disable it to debug radios that don't advertise it.
     */
    fun startScan(filterByService: Boolean = true) {
        if (scanJob?.isActive == true || _activeRadio.value != null) return
        if (!isBluetoothEnabled()) {
            _status.value = ConnectionStatus.Error("Bluetooth is off")
            return
        }
        _radios.value = emptyList()
        _status.value = ConnectionStatus.Scanning
        Log.i(TAG, "BLE scan started (filterByService=$filterByService, ${SCAN_DURATION})")

        val scanner = Scanner {
            if (filterByService) {
                filters { match { services = listOf(BleConstants.MESH_SERVICE_UUID) } }
            }
        }
        scanJob = scope.launch {
            try {
                withTimeoutOrNull(SCAN_DURATION) {
                    scanner.advertisements.collect(::onAdvertisement)
                }
                Log.i(TAG, "BLE scan stopped (timeout), found ${_radios.value.size} radio(s)")
            } catch (e: CancellationException) {
                Log.i(TAG, "BLE scan stopped (cancelled), found ${_radios.value.size} radio(s)")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "BLE scan failed", e)
                _status.value = ConnectionStatus.Error("Scan failed: ${e.message ?: e::class.simpleName}")
                return@launch
            } finally {
                if (_status.value == ConnectionStatus.Scanning) _status.value = ConnectionStatus.Disconnected
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    private fun onAdvertisement(ad: PlatformAdvertisement) {
        val radio = DiscoveredRadio(
            name = ad.name ?: ad.peripheralName,
            address = ad.address,
            rssi = ad.rssi,
            bonded = ad.bondState == PlatformAdvertisement.BondState.Bonded,
        )
        _radios.update { current ->
            val existing = current.indexOfFirst { it.address == radio.address }
            if (existing < 0) {
                Log.i(TAG, "Discovered radio: name=${radio.name} address=${radio.address} rssi=${radio.rssi} bonded=${radio.bonded}")
                current + radio
            } else {
                current.toMutableList().also { it[existing] = radio }
            }
        }
    }

    // ── Connection ─────────────────────────────────────────────────────────

    /** The radio the user wants connected, or null when idle. Drives the foreground service. */
    private val _activeRadio = MutableStateFlow<DiscoveredRadio?>(null)
    val activeRadio: StateFlow<DiscoveredRadio?> = _activeRadio.asStateFlow()

    /** The radio from the last session (persisted), for reconnecting without a scan. */
    val savedRadio: DiscoveredRadio? get() = settings.savedRadio

    /** On app start: reconnect to the saved radio unless the user explicitly disconnected. */
    fun connectSavedIfWanted() {
        val radio = settings.savedRadio ?: return
        if (!settings.autoConnect || _activeRadio.value != null) return
        Log.i(TAG, "Auto-connecting to saved radio ${radio.address}")
        connect(radio)
    }

    fun connect(radio: DiscoveredRadio) {
        if (_activeRadio.value?.address == radio.address && connectJob?.isActive == true) {
            Log.w(TAG, "Connect ignored: already connecting/connected to ${radio.address}")
            return
        }
        stopScan()
        settings.savedRadio = radio
        settings.autoConnect = true
        val previous = connectJob
        _activeRadio.value = radio
        connectJob = scope.launch {
            previous?.cancelAndJoin()
            connectLoop(radio)
        }
    }

    fun disconnect() {
        Log.i(TAG, "Disconnect requested")
        settings.autoConnect = false
        _activeRadio.value = null
        val job = connectJob
        connectJob = null
        scope.launch {
            job?.cancelAndJoin() // runs session teardown (non-cancellable) before returning
            _status.value = ConnectionStatus.Disconnected
        }
    }

    /**
     * Keeps [radio] connected until [disconnect]. The SDK auto-reconnects short link drops itself;
     * this loop covers the initial connection (e.g. radio off or out of range at app start) and
     * sessions the SDK gives up on, retrying with backoff.
     */
    private suspend fun connectLoop(radio: DiscoveredRadio) {
        val device = try {
            adapter?.getRemoteDevice(radio.address) ?: error("Bluetooth unavailable")
        } catch (e: Exception) {
            fail("Invalid device: ${e.message}", e)
            _activeRadio.value = null
            return
        }
        Log.i(TAG, "Connection attempt: ${radio.name} ${radio.address}")
        _status.value = ConnectionStatus.Connecting(radio, "Pairing (check the radio's screen for a PIN)")
        ensureBonded(device)

        var everConnected = false
        var failures = 0
        while (true) {
            val outcome = runSession(radio, device, reconnecting = everConnected)
            if (outcome.connected) {
                everConnected = true
                failures = 0
            }
            failures++
            val backoff = (RETRY_BASE * (1 shl (failures - 1).coerceAtMost(4))).coerceAtMost(RETRY_MAX)
            Log.w(TAG, "Session for ${radio.address} ended: ${outcome.reason}; retrying in $backoff")
            val detail = "${outcome.reason}. Retrying in ${backoff.inWholeSeconds} s"
            _status.value = if (everConnected) {
                ConnectionStatus.Reconnecting(radio, detail)
            } else {
                ConnectionStatus.Connecting(radio, detail)
            }
            delay(backoff)
        }
    }

    private class SessionOutcome(val connected: Boolean, val reason: String)

    /** One SDK session: connect, then stay until the SDK reports the link is gone for good. */
    private suspend fun runSession(radio: DiscoveredRadio, device: BluetoothDevice, reconnecting: Boolean): SessionOutcome {
        if (!reconnecting) _status.value = ConnectionStatus.Connecting(radio, "Opening BLE connection")
        val bleTransport = BleTransport(Peripheral(device), address = radio.address)
        val client = RadioClient.Builder()
            .transport(bleTransport)
            .storage(storage) // shared across sessions, so a reconnect starts from the known NodeDB
            .logger(LogcatLogSink)
            // Read-only toward the radio: don't push the phone's clock on connect.
            .autoSyncTimeOnConnect(false)
            // Short link drops (walked out of range, radio rebooted) are retried by the SDK.
            .autoReconnect(enabled = true, initialBackoff = 2.seconds, maxBackoff = 30.seconds)
            .build()
        Log.i(TAG, "Meshtastic SDK RadioClient initialized for ${radio.address}")
        _session.value = client
        val stateJob = scope.launch { trackConnectionState(client, radio, reconnecting) }
        try {
            client.connect() // suspends until the NodeDB handshake (stage 2) completes
            Log.i(TAG, "Connection success: ${radio.address}, own node=${client.ownNode.value?.num?.let { "!%08x".format(MeshtasticMapper.nodeNumToLong(it)) }}")
            client.connection.first { it is ConnectionState.Disconnected }
            return SessionOutcome(connected = true, reason = "Radio link lost")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${radio.address}", e)
            return SessionOutcome(connected = false, reason = "Connection failed: ${e.message ?: e::class.simpleName}")
        } finally {
            stateJob.cancel()
            withContext(NonCancellable) {
                _session.value = null
                client.disconnect() // never throws; closes transport + storage handle
                bleTransport.shutdown()
                Log.i(TAG, "Session closed: ${radio.address}")
            }
        }
    }

    private suspend fun trackConnectionState(client: RadioClient, radio: DiscoveredRadio, reconnecting: Boolean) {
        var wasConnected = reconnecting
        client.connection.collect { state ->
            Log.d(TAG, "SDK connection state: $state")
            fun progress(detail: String) {
                _status.value = if (wasConnected) {
                    ConnectionStatus.Reconnecting(radio, detail)
                } else {
                    ConnectionStatus.Connecting(radio, detail)
                }
            }
            when (state) {
                is ConnectionState.Connecting -> progress("BLE connect (attempt ${state.attempt})")
                is ConnectionState.Configuring ->
                    progress("Meshtastic handshake ${state.phase} (${(state.progress * 100).toInt()}%)")
                is ConnectionState.Reconnecting -> {
                    Log.w(TAG, "Link lost, SDK reconnecting (attempt ${state.attempt}): ${state.cause.message}")
                    wasConnected = true
                    progress("Link lost, reconnecting (attempt ${state.attempt})")
                }
                ConnectionState.Connected -> {
                    wasConnected = true
                    _status.value = ConnectionStatus.Connected(radio)
                }
                ConnectionState.Disconnected -> Unit // handled by runSession / connectLoop
            }
        }
    }

    private fun fail(message: String, cause: Throwable?) {
        Log.e(TAG, message, cause)
        _status.value = ConnectionStatus.Error(message)
    }

    // ── Bonding ────────────────────────────────────────────────────────────

    /**
     * Meshtastic radios (unless set to NO_PIN) protect their GATT characteristics with an
     * encrypted/authenticated link, so the phone must be bonded first. A radio with a screen
     * (T-Beam Supreme) shows a random 6-digit PIN that the user types into Android's dialog.
     *
     * If bonding fails we still try to connect: a NO_PIN radio works without a bond, and the
     * SDK will surface a clear GATT error otherwise.
     */
    private suspend fun ensureBonded(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "Already bonded with ${device.address}")
            return
        }
        Log.i(TAG, "Bonding with ${device.address} (enter the PIN shown on the radio)")
        val bondStates = callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    @Suppress("DEPRECATION")
                    val d: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (d?.address == device.address) {
                        trySend(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE))
                    }
                }
            }
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            if (!device.createBond()) {
                Log.w(TAG, "createBond() returned false for ${device.address}")
                trySend(device.bondState.takeIf { it == BluetoothDevice.BOND_BONDED } ?: BluetoothDevice.BOND_NONE)
            }
            awaitClose { appContext.unregisterReceiver(receiver) }
        }
        val result = withTimeoutOrNull(BOND_TIMEOUT) {
            bondStates.first { it == BluetoothDevice.BOND_BONDED || it == BluetoothDevice.BOND_NONE }
        }
        when (result) {
            BluetoothDevice.BOND_BONDED -> Log.i(TAG, "Bonded with ${device.address}")
            null -> Log.w(TAG, "Bonding timed out after $BOND_TIMEOUT; trying to connect anyway")
            else -> Log.w(TAG, "Bonding failed or was cancelled; trying to connect anyway (works only if radio uses NO_PIN)")
        }
    }

    companion object {
        val SCAN_DURATION = 15.seconds
        val BOND_TIMEOUT = 90.seconds
        val RETRY_BASE = 5.seconds
        val RETRY_MAX = 60.seconds
    }
}

/**
 * Forwards SDK logs to Logcat. Protocol payload logging stays at the SDK default (NONE), so
 * packet contents, channel PSKs, and admin keys are never emitted.
 */
private object LogcatLogSink : LogSink {
    override fun log(level: LogLevel, tag: String, message: String, cause: Throwable?) {
        val msg = "[$tag] $message"
        when (level) {
            LogLevel.NONE -> Unit
            LogLevel.VERBOSE -> Log.v(SDK_TAG, msg, cause)
            LogLevel.DEBUG -> Log.d(SDK_TAG, msg, cause)
            LogLevel.INFO -> Log.i(SDK_TAG, msg, cause)
            LogLevel.WARN -> Log.w(SDK_TAG, msg, cause)
            LogLevel.ERROR -> Log.e(SDK_TAG, msg, cause)
        }
    }
}
