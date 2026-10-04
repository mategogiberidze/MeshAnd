package com.meshand.app.data.update

import android.util.Log
import com.meshand.app.BuildConfig
import com.meshand.app.data.settings.AppSettings
import com.meshand.app.domain.AppVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant

private const val TAG = "MeshAnd/Update"

/** A newer MeshAnd release on GitHub. */
data class AvailableUpdate(val version: String, val pageUrl: String)

/**
 * Asks GitHub for the latest MeshAnd release when the app is opened (at most once per
 * [CHECK_EVERY]) and publishes it in [available] if it's newer than this build. This is the only
 * internet access MeshAnd makes, and the user can switch it off.
 */
class UpdateChecker(private val settings: AppSettings, private val scope: CoroutineScope) {
    private val _available = MutableStateFlow<AvailableUpdate?>(null)
    val available: StateFlow<AvailableUpdate?> = _available.asStateFlow()

    private var checking = false

    init {
        scope.launch {
            settings.checkForUpdates.collect { enabled ->
                publish()
                if (enabled) checkIfDue()
            }
        }
    }

    /** Called when MeshAnd comes to the front. */
    fun checkIfDue(now: Instant = Instant.now()) {
        if (!settings.checkForUpdates.value || checking) return
        if (Duration.between(Instant.ofEpochMilli(settings.lastUpdateCheckMillis), now) < CHECK_EVERY) return
        checking = true
        scope.launch {
            val latest = withContext(Dispatchers.IO) { fetchLatest() }
            checking = false
            if (latest != null) {
                Log.i(TAG, "Latest release on GitHub: ${latest.version} (this build: ${BuildConfig.VERSION_NAME})")
                settings.saveLatestRelease(latest.version, latest.pageUrl, now.toEpochMilli())
                publish()
            }
        }
    }

    /** "Later": don't offer this version again. */
    fun dismiss() {
        _available.value?.let { settings.dismissedUpdateVersion = it.version }
        publish()
    }

    private fun publish() {
        val version = settings.latestReleaseVersion
        val url = settings.latestReleaseUrl
        _available.value = if (
            settings.checkForUpdates.value && version != null && url != null &&
            version != settings.dismissedUpdateVersion && AppVersion.isNewer(version, BuildConfig.VERSION_NAME)
        ) {
            AvailableUpdate(version, url)
        } else {
            null
        }
    }

    /** Null when offline or GitHub doesn't answer; the next app start tries again. */
    private fun fetchLatest(): AvailableUpdate? = try {
        val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "MeshAnd/${BuildConfig.VERSION_NAME}")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "Update check: GitHub answered HTTP ${connection.responseCode}")
                null
            } else {
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                AvailableUpdate(
                    version = json.getString("tag_name").removePrefix("v"),
                    pageUrl = json.optString("html_url").ifBlank { RELEASES_PAGE },
                )
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Log.i(TAG, "Update check failed (offline?): ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    companion object {
        val CHECK_EVERY: Duration = Duration.ofHours(1)
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/mategogiberidze/MeshAnd/releases/latest"
        private const val RELEASES_PAGE = "https://github.com/mategogiberidze/MeshAnd/releases/latest"
    }
}
