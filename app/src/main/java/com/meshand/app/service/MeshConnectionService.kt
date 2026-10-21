package com.meshand.app.service

import android.app.Notification
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
import com.meshand.app.domain.model.PhoneGpsStatus
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
    private var notification: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val first = buildNotification("Starting…", null).also { notification = it }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                first,
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
            notification = null
            stopSelf()
            return
        }

        val graph = applicationContext.graph
        // After the system restarted us (process was killed), reconnect if the user wanted it;
        // otherwise nothing is wanted and the collector below stops the service.
        graph.client.connectSavedIfWanted()
        // Run as a location service too while the phone feeds its GPS to the radio.
        scope.launch { graph.phoneGps.wantsLocation.collect(::applyForegroundType) }
        scope.launch {
            combine(graph.client.activeRadio, graph.client.status, graph.repository.activeNodes, graph.osmAnd.status, graph.phoneGps.status) { radio, status, nodes, osmAnd, phoneGps ->
                if (radio == null) null else NotificationState(status, nodes, osmAnd, phoneGps)
            }.conflate().collect { state ->
                if (state == null) {
                    Log.i(TAG, "No radio wanted any more; stopping foreground service")
                    stopSelf()
                    return@collect
                }
                val (status, nodes, osmAnd, phoneGps) = state
                val text = statusText(status, nodes) + phoneGpsText(phoneGps).orEmpty()
                val built = buildNotification(text, osmAndText(osmAnd)).also { notification = it }
                NotificationManagerCompat.from(this@MeshConnectionService).let { manager ->
                    if (manager.areNotificationsEnabled()) {
                        try {
                            manager.notify(NOTIFICATION_ID, built)
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
        val graph = applicationContext.graph
        if (intent?.action == ACTION_DISCONNECT) {
            graph.client.disconnect()
        } else if (notification != null) {
            // Started again from the app (in front now): retry location access if it was refused.
            applyForegroundType(graph.phoneGps.wantsLocation.value)
        }
        return START_STICKY
    }

    /**
     * Adds or drops the location service type. Android 14+ refuses to add it while MeshAnd is in
     * the background (e.g. after a sticky restart); phone GPS then waits until the app is opened.
     */
    private fun applyForegroundType(withLocation: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return // no types; the service is enough
        val current = notification ?: return
        val phoneGps = applicationContext.graph.phoneGps
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (withLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, current, type)
            phoneGps.setBackgroundBlocked(false)
        } catch (e: Exception) {
            Log.w(TAG, "Android refused location access in the background; phone GPS waits for the app", e)
            phoneGps.setBackgroundBlocked(true)
        }
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

    private fun phoneGpsText(status: PhoneGpsStatus): String? = when (status) {
        PhoneGpsStatus.Off, PhoneGpsStatus.WaitingForRadio -> null
        is PhoneGpsStatus.Sending, PhoneGpsStatus.WaitingForFix -> " · phone GPS"
        PhoneGpsStatus.NeedsApp -> " · phone GPS paused: open MeshAnd"
        PhoneGpsStatus.LocationOff -> " · phone GPS: Location is off"
        PhoneGpsStatus.NoPermission -> " · phone GPS: permission needed"
    }

    private data class NotificationState(
        val status: ConnectionStatus,
        val nodes: List<MeshNode>,
        val osmAnd: OsmAndStatus,
        val phoneGps: PhoneGpsStatus,
    )

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
