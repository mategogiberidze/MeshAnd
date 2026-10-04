package com.meshand.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.meshand.app.domain.Geo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A white (dark: raised) card with a thin border; the building block of every screen. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** Secondary explanation text, readable outdoors (no tiny grey text). */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium, color = color)
}

/** Small filled circle used next to a status line. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).background(color, CircleShape))
}

/** One-of-N choice (e.g. 15m / 30m / 1h) as Material segmented buttons. */
@Composable
fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                label = { Text(label(option), maxLines = 1) },
            )
        }
    }
}

/** A whole-row switch (the full row is the touch target, ≥ 48dp). */
@Composable
fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) HintText(subtitle)
        }
        // The row handles the click; the switch only shows the state.
        Switch(checked = checked, onCheckedChange = null)
    }
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val dayClockFormat = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.US)
private val fullFormat = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.US)

/** Real time: "14:05" today, "4 Oct 14:05" on another day, with the year if it's not this year. */
fun formatDateTime(time: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
    val local = time.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    return when {
        local.toLocalDate() == today -> local.format(clockFormat)
        local.year == today.year -> local.format(dayClockFormat)
        else -> local.format(fullFormat)
    }
}

/** "14:05 (5 min ago)": the real time plus how long ago. */
fun formatWhen(time: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    "${formatDateTime(time, now, zone)} (${formatAgo(Duration.between(time, now).seconds)})"

/** How far away something is, as a small rounded badge ("850 m", "1.2 km", "23 km"). */
@Composable
fun DistanceBadge(meters: Double, modifier: Modifier = Modifier) {
    val text = Geo.formatDistance(meters)
    Text(
        text,
        modifier = modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = "$text away" },
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
    )
}

/** Human-friendly "N min ago". */
fun formatAgo(seconds: Long): String = when {
    seconds < 60 -> "just now"
    seconds < 3600 -> "${seconds / 60} min ago"
    seconds < 86_400 -> "${seconds / 3600} h ${seconds % 3600 / 60} min ago"
    else -> "${seconds / 86_400} d ago"
}
