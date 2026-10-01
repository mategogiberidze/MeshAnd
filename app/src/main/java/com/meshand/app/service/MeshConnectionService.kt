package com.meshand.app.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.meshand.app.MainActivity
import com.meshand.app.R
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.MeshNode
import com.meshand.app.domain.model.OsmAndStatus
import com.meshand.app.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

private const val TAG = "MeshAnd"

/**
 * Foreground service that keeps the process (and so the radio link and the OsmAnd bridge) alive
 * while the user wants a radio connected, e.g. with OsmAnd in front and the screen off.
 * It owns no state: everything lives in [com.meshand.app.AppGraph]; this only shows the
 * persistent notification and stops itself when no radio is wanted any more.
 */
class MeshConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Starting…", null),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                },
            )
            Log.i(TAG, "Foreground service started")
        } catch (e: Exception) {
            // Android 12+ refuses foreground starts from the background (e.g. a sticky restart).
            Log.e(TAG, "Could not start foreground service", e)
            stopSelf()
            return
        }

        val graph = applicationContext.graph
        // After the system restarted us (process was killed), reconnect if the user wanted it;
        // otherwise nothing is wanted and the collector below stops the service.
        graph.client.connectSavedIfWanted()
        scope.launch {
            combine(graph.client.activeRadio, graph.client.status, graph.repository.nodes, graph.osmAnd.status) { radio, status, nodes, osmAnd ->
                if (radio == null) null else Triple(status, nodes, osmAnd)
            }.conflate().collect { state ->
                if (state == null) {
                    Log.i(TAG, "No radio wanted any more; stopping foreground service")
                    stopSelf()
                    return@collect
                }
                val (status, nodes, osmAnd) = state
                NotificationManagerCompat.from(this@MeshConnectionService).let { manager ->
                    if (manager.areNotificationsEnabled()) {
                        try {
                            manager.notify(NOTIFICATION_ID, buildNotification(statusText(status, nodes), osmAndText(osmAnd)))
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Notification permission missing", e)
                        }
                    }
                }
                delay(1_000) // at most one notification update per second
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) applicationContext.graph.client.disconnect()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        Log.i(TAG, "Foreground service stopped")
        super.onDestroy()
    }

    private fun createChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Radio connection")
                .setDescription("Shown while MeshAnd keeps the Meshtastic radio connected")
                .setShowBadge(false)
                .build(),
        )
    }

    private fun buildNotification(text: String, subText: String?) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle("MeshAnd")
            .setContentText(text)
            .setSubText(subText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .addAction(
                0, "Disconnect",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, MeshConnectionService::class.java).setAction(ACTION_DISCONNECT),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    private fun statusText(status: ConnectionStatus, nodes: List<MeshNode>): String {
        val withPos = nodes.count { it.hasPosition }
        return when (status) {
            is ConnectionStatus.Connected ->
                "Connected to ${status.radio.name ?: status.radio.address} · ${nodes.size} nodes, $withPos with position"
            is ConnectionStatus.Reconnecting -> "Reconnecting to ${status.radio.name ?: status.radio.address}: ${status.detail}"
            is ConnectionStatus.Connecting -> "Connecting to ${status.radio.name ?: status.radio.address}: ${status.detail}"
            is ConnectionStatus.Error -> "Error: ${status.message}"
            ConnectionStatus.Scanning -> "Scanning"
            ConnectionStatus.Disconnected -> "Disconnected"
        }
    }

    private fun osmAndText(status: OsmAndStatus): String? = when (status) {
        is OsmAndStatus.Showing -> "OsmAnd: ${status.nodeCount} on map"
        is OsmAndStatus.NotAllowed -> "OsmAnd: allow MeshAnd in Plugins"
        is OsmAndStatus.Connecting -> "OsmAnd: connecting"
        else -> null
    }

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "com.meshand.app.action.DISCONNECT"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MeshConnectionService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed", e)
            }
        }
    }
}
