package com.visorcraft.radioshed.source

class IosTrackSharer : TrackSharer {
    override suspend fun share(
        absolutePath: String,
        displayName: String,
    ): Boolean = false
}
