package com.visorcraft.radioshed.source

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A mock implementation of [MediaSource] used for testing and demonstrating
 * cloud-based track discovery without requiring a real server.
 */
class MockCloudSource(
    override val id: String = "mock-cloud",
    override val displayName: String = "Mock Cloud",
) : MediaSource {
    override val capabilities: SourceCapabilities =
        SourceCapabilities(
            supportsSearch = true,
            supportsStreaming = true,
            supportsPlaylists = true,
            supportsArtwork = true,
        )

    private val mockTracks =
        listOf(
            DiscoveredTrack(
                uri = "https://mock-cloud.com/tracks/1",
                fileName = "Song One.mp3",
                fileSize = 5242880L,
                fileCtime = 1672531200000L,
                fileMtime = 1672531200000L,
                format = "mp3",
            ),
            DiscoveredTrack(
                uri = "https://mock-cloud.com/tracks/2",
                fileName = "Song Two.flac",
                fileSize = 10485760L,
                fileCtime = 1672531200000L,
                fileMtime = 1672531200000L,
                format = "flac",
            ),
            DiscoveredTrack(
                uri = "https://mock-cloud.com/tracks/3",
                fileName = "Song Three.m4a",
                fileSize = 7864320L,
                fileCtime = 1672531200000L,
                fileMtime = 1672531200000L,
                format = "m4a",
            ),
        )

    override fun listTracks(filter: SourceFilter): Flow<DiscoveredTrack> =
        flow {
            // Simulate network latency for initial connection
            delay(800)

            for (track in mockTracks) {
                // Check if track matches filter formats
                if (filter.formats.isNotEmpty() && track.format !in filter.formats) {
                    continue
                }

                // Simulate per-item latency for a realistic "streaming" discovery feel
                delay(200)
                emit(track)
            }
        }
}
