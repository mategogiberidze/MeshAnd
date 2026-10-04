package com.meshand.app.data.storage

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

private const val TAG = "MeshAnd/Store"

/**
 * Trails and pins saved as small text files in the app's private storage, so they survive app
 * restarts. Nothing here is shared with other apps. Call [read]/[write] off the main thread.
 */
class LocalStore(context: Context) {
    private val dir = File(context.filesDir, "store")

    private val _sizeBytes = MutableStateFlow(0L)

    /** Total size of the saved files. */
    val sizeBytes: StateFlow<Long> = _sizeBytes.asStateFlow()

    fun read(name: String): String? = try {
        File(dir, name).takeIf { it.isFile }?.readText()
    } catch (e: IOException) {
        Log.w(TAG, "Reading $name failed", e)
        null
    } finally {
        updateSize()
    }

    /** Writes via a temporary file and a rename, so a crash never leaves a half-written file. */
    @Synchronized
    fun write(name: String, text: String) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(File(dir, name))) throw IOException("rename failed")
        } catch (e: IOException) {
            Log.w(TAG, "Saving $name failed", e)
        }
        updateSize()
    }

    @Synchronized
    fun delete(name: String) {
        File(dir, name).delete()
        updateSize()
    }

    private fun updateSize() {
        _sizeBytes.value = dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    companion object {
        const val TRAILS_FILE = "trails.tsv"
        const val PINS_FILE = "pins.tsv"
    }
}
