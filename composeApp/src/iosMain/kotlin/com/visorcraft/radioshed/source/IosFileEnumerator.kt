package com.visorcraft.radioshed.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * v1 stub. Real implementation lands in a follow-up session that needs Mac
 * access for testing — it wraps NSFileManager + security-scoped bookmarks
 * resolved from the iOS document picker.
 *
 * For now, returns an empty flow so the scanner runs cleanly on iOS without
 * crashing; the iOS UI just shows zero tracks until storage integration
 * lands in Phase 3 part 2.
 */
class IosFileEnumerator : FileEnumerator {
    override fun enumerate(
        rootUri: String,
        recursive: Boolean,
        filter: SourceFilter,
    ): Flow<DiscoveredTrack> = emptyFlow()
}
