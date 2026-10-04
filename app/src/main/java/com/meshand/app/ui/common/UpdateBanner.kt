package com.meshand.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meshand.app.BuildConfig
import com.meshand.app.data.update.AvailableUpdate

/** Shown at the top of MeshAnd when GitHub has a newer release. */
@Composable
fun UpdateBanner(update: AvailableUpdate, onDownload: () -> Unit, onLater: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("MeshAnd ${update.version} is available", fontWeight = FontWeight.Bold)
            Text(
                "You have ${BuildConfig.VERSION_NAME}. Download the APK from the release page and open it to update.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDownload) { Text("Download") }
                TextButton(onClick = onLater) { Text("Later") }
            }
        }
    }
}
