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

    companion object {
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
