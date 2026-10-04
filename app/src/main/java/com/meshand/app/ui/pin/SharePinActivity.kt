package com.meshand.app.ui.pin

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshand.app.domain.PinText
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.graph
import com.meshand.app.ui.theme.MeshAndTheme
import java.util.Locale

/**
 * Receives text shared from another app (OsmAnd: tap a place → Share → "MeshAnd pin"), finds the
 * coordinates in it, and sends them to the team as a pin after the user confirms. Only the
 * coordinates and an optional short description are sent, never the rest of the shared text.
 */
class SharePinActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shared = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val parsed = PinText.parseShared(shared)
        val graph = applicationContext.graph
        setContent {
            MeshAndTheme {
                val status by graph.client.status.collectAsStateWithLifecycle()
                var name by remember { mutableStateOf(parsed?.name.orEmpty()) }
                // Off by default: only the coordinates go out unless the user ticks it.
                var withDescription by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                Scaffold(Modifier.fillMaxSize()) { padding ->
                    Column(
                        Modifier.padding(padding).padding(16.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Send a pin to your team", style = MaterialTheme.typography.headlineSmall)
                        if (parsed == null) {
                            Text("No coordinates found in what was shared.", color = MaterialTheme.colorScheme.error)
                            Text(
                                "In OsmAnd, tap a place on the map, then Share → MeshAnd pin.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            OutlinedButton(onClick = { finish() }) { Text("Close") }
                            return@Column
                        }
                        Text(
                            String.format(Locale.US, "%.5f, %.5f", parsed.latitude, parsed.longitude),
                            fontFamily = FontFamily.Monospace,
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(withDescription, role = Role.Checkbox, onValueChange = { withDescription = it }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = withDescription, onCheckedChange = null)
                            Text("Send a description", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                        }
                        OutlinedTextField(
                            value = name,
                            // Editing the description means the user wants it sent.
                            onValueChange = {
                                name = PinText.truncateToBytes(it, PinText.MAX_NAME_BYTES)
                                withDescription = true
                            },
                            label = { Text("Description (optional)") },
                            supportingText = {
                                Text("${PinText.utf8Bytes(name)} of ${PinText.MAX_NAME_BYTES} bytes (Georgian letters take 3)")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val description = name.takeIf { withDescription }
                        val message = PinText.format(parsed.latitude, parsed.longitude, description)
                        Text(
                            "Sent on your primary channel as a text message. Teammates with MeshAnd see it on " +
                                "the OsmAnd map; others read it in the Meshtastic app:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(message, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        val connected = status is ConnectionStatus.Connected
                        if (!connected) {
                            Text("MeshAnd isn't connected to your radio. Open MeshAnd and connect first.", color = MaterialTheme.colorScheme.error)
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = connected,
                                onClick = {
                                    error = graph.pins.send(parsed.latitude, parsed.longitude, description)
                                    if (error == null) {
                                        Toast.makeText(this@SharePinActivity, "Pin sent to your team", Toast.LENGTH_SHORT).show()
                                        finish()
                                    }
                                },
                            ) { Text("Send pin") }
                            OutlinedButton(onClick = { finish() }) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }
}
