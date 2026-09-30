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
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.DiscoveredRadio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
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
 * Owns the BLE side of the app: scanning for Meshtastic radios, OS bonding, and the lifecycle of
 * one Meshtastic SDK [RadioClient]. Exposes app-level state ([ConnectionStatus],
 * [DiscoveredRadio]) plus the active [session] for [com.meshand.app.data.repository.NodeRepository].
 *
 * Callers must hold the Bluetooth runtime permissions before calling [startScan]/[connect]; the UI
 * gates both on [com.meshand.app.MainActivity]'s permission check.
 */
@SuppressLint("MissingPermission")
class MeshtasticClient(context: Context) {
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

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var stateJob: Job? = null
    private var transport: BleTransport? = null

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    // ── Scanning ───────────────────────────────────────────────────────────

    /**
     * Scans for [SCAN_DURATION]. With [filterByService] (default) only devices advertising the
     * Meshtastic service UUID are reported; disable it to debug radios that don't advertise it.
     */
    fun startScan(filterByService: Boolean = true) {
        if (scanJob?.isActive == true || _session.value != null) return
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

    fun connect(radio: DiscoveredRadio) {
        if (connectJob?.isActive == true || _session.value != null) {
            Log.w(TAG, "Connect ignored: a connection is already active")
            return
        }
        stopScan()
        connectJob = scope.launch { doConnect(radio) }
    }

    private suspend fun doConnect(radio: DiscoveredRadio) {
        Log.i(TAG, "Connection attempt: ${radio.name} ${radio.address}")
        val device = try {
            adapter?.getRemoteDevice(radio.address) ?: error("Bluetooth unavailable")
        } catch (e: Exception) {
            fail("Invalid device: ${e.message}", e)
            return
        }

        _status.value = ConnectionStatus.Connecting(radio, "Pairing (check the radio's screen for a PIN)")
        ensureBonded(device)

        _status.value = ConnectionStatus.Connecting(radio, "Opening BLE connection")
        val bleTransport = BleTransport(Peripheral(device), address = radio.address)
        val client = RadioClient.Builder()
            .transport(bleTransport)
            .storage(InMemoryStorageProvider())
            .logger(LogcatLogSink)
            // Phase 1 is read-only: don't push the phone's clock to the radio on connect.
            .autoSyncTimeOnConnect(false)
            .build()
        Log.i(TAG, "Meshtastic SDK RadioClient initialized for ${radio.address}")
        transport = bleTransport
        _session.value = client
        stateJob = scope.launch { trackConnectionState(client, radio) }

        try {
            client.connect() // suspends until the NodeDB handshake (stage 2) completes
            Log.i(TAG, "Connection success: ${radio.address}, own node=${client.ownNode.value?.num?.let { MeshtasticMapper.nodeNumToLong(it) }?.let { "!%08x".format(it) }}")
        } catch (e: CancellationException) {
            teardown()
            throw e
        } catch (e: Exception) {
            teardown()
            fail("Connection failed: ${e.message ?: e::class.simpleName}", e)
        }
    }

    private suspend fun trackConnectionState(client: RadioClient, radio: DiscoveredRadio) {
        var started = false
        client.connection.collect { state ->
            Log.d(TAG, "SDK connection state: $state")
            when (state) {
                is ConnectionState.Connecting -> {
                    started = true
                    _status.value = ConnectionStatus.Connecting(radio, "BLE connect (attempt ${state.attempt})")
                }
                is ConnectionState.Configuring -> {
                    started = true
                    val pct = (state.progress * 100).toInt()
                    _status.value = ConnectionStatus.Connecting(radio, "Meshtastic handshake ${state.phase} ($pct%)")
                }
                is ConnectionState.Reconnecting -> {
                    started = true
                    _status.value = ConnectionStatus.Connecting(radio, "Reconnecting (attempt ${state.attempt})")
                }
                ConnectionState.Connected -> {
                    started = true
                    _status.value = ConnectionStatus.Connected(radio)
                }
                ConnectionState.Disconnected -> {
                    // The initial value is Disconnected before connect() starts; ignore it.
                    if (started && _status.value is ConnectionStatus.Connected) {
                        Log.w(TAG, "Disconnected by radio/link: ${radio.address}")
                        _status.value = ConnectionStatus.Error("Radio disconnected")
                        scope.launch { teardown() }
                    }
                }
            }
        }
    }

    fun disconnect() {
        scope.launch {
            Log.i(TAG, "Disconnect requested")
            connectJob?.cancel()
            teardown()
            _status.value = ConnectionStatus.Disconnected
        }
    }

    /** Runs to completion even if the calling coroutine was cancelled. */
    private suspend fun teardown() = withContext(NonCancellable) {
        stateJob?.cancel()
        stateJob = null
        val client = _session.value
        _session.value = null
        if (client != null) {
            client.disconnect() // never throws; closes transport + storage
            Log.i(TAG, "Disconnected")
        }
        transport?.shutdown()
        transport = null
    }

    /** Called when the owning ViewModel is cleared. */
    fun close() {
        stopScan()
        connectJob?.cancel()
        scope.launch {
            teardown()
            scope.cancel()
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
