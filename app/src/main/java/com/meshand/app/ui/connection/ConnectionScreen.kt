package com.meshand.app.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meshand.app.UiState
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.DiscoveredRadio
import com.meshand.app.ui.common.HintText
import com.meshand.app.ui.common.SectionCard
import com.meshand.app.ui.common.StatusDot
import com.meshand.app.ui.theme.good
import com.meshand.app.ui.theme.warning

/** Shown while no radio is connected: setup checks, the last radio, and scanning. */
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
    val ready = env.allPermissionsGranted && env.bluetoothOn && env.locationServicesOn != false
    val scanning = status == ConnectionStatus.Scanning

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Only what's missing; nothing to read when everything is fine.
        if (!ready || env.optionalPermissions.values.any { !it }) {
            item {
                SectionCard(title = if (ready) "Optional" else "Before you connect") {
                    env.permissions.forEach { (perm, granted) -> Check(permissionLabel(perm), granted) }
                    env.optionalPermissions.forEach { (perm, granted) -> Check(permissionLabel(perm) + " (optional)", granted, optional = true) }
                    Check("Bluetooth is on", env.bluetoothOn)
                    env.locationServicesOn?.let { Check("Location is on (Android needs it to scan)", it) }
                    val anyMissing = !env.allPermissionsGranted || env.optionalPermissions.values.any { !it }
                    if (anyMissing) Button(onClick = onRequestPermissions) { Text("Grant permissions") }
                }
            }
        }

        when (status) {
            is ConnectionStatus.Connecting -> item {
                SectionCard(title = "Connecting to ${status.radio.name ?: status.radio.address}") {
                    HintText(status.detail)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            }
            is ConnectionStatus.Error -> item {
                SectionCard(title = "Couldn't connect") {
                    HintText(status.message, color = MaterialTheme.colorScheme.error)
                }
            }
            else -> Unit
        }

        state.savedRadio?.let { saved ->
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            HintText("Your radio")
                            Text(saved.name ?: "(unnamed)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(saved.address, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        }
                        Button(onClick = { onConnect(saved) }, enabled = ready && !busy) { Text("Connect") }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (scanning) {
                    OutlinedButton(onClick = onStopScan, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Stop scanning") }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Button(
                        onClick = onStartScan,
                        enabled = ready && !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Scan for radios") }
                }
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .toggleable(state.filterByService, enabled = !scanning, role = Role.Checkbox, onValueChange = onFilterChange),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = state.filterByService, onCheckedChange = null, enabled = !scanning)
                    Text("Meshtastic radios only", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        if (state.radios.isEmpty()) {
            if (!scanning) item { HintText("Turn the radio on, make sure no other phone is connected to it, then scan.") }
        } else {
            item { Text("Found (${state.radios.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        }

        items(state.radios, key = { it.address }) { radio ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(radio.name ?: "(unnamed)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(radio.address, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        HintText("Signal ${radio.rssi} dBm" + if (radio.bonded) " · paired" else "")
                    }
                    Button(onClick = { onConnect(radio) }, enabled = !busy) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
private fun Check(label: String, ok: Boolean, optional: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val color = when {
        ok -> colors.good
        optional -> colors.warning
        else -> colors.error
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusDot(color)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(if (ok) "OK" else if (optional) "Off" else "Missing", style = MaterialTheme.typography.labelLarge, color = color)
    }
}

private fun permissionLabel(permission: String): String = when (permission.substringAfterLast('.')) {
    "BLUETOOTH_SCAN" -> "Find nearby radios"
    "BLUETOOTH_CONNECT" -> "Connect to radios"
    "ACCESS_FINE_LOCATION" -> "Location permission (for Bluetooth scanning)"
    "POST_NOTIFICATIONS" -> "Notifications"
    else -> permission.substringAfterLast('.')
}
