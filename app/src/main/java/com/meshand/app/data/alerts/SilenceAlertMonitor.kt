package com.meshand.app.data.alerts

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.meshand.app.R
import com.meshand.app.data.settings.AppSettings
import com.meshand.app.domain.Geo
import com.meshand.app.domain.NodeColors
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.MeshNode
import com.meshand.app.ui.team.TeamActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TAG = "MeshAnd/Alerts"

/**
 * Posts a notification when a watched teammate hasn't been heard for the configured time, and
 * another when they're heard again. Only evaluates while the radio is connected: when our own
 * link is down everyone looks silent, which says nothing about the teammates.
 */
class SilenceAlertMonitor(
    context: Context,
    private val settings: AppSettings,
    private val nodes: StateFlow<List<MeshNode>>,
    private val status: StateFlow<ConnectionStatus>,
) {
    private val appContext = context.applicationContext
    private var alerted: Set<Long> = emptySet()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    fun start(scope: CoroutineScope) {
        NotificationManagerCompat.from(appContext).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Teammate alerts")
                .setDescription("A watched teammate hasn't been heard for a while, or is back")
                .build(),
        )
        scope.launch {
            while (true) {
                check()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private fun check() {
        val minutes = settings.silenceAlertMinutes.value
        if (minutes <= 0 || status.value !is ConnectionStatus.Connected) return
        val decisions = SilenceAlertLogic.evaluate(
            nodes = nodes.value,
            watched = settings.watchedNodeIds.value,
            threshold = Duration.ofMinutes(minutes.toLong()),
            now = Instant.now(),
            alreadyAlerted = alerted,
        )
        alerted = decisions.alerted
        val me = nodes.value.firstOrNull { it.isOwnNode }
        decisions.wentSilent.forEach { node ->
            val silentFor = node.lastSeen?.let { Duration.between(it, Instant.now()).toMinutes() } ?: minutes.toLong()
            Log.i(TAG, "Teammate silent: ${node.nodeIdHex} for $silentFor min")
            notify(
                node,
                title = "${name(node)} not heard for $silentFor min",
                text = details(node, me),
                important = true,
            )
        }
        decisions.cameBack.forEach { node ->
            Log.i(TAG, "Teammate back: ${node.nodeIdHex}")
            notify(node, title = "${name(node)} is back", text = details(node, me), important = false)
        }
    }

    private fun name(node: MeshNode) = node.longName ?: node.shortName ?: node.nodeIdHex

    private fun details(node: MeshNode, me: MeshNode?): String {
        val heard = node.lastSeen?.atZone(ZoneId.systemDefault())?.format(timeFormat)?.let { "Last heard $it" }
        val where = if (me?.hasPosition == true && node.hasPosition) {
            val d = Geo.distanceMeters(me.latitude!!, me.longitude!!, node.latitude!!, node.longitude!!)
            val b = Geo.bearingDegrees(me.latitude, me.longitude, node.latitude, node.longitude)
            "last position ${Geo.formatDistance(d)} ${Geo.compassPoint(b)} of you"
        } else {
            null
        }
        return listOfNotNull(heard, where).joinToString(" · ")
    }

    private fun notify(node: MeshNode, title: String, text: String, important: Boolean) {
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled; can't show: $title")
            return
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setColor(NodeColors.colorFor(node))
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(if (important) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setSilent(!important)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(
                PendingIntent.getActivity(
                    appContext, 2,
                    Intent(appContext, TeamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        try {
            // One notification per teammate: "back" replaces "not heard".
            manager.notify(NOTIFICATION_ID_BASE + (node.id % 100_000).toInt(), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission missing", e)
        }
    }

    private companion object {
        const val CHANNEL_ID = "team_alerts"
        const val NOTIFICATION_ID_BASE = 1_000
        const val CHECK_INTERVAL_MS = 30_000L
    }
}
