package com.visorcraft.radioshed.source

/**
 * iOS tag-write stub. Returns failure for every call.
 *
 * Real implementation deferred — AVAsset is read-only for ID3 frames,
 * so a working iOS writer needs either a custom mutator (per-format)
 * or a port of a library like TagLibSharp. Both are multi-day work
 * that needs Mac access.
 */
class IosTagWriter : TagWriter {
    override suspend fun write(
        uri: String,
        patch: TagPatch,
    ): Result<Unit> =
        Result.failure(
            UnsupportedOperationException(
                "Tag editing is not implemented on iOS yet.",
            ),
        )
}
