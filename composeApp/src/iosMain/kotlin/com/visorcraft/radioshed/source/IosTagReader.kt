package com.visorcraft.radioshed.source

/**
 * v1 stub for iOS. Returns minimal info derived from the file path so the
 * scanner pipeline doesn't crash on iOS while we don't have AVAsset wiring.
 *
 * Full implementation (planned in Phase 3 part 2 with Mac access):
 *   - AVAsset.commonMetadata for ID3v2 / iTunes metadata
 *   - Custom FLAC/Vorbis comment parser (AVAsset doesn't expose them)
 *   - Custom Opus header parser for embedded gain
 *   - mobile-ffmpeg fallback for WMA/APE/DSD
 */
class IosTagReader : TagReader {
    override suspend fun read(uri: String): ParsedTags? =
        ParsedTags(
            title = uri.substringAfterLast('/').substringBeforeLast('.').takeIf { it.isNotBlank() },
        )
}
