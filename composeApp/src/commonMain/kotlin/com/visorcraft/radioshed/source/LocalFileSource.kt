package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.domain.Folder
import kotlinx.coroutines.flow.Flow

/**
 * The default v1 [MediaSource] backed by the local filesystem. Wraps a
 * platform-specific [FileEnumerator] and a domain [Folder] (registered
 * via [com.visorcraft.radioshed.data.FolderRepository]).
 */
class LocalFileSource(
    private val folder: Folder,
    private val enumerator: FileEnumerator,
) : MediaSource {

    override val id: String = "local:${folder.id}"

    override val displayName: String = folder.displayName

    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSearch = false,
        supportsStreaming = false,
        supportsPlaylists = false,
        supportsArtwork = false,
    )

    override fun listTracks(filter: SourceFilter): Flow<DiscoveredTrack> {
        // Merge folder-level globs with the caller-supplied filter so a
        // station can layer additional constraints on top of what the
        // user configured for the folder itself.
        val merged = filter.copy(
            includeGlobs = folder.includeGlobs + filter.includeGlobs,
            excludeGlobs = folder.excludeGlobs + filter.excludeGlobs,
        )
        return enumerator.enumerate(
            rootUri = folder.uri,
            recursive = folder.recursive,
            filter = merged,
        )
    }
}
