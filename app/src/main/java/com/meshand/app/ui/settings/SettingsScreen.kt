package com.meshand.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meshand.app.BuildConfig
import com.meshand.app.data.settings.AppSettings
import com.meshand.app.data.settings.ThemeMode
import com.meshand.app.ui.common.ChoiceRow
import com.meshand.app.ui.common.HintText
import com.meshand.app.ui.common.SectionCard
import com.meshand.app.ui.common.SwitchRow
import java.util.Locale

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    showOwnRadioOnMap: Boolean,
    onShowOwnRadioChange: (Boolean) -> Unit,
    silenceAlertMinutes: Int,
    onSilenceAlertMinutesChange: (Int) -> Unit,
    trailMinutes: Int,
    onTrailMinutesChange: (Int) -> Unit,
    onResetTrails: () -> Unit,
    savedDataBytes: Long,
    onClearSavedData: () -> Unit,
    checkForUpdates: Boolean,
    onCheckForUpdatesChange: (Boolean) -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "Appearance") {
                ChoiceRow(ThemeMode.entries, themeMode, ThemeMode::label, onThemeModeChange)
            }
        }
        item {
            SectionCard(title = "Map") {
                SwitchRow(
                    title = "Show my radio on the map",
                    subtitle = "Useful for debugging. Distances always use your radio's position.",
                    checked = showOwnRadioOnMap,
                    onCheckedChange = onShowOwnRadioChange,
                )
            }
        }
        item {
            SectionCard(title = "Teammate alerts") {
                HintText("Notify when a teammate you switched \"Alert\" on for (in the Team list) isn't heard for:")
                ChoiceRow(AppSettings.SILENCE_MINUTE_OPTIONS, silenceAlertMinutes, ::minutesLabel, onSilenceAlertMinutesChange)
            }
        }
        item {
            SectionCard(title = "Trails") {
                HintText("How much of each teammate's path to keep. Show a trail from the Team list, or with \"Trail\" when you tap someone on the OsmAnd map.")
                ChoiceRow(AppSettings.TRAIL_MINUTE_OPTIONS, trailMinutes, ::minutesLabel, onTrailMinutesChange)
                OutlinedButton(onClick = onResetTrails) { Text("Reset all trails") }
            }
        }
        item {
            SectionCard(title = "Saved data") {
                HintText(
                    "Trails and pins are saved on this phone, so they're still there after MeshAnd or the " +
                        "phone restarts. Using ${formatBytes(savedDataBytes)}.",
                )
                OutlinedButton(onClick = { confirmClear = true }) { Text("Clear saved data") }
            }
        }
        item {
            SectionCard(title = "About") {
                HintText("MeshAnd ${BuildConfig.VERSION_NAME}")
                SwitchRow(
                    title = "Check for updates",
                    subtitle = "Asks GitHub for a newer version when MeshAnd opens. This is MeshAnd's only internet use.",
                    checked = checkForUpdates,
                    onCheckedChange = onCheckForUpdatesChange,
                )
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear saved data?") },
            text = { Text("All trails and pins are deleted from this phone. Trails start again from everyone's current position.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearSavedData() }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

private fun minutesLabel(minutes: Int): String = when {
    minutes == 0 -> "Off"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes}m"
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes bytes"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
