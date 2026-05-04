package com.visorcraft.radioshed.source

/**
 * iOS stub. The Mac-side iOS scanner work hasn't landed yet; once it has,
 * this writes to `NSCachesDirectory` (or `NSApplicationSupportDirectory`)
 * with the same SHA-256-keyed scheme as the Android impl.
 */
class IosArtworkPersister : ArtworkPersister {
    override suspend fun persist(bytes: ByteArray): String? = null
    override suspend fun clearAll(): Int = 0
}
