package com.visorcraft.radioshed.source

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A registry of all active [MediaSource]s in the application.
 *
 * Mutations are serialized through [Mutex] so concurrent registrations
 * from different coroutines (e.g. app-launch + settings-dialog "Test &
 * save") don't race on the read-modify-write of the backing map.
 */
class MediaSourceRegistry {
    private val _sources = MutableStateFlow<Map<String, MediaSource>>(emptyMap())
    val sources = _sources.asStateFlow()
    private val mutex = Mutex()

    suspend fun register(source: MediaSource) =
        mutex.withLock {
            _sources.value += (source.id to source)
        }

    suspend fun unregister(id: String) =
        mutex.withLock {
            _sources.value -= id
        }

    fun getById(id: String): MediaSource? = _sources.value[id]
}
