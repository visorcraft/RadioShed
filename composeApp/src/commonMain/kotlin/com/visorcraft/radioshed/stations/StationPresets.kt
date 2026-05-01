package com.visorcraft.radioshed.stations

import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.domain.PlayOrder
import com.visorcraft.radioshed.domain.PlaybackRule
import com.visorcraft.radioshed.domain.SmartQueryKind
import com.visorcraft.radioshed.domain.SourceRule

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
)

/**
 * Idempotently seeds the built-in presets. Skips entirely when the user
 * already has any stations (so we don't undo their edits or re-create
 * presets they intentionally deleted).
 */
class PresetSeeder(
    private val stationRepo: StationRepository,
    private val folderRepo: FolderRepository,
) {

    suspend fun seedIfEmpty(): SeedResult {
        if (stationRepo.count() > 0) return SeedResult.AlreadyHasStations
        if (folderRepo.count() == 0L) return SeedResult.NoLibraryYet

        var inserted = 0
        for (preset in DefaultPresets) {
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
        return SeedResult.Seeded(inserted)
    }

    sealed interface SeedResult {
        data object AlreadyHasStations : SeedResult
        data object NoLibraryYet : SeedResult
        data class Seeded(val count: Int) : SeedResult
    }
}
