package com.meshand.app.data.settings

import android.content.Context
import com.meshand.app.domain.model.DiscoveredRadio

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

    private companion object {
        const val KEY_RADIO_ADDRESS = "radio_address"
        const val KEY_RADIO_NAME = "radio_name"
        const val KEY_AUTO_CONNECT = "auto_connect"
        const val KEY_OSMAND_ENABLED = "osmand_enabled"
    }
}
