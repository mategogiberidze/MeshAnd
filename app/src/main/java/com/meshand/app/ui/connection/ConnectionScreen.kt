package com.meshand.app.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meshand.app.UiState
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.DiscoveredRadio

private val Ok = Color(0xFF2E7D32)
private val Bad = Color(0xFFC62828)

@Composable
fun ConnectionScreen(
    state: UiState,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onFilterChange: (Boolean) -> Unit,
    onConnect: (DiscoveredRadio) -> Unit,
    onCancel: () -> Unit,
) {
    val env = state.environment
    val status = state.status
    val busy = status is ConnectionStatus.Connecting

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("MeshAnd — Phase 1", style = MaterialTheme.typography.headlineSmall)
        }

        // ── Permissions / environment ──
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Bluetooth permissions", fontWeight = FontWeight.Bold)
                    env.permissions.forEach { (perm, granted) ->
                        StatusLine(perm.substringAfterLast('.'), granted, if (granted) "Granted" else "Denied")
                    }
                    StatusLine("Bluetooth adapter", env.bluetoothOn, if (env.bluetoothOn) "On" else "Off")
                    env.locationServicesOn?.let { on ->
                        StatusLine("Location services (needed to scan on Android ≤ 11)", on, if (on) "On" else "Off")
                    }
                    if (!env.allPermissionsGranted) {
                        Button(onClick = onRequestPermissions) { Text("Grant Bluetooth permissions") }
                    }
                }
            }
        }

        // ── Connection status ──
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Status: ${statusLabel(status)}", fontWeight = FontWeight.Bold)
                    when (status) {
                        is ConnectionStatus.Connecting -> {
                            Text("${status.radio.name ?: "Radio"} ${status.radio.address}")
                            Text(status.detail, style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            OutlinedButton(onClick = onCancel) { Text("Cancel") }
                        }
                        is ConnectionStatus.Error -> Text(status.message, color = Bad)
                        ConnectionStatus.Scanning -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        else -> Unit
                    }
                }
            }
        }

        // ── Scan controls ──
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status == ConnectionStatus.Scanning) {
                    Button(onClick = onStopScan) { Text("Stop scan") }
                } else {
                    Button(onClick = onStartScan, enabled = env.allPermissionsGranted && env.bluetoothOn && !busy) {
                        Text("Scan for radios")
                    }
                }
                Checkbox(checked = state.filterByService, onCheckedChange = onFilterChange, enabled = status != ConnectionStatus.Scanning)
                Text("Meshtastic only", style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            Text("Meshtastic Radios (${state.radios.size})", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
        }

        if (state.radios.isEmpty()) {
            item {
                Text(
                    if (status == ConnectionStatus.Scanning) "Scanning…" else "No radios found yet. Make sure the radio is on and not connected to another phone.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        items(state.radios, key = { it.address }) { radio ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(radio.name ?: "(unnamed)", fontWeight = FontWeight.Bold)
                        Text(radio.address, fontFamily = FontFamily.Monospace)
                        Text(
                            "RSSI ${radio.rssi} dBm" + if (radio.bonded) " · paired" else " · not paired",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = { onConnect(radio) }, enabled = !busy) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = if (ok) Ok else Bad, fontWeight = FontWeight.Bold)
    }
}

private fun statusLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.Disconnected -> "Disconnected"
    ConnectionStatus.Scanning -> "Scanning"
    is ConnectionStatus.Connecting -> "Connecting"
    is ConnectionStatus.Connected -> "Connected"
    is ConnectionStatus.Error -> "Error"
}
