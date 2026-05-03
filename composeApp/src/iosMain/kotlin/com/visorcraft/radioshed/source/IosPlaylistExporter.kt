package com.visorcraft.radioshed.source

class IosPlaylistExporter : PlaylistExporter {
    override suspend fun exportM3u(
        playlistName: String,
        entries: List<M3uEntry>,
    ): Boolean = false
}
