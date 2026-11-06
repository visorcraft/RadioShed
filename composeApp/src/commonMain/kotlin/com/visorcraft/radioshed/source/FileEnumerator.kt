package com.visorcraft.radioshed.source

import kotlinx.coroutines.flow.Flow

/**
 * Walks the local filesystem under a root URI, emitting audio files.
 * Per-platform impls handle the actual traversal: java.io.File on Android,
 * NSFileManager / security-scoped bookmarks on iOS.
 */
interface FileEnumerator {
    // Walk [rootUri] (recursively if the corresponding folder is recursive)
    // and emit each audio file as a [DiscoveredTrack]. Apply [filter]
    // inline so we don't waste cycles on files we'd reject anyway.
    fun enumerate(
        rootUri: String,
        recursive: Boolean = true,
        filter: SourceFilter = SourceFilter(),
    ): Flow<DiscoveredTrack>
}
