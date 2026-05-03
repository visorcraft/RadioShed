package com.visorcraft.radioshed.stations

import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.domain.PlayOrder
import com.visorcraft.radioshed.domain.PlaybackRule
import com.visorcraft.radioshed.domain.SmartQueryKind
import com.visorcraft.radioshed.domain.SourceRule
import kotlinx.coroutines.sync.withLock

/**
 * Built-in station definitions seeded once after the user's first scan
 * completes. Every preset can be edited or deleted from Library Settings;
 * the `is_built_in` flag survives so the user can tell at a glance.
 *
 * v1 set deliberately small. Forgotten Gems (highest-rated AND not played
 * in 90+ days), Workshop (empty user-curated playlist), per-decade auto-
 * stations, and per-genre auto-stations land in Phase 5 part 2 once the
 * Station Editor UI exists for users to actually fine-tune them.
 */
internal data class StationPreset(
    val name: String,
    val color: Int,
    val source: SourceRule,
    val playback: PlaybackRule,
    val sortPosition: Int,
    val customSubtitle: String? = null,
)

internal val DefaultPresets: List<StationPreset> = listOf(
    StationPreset(
        name = "Latest Hits",
        color = 0xFFFF7F1A.toInt(),    // RadioShed brand amber
        source = SourceRule.SmartQuery(SmartQueryKind.NewestN, limit = 100),
        playback = PlaybackRule(order = PlayOrder.SmartShuffle),
        sortPosition = 0,
        customSubtitle = "Newest 100 tracks · smart shuffle",
    ),
    StationPreset(
        name = "All Music",
        color = 0xFF607D8B.toInt(),
        source = SourceRule.SmartQuery(SmartQueryKind.NewestN, limit = 100_000),
        playback = PlaybackRule(order = PlayOrder.SmartShuffle),
        sortPosition = 1,
        customSubtitle = "Everything, smart shuffle",
    ),
    StationPreset(
        name = "Discover",
        color = 0xFF7B68EE.toInt(),
        source = SourceRule.SmartQuery(SmartQueryKind.LeastPlayed, limit = 100),
        playback = PlaybackRule(order = PlayOrder.SmartShuffle),
        sortPosition = 2,
        customSubtitle = "Tracks you've barely heard",
    ),
    StationPreset(
        name = "Top Rated",
        color = 0xFFFFC107.toInt(),
        source = SourceRule.SmartQuery(SmartQueryKind.HighestRated, limit = 100),
        playback = PlaybackRule(order = PlayOrder.SmartShuffle),
        sortPosition = 3,
        customSubtitle = "4★ and up",
    ),
    StationPreset(
        name = "Recently Added",
        color = 0xFF4ECDC4.toInt(),
        source = SourceRule.SmartQuery(SmartQueryKind.RecentlyAdded, limit = 50),
        playback = PlaybackRule(order = PlayOrder.SmartShuffle),
        sortPosition = 4,
        customSubtitle = "Newest 50 by file ctime",
    ),
    StationPreset(
        name = "Forgotten Gems",
        color = 0xFFFF6B6B.toInt(),
        source = SourceRule.SmartQuery(SmartQueryKind.ForgottenGems, limit = 100),
        playback = PlaybackRule(order = PlayOrder.SmartShuffle),
        sortPosition = 5,
        customSubtitle = "4★+ tracks you haven't heard in months",
    ),
)

/**
 * Idempotently seeds the built-in presets.
 *
 * Two-mode behaviour:
 *  - **First-run** (zero stations exist): seed the entire [DefaultPresets]
 *    set after a folder is registered. Skipped if no folders yet so the
 *    user sees their first scan complete before stations appear.
 *  - **Upgrade** (at least one station exists): seed only the built-in
 *    presets the user is **missing** by name. This is how new built-ins
 *    (e.g. Forgotten Gems, added in a later release) reach existing
 *    users without resurrecting presets they intentionally deleted —
 *    the test is "preset name not currently present", so a user who
 *    deletes "Latest Hits" won't have it re-created.
 *
 * Both paths preserve user customizations to existing presets — we
 * only insert; we never overwrite.
 */
class PresetSeeder(
    private val stationRepo: StationRepository,
    private val folderRepo: FolderRepository,
) {
    /**
     * Read-then-insert pattern needs a guard so concurrent callers
     * (onboarding completion + auto-rescan-on-launch both fire seed
     * paths in close sequence) don't both observe an empty
     * existingNames set and double-insert. The mutex is process-wide
     * because PresetSeeder is a Koin singleton.
     */
    private val seedMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun seedIfEmpty(): SeedResult = seedMutex.withLock {
        if (folderRepo.count() == 0L) return@withLock SeedResult.NoLibraryYet

        val existingNames = stationRepo.list().map { it.name }.toSet()
        val missing = DefaultPresets.filter { it.name !in existingNames }
        if (missing.isEmpty()) return@withLock SeedResult.AlreadyHasStations

        var inserted = 0
        for (preset in missing) {
            stationRepo.insert(
                name = preset.name,
                source = preset.source,
                playback = preset.playback,
                color = preset.color,
                sortPosition = preset.sortPosition,
                customSubtitle = preset.customSubtitle,
                isBuiltIn = true,
            )
            inserted += 1
        }
        SeedResult.Seeded(inserted)
    }

    sealed interface SeedResult {
        data object AlreadyHasStations : SeedResult
        data object NoLibraryYet : SeedResult
        data class Seeded(val count: Int) : SeedResult
    }
}
