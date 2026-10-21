package com.meshand.app.data.repository

import android.util.Log
import com.meshand.app.data.meshtastic.MeshtasticClient
import com.meshand.app.data.meshtastic.RadioInfoMapper
import com.meshand.app.domain.model.RadioInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import java.time.Instant

private const val TAG = "MeshAnd"

/**
 * The connected radio's own details for the Radio page: identity and firmware from the session,
 * battery and airtime from device telemetry. The radio volunteers its telemetry rarely, so it is
 * also asked every [POLL_MS]; that request is addressed to our own radio and never goes over LoRa.
 */
class RadioInfoRepository(client: MeshtasticClient, scope: CoroutineScope) {
    private val _info = MutableStateFlow<RadioInfo?>(null)

    /** Null until connected; kept through reconnects, cleared on Disconnect. */
    val info: StateFlow<RadioInfo?> = _info.asStateFlow()

    init {
        scope.launch {
            client.activeRadio.collect { if (it == null) _info.value = null }
        }
        scope.launch {
            client.session.collectLatest { session -> if (session != null) observe(session) }
        }
    }

    private suspend fun observe(session: RadioClient) = coroutineScope {
        val metrics = MutableStateFlow<Pair<DeviceMetrics, Instant>?>(null)
        // Telemetry our radio sends to the phone on its own (and the replies to our requests).
        launch {
            session.ownNode.filterNotNull().map { it.num }.distinctUntilChanged().collectLatest { num ->
                session.telemetry.observe(NodeId(num)).collect { telemetry ->
                    telemetry.device_metrics?.let { metrics.value = it to Instant.now() }
                }
            }
        }
        launch {
            session.connection.first { it is ConnectionState.Connected }
            while (true) {
                try {
                    when (val result = session.telemetry.requestDevice()) {
                        is AdminResult.Success -> metrics.value = result.value to Instant.now()
                        else -> Log.d(TAG, "Radio telemetry request: $result")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Radio telemetry request failed", e)
                }
                delay(POLL_MS)
            }
        }
        combine(session.ownNode, session.configBundle, metrics) { own, bundle, latest ->
            RadioInfoMapper.toRadioInfo(own, bundle, latest?.first, latest?.second)
        }.collect { info -> if (info.nodeIdHex != null) _info.value = info }
    }

    private companion object {
        const val POLL_MS = 2 * 60_000L
    }
}
