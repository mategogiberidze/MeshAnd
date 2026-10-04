package com.meshand.app.data.repository

import com.meshand.app.data.storage.LocalStore
import com.meshand.app.data.storage.StoreCodec
import com.meshand.app.domain.TrailBook
import com.meshand.app.domain.model.MeshNode
import com.meshand.app.domain.model.TrailPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * Feeds every node-list change into a [TrailBook] and publishes each node's trail, trimmed to
 * the configured length (re-checked every minute, since old points age out without new data).
 * Trails are loaded from [store] at start and saved back at most every [SAVE_EVERY_MS].
 */
class TrailRecorder(
    private val nodes: StateFlow<List<MeshNode>>,
    trailMinutes: StateFlow<Int>,
    private val scope: CoroutineScope,
    private val store: LocalStore,
) {
    private val book = TrailBook()
    private val _trails = MutableStateFlow<Map<Long, List<TrailPoint>>>(emptyMap())

    /** Node id → positions within the trail window, oldest first. */
    val trails: StateFlow<Map<Long, List<TrailPoint>>> = _trails.asStateFlow()

    /** Set when [trails] changed since the last save. Only touched on the main thread. */
    private var dirty = false

    init {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                store.read(LocalStore.TRAILS_FILE)?.let(StoreCodec::decodeTrails).orEmpty()
            }
            book.restore(saved)
            _trails.value = book.snapshot()
            combine(nodes, trailMinutes, minuteTicker()) { all, minutes, now -> Triple(all, minutes, now) }
                .collect { (all, minutes, _) ->
                    val now = Instant.now()
                    val recorded = book.record(all, now)
                    val pruned = book.prune(Duration.ofMinutes(minutes.toLong()), now)
                    if (recorded || pruned) publish()
                }
        }
        scope.launch {
            while (true) {
                delay(SAVE_EVERY_MS)
                if (dirty) save()
            }
        }
    }

    /** Restarts one node's trail, or every trail when [nodeId] is null, from the current position. */
    fun reset(nodeId: Long?) {
        if (nodeId == null) book.clear() else book.reset(nodeId)
        book.record(nodes.value, Instant.now())
        publish()
    }

    /** "Clear saved data": every trail restarts from now, and the file is rewritten right away. */
    fun clearSaved() {
        reset(null)
        scope.launch { save() }
    }

    private fun publish() {
        _trails.value = book.snapshot()
        dirty = true
    }

    private suspend fun save() {
        dirty = false
        val text = StoreCodec.encodeTrails(_trails.value)
        withContext(Dispatchers.IO) { store.write(LocalStore.TRAILS_FILE, text) }
    }

    private fun minuteTicker() = flow {
        while (true) {
            emit(Instant.now())
            delay(60_000)
        }
    }

    private companion object {
        const val SAVE_EVERY_MS = 10_000L
    }
}
