package com.meshand.app.data.settings

import android.content.Context
import com.meshand.app.domain.model.DiscoveredRadio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small persistent key-value settings (SharedPreferences); no secrets are stored here. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("meshand", Context.MODE_PRIVATE)

    /** The radio the user last connected to; reconnected to automatically on app start. */
    var savedRadio: DiscoveredRadio?
        get() {
            val address = prefs.getString(KEY_RADIO_ADDRESS, null) ?: return null
            return DiscoveredRadio(
                name = prefs.getString(KEY_RADIO_NAME, null),
                address = address,
                rssi = 0,
                bonded = true,
            )
        }
        set(value) {
            prefs.edit()
                .putString(KEY_RADIO_ADDRESS, value?.address)
                .putString(KEY_RADIO_NAME, value?.name)
                .apply()
        }

    /** True while the user wants to be connected; cleared by an explicit Disconnect. */
    var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONNECT, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CONNECT, value).apply()

    var osmAndEnabled: Boolean
        get() = prefs.getBoolean(KEY_OSMAND_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_OSMAND_ENABLED, value).apply()

    /** Minutes without hearing a watched teammate before alerting; 0 = alerts off. */
    private val _silenceAlertMinutes = MutableStateFlow(prefs.getInt(KEY_SILENCE_MINUTES, DEFAULT_SILENCE_MINUTES))
    val silenceAlertMinutes: StateFlow<Int> = _silenceAlertMinutes.asStateFlow()

    fun setSilenceAlertMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_SILENCE_MINUTES, minutes).apply()
        _silenceAlertMinutes.value = minutes
    }

    /** Node numbers the user wants "not heard" alerts for (opt-in, so strangers never alert). */
    private val _watchedNodeIds = MutableStateFlow(
        prefs.getStringSet(KEY_WATCHED, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
    )
    val watchedNodeIds: StateFlow<Set<Long>> = _watchedNodeIds.asStateFlow()

    fun setWatched(nodeId: Long, watched: Boolean) {
        val updated = if (watched) _watchedNodeIds.value + nodeId else _watchedNodeIds.value - nodeId
        prefs.edit().putStringSet(KEY_WATCHED, updated.mapTo(HashSet()) { it.toString() }).apply()
        _watchedNodeIds.value = updated
    }

    /** Show our own radio as a point on the OsmAnd map (off by default; useful for debugging). */
    private val _showOwnRadioOnMap = MutableStateFlow(prefs.getBoolean(KEY_SHOW_OWN_RADIO, false))
    val showOwnRadioOnMap: StateFlow<Boolean> = _showOwnRadioOnMap.asStateFlow()

    fun setShowOwnRadioOnMap(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_OWN_RADIO, show).apply()
        _showOwnRadioOnMap.value = show
    }

    /** How much history each teammate's trail keeps, in minutes. */
    private val _trailMinutes = MutableStateFlow(prefs.getInt(KEY_TRAIL_MINUTES, DEFAULT_TRAIL_MINUTES))
    val trailMinutes: StateFlow<Int> = _trailMinutes.asStateFlow()

    fun setTrailMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_TRAIL_MINUTES, minutes).apply()
        _trailMinutes.value = minutes
    }

    /** Ask GitHub for new MeshAnd releases when the app opens (the app's only internet access). */
    private val _checkForUpdates = MutableStateFlow(prefs.getBoolean(KEY_CHECK_UPDATES, true))
    val checkForUpdates: StateFlow<Boolean> = _checkForUpdates.asStateFlow()

    fun setCheckForUpdates(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CHECK_UPDATES, enabled).apply()
        _checkForUpdates.value = enabled
    }

    val lastUpdateCheckMillis: Long get() = prefs.getLong(KEY_UPDATE_CHECKED_AT, 0)
    val latestReleaseVersion: String? get() = prefs.getString(KEY_LATEST_VERSION, null)
    val latestReleaseUrl: String? get() = prefs.getString(KEY_LATEST_URL, null)

    fun saveLatestRelease(version: String, url: String, checkedAtMillis: Long) {
        prefs.edit()
            .putString(KEY_LATEST_VERSION, version)
            .putString(KEY_LATEST_URL, url)
            .putLong(KEY_UPDATE_CHECKED_AT, checkedAtMillis)
            .apply()
    }

    /** The release the user answered "Later" to; not offered again. */
    var dismissedUpdateVersion: String?
        get() = prefs.getString(KEY_DISMISSED_VERSION, null)
        set(value) = prefs.edit().putString(KEY_DISMISSED_VERSION, value).apply()

    companion object {
        private const val KEY_CHECK_UPDATES = "check_for_updates"
        private const val KEY_UPDATE_CHECKED_AT = "update_checked_at"
        private const val KEY_LATEST_VERSION = "latest_release_version"
        private const val KEY_LATEST_URL = "latest_release_url"
        private const val KEY_DISMISSED_VERSION = "dismissed_update_version"

        const val DEFAULT_TRAIL_MINUTES = 60
        val TRAIL_MINUTE_OPTIONS = listOf(30, 60, 180, 360)
        private const val KEY_TRAIL_MINUTES = "trail_minutes"
        private const val KEY_SHOW_OWN_RADIO = "show_own_radio_on_map"

        const val DEFAULT_SILENCE_MINUTES = 60
        val SILENCE_MINUTE_OPTIONS = listOf(0, 15, 30, 60, 120)

        private const val KEY_SILENCE_MINUTES = "silence_alert_minutes"
        private const val KEY_WATCHED = "watched_node_ids"
        private const val KEY_RADIO_ADDRESS = "radio_address"
        private const val KEY_RADIO_NAME = "radio_name"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_OSMAND_ENABLED = "osmand_enabled"
    }
}
