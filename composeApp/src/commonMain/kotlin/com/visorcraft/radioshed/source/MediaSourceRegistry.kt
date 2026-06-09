package com.visorcraft.radioshed.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A registry of all active [MediaSource]s in the application.
 */
class MediaSourceRegistry {
    private val _sources = MutableStateFlow<Map<String, MediaSource>>(emptyMap())
    val sources = _sources.asStateFlow()

    fun register(source: MediaSource) {
        _sources.value += (source.id to source)
    }

    fun unregister(id: String) {
        _sources.value -= id
    }

    fun getById(id: String): MediaSource? = _sources.value[id]
}
