package com.meshand.app.data.phonegps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.meshand.app.data.meshtastic.MeshtasticClient
import com.meshand.app.data.settings.AppSettings
import com.meshand.app.domain.model.ConnectionStatus
import com.meshand.app.domain.model.PhoneGpsStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.sdk.ChannelIndex
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import java.time.Instant
import kotlin.math.roundToInt

private const val TAG = "MeshAnd"

/**
 * While the user has "use phone GPS" on, sends the phone's location to our own radio (addressed to
 * its node number, so it never goes over LoRa itself). The firmware takes it as the radio's
 * position and broadcasts it on its usual schedule; if the radio's own GPS has a fix too, the two
 * take turns. Coordinates are never logged.
 */
class PhoneGpsFeeder(
    context: Context,
    settings: AppSettings,
    client: MeshtasticClient,
    scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val permission = MutableStateFlow(hasPermission())
    private val backgroundBlocked = MutableStateFlow(false)

    private val _status = MutableStateFlow<PhoneGpsStatus>(PhoneGpsStatus.Off)
    val status: StateFlow<PhoneGpsStatus> = _status.asStateFlow()

    /** True while the foreground service should also run as a location service. */
    val wantsLocation: StateFlow<Boolean> = combine(settings.usePhoneGps, permission) { on, granted -> on && granted }
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        val connected = client.status.map { it is ConnectionStatus.Connected }.distinctUntilChanged()
        scope.launch {
            combine(settings.usePhoneGps, permission, client.session, connected, backgroundBlocked) { on, granted, session, isConnected, _ ->
                when {
                    !on -> PhoneGpsStatus.Off to null
                    !granted -> PhoneGpsStatus.NoPermission to null
                    session == null || !isConnected -> PhoneGpsStatus.WaitingForRadio to null
                    else -> waitingStatus() to session
                }
            }.collectLatest { (status, session) ->
                _status.value = status
                if (session != null) feed(session)
            }
        }
    }

    /** Re-read the permission; called when the app comes back to the front. */
    fun refreshPermission() {
        permission.value = hasPermission()
    }

    /** Set by the service when Android refused location access in the background. */
    fun setBackgroundBlocked(blocked: Boolean) {
        backgroundBlocked.value = blocked
    }

    private suspend fun feed(session: RadioClient) {
        var lastSentAt: Long? = null
        fixes().collect { fix ->
            val now = System.currentTimeMillis()
            val ageMs = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000
            val accuracy = if (fix.hasAccuracy()) fix.accuracy else null
            if (!PhonePosition.isUsable(accuracy, ageMs, lastSentAt, now)) return@collect
            val own = session.ownNode.value?.num ?: return@collect
            val position = PhonePosition.build(
                latitude = fix.latitude,
                longitude = fix.longitude,
                altitudeHae = if (fix.hasAltitude()) fix.altitude else null,
                altitudeMsl = if (Build.VERSION.SDK_INT >= 34 && fix.hasMslAltitude()) fix.mslAltitudeMeters else null,
                timeMillis = fix.time,
            )
            try {
                session.send(
                    portnum = PortNum.POSITION_APP,
                    payload = Position.ADAPTER.encode(position),
                    to = NodeId(own),
                    channel = ChannelIndex(0),
                    wantAck = false,
                )
                lastSentAt = now
                val meters = (accuracy ?: 0f).roundToInt()
                _status.value = PhoneGpsStatus.Sending(Instant.ofEpochMilli(now), meters)
                Log.d(TAG, "Phone position sent to the radio (±$meters m)")
            } catch (e: MeshtasticException) {
                Log.w(TAG, "Sending the phone position failed", e)
            }
        }
    }

    // Only collected while permission is granted (see the combine in init).
    @SuppressLint("MissingPermission")
    private fun fixes(): Flow<Location> = callbackFlow {
        val listener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                trySend(location)
            }

            override fun onProviderEnabled(provider: String) {
                if (_status.value !is PhoneGpsStatus.Sending) _status.value = waitingStatus()
            }

            override fun onProviderDisabled(provider: String) {
                _status.value = PhoneGpsStatus.LocationOff
            }
        }
        val request = LocationRequestCompat.Builder(PhonePosition.REQUEST_INTERVAL_MS)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()
        try {
            LocationManagerCompat.requestLocationUpdates(
                locationManager, LocationManager.GPS_PROVIDER, request, listener, Looper.getMainLooper(),
            )
            Log.i(TAG, "Phone GPS: listening for location updates")
        } catch (e: SecurityException) {
            Log.w(TAG, "Phone GPS: location permission missing", e)
            permission.value = false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Phone GPS: this phone has no GPS provider", e)
        }
        awaitClose {
            LocationManagerCompat.removeUpdates(locationManager, listener)
            Log.i(TAG, "Phone GPS: stopped location updates")
        }
    }

    private fun waitingStatus(): PhoneGpsStatus = when {
        !LocationManagerCompat.isLocationEnabled(locationManager) -> PhoneGpsStatus.LocationOff
        backgroundBlocked.value -> PhoneGpsStatus.NeedsApp
        else -> PhoneGpsStatus.WaitingForFix
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
