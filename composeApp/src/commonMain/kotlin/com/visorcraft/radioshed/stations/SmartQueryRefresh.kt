package com.visorcraft.radioshed.stations

import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.domain.SourceRule
import com.visorcraft.radioshed.domain.Track
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Daily pass over SmartQuery-backed stations: re-runs the injected
 * evaluator on each one (at most once per 24 hours) and discards the
 * results. Be honest about what that buys today: nothing is cached or
 * persisted — [SourceRuleEvaluator] keeps no cache, and
 * [StationPlayback] re-evaluates from scratch on every station start —
 * so the only durable effect of a pass is bumping
 * [SettingsStore.KEY_SMARTQUERY_REFRESH_LAST]. It does not precompute
 * or speed up anything. Whether the pass should persist its results
 * (or be removed) is a deferred product decision; keep this KDoc in
 * sync if that changes.
 *
 * The refresh cadence is gated by [SettingsStore.KEY_SMARTQUERY_REFRESH_LAST].
 * Failures on individual stations are swallowed with a log so a single
 * bad SmartQuery rule can't break the whole refresh pass.
 */
class SmartQueryRefresh(
    private val settings: SettingsStore,
    private val stationRepo: StationRepository,
    private val evaluate: suspend (SourceRule) -> List<Track>,
) {

    private val mutex = Mutex()

    /**
     * Re-evaluate every station whose source rule tree contains a
     * [SourceRule.SmartQuery] if the last refresh was more than
     * [REFRESH_INTERVAL_MS] ago (or never). After the pass completes
     * (including any swallowed per-station failures), bump
     * [SettingsStore.KEY_SMARTQUERY_REFRESH_LAST] to [nowMs].
     *
     * This is a no-op when there are no SmartQuery stations, and it's
     * safe to call on every app launch — the interval gate keeps it
     * cheap. Concurrent callers serialize through an internal [Mutex]
     * so overlapping launches (e.g. app launch + a daily tick) don't
     * double-evaluate.
     */
    suspend fun refreshIfDue(nowMs: Long) = mutex.withLock {
        val lastRefresh = settings.getSmartQueryRefreshLast()
        if (lastRefresh > 0 && nowMs - lastRefresh < REFRESH_INTERVAL_MS) return@withLock

        val stations = stationRepo.list()
        val smartStations = stations.filter { it.source.containsSmartQuery() }

        for (station in smartStations) {
            runCatching {
                evaluate(station.source)
            }.onFailure {
                // Swallow per-station failures — a single bad rule must
                // not break the refresh pass for every other station.
                println("SmartQueryRefresh: failed for station ${station.id} — ${it.message}")
            }
        }

        settings.setSmartQueryRefreshLast(nowMs)
    }

    companion object {
        /** 24 hours in milliseconds. */
        private const val REFRESH_INTERVAL_MS = 86_400_000L
    }
}

/**
 * Walk a [SourceRule] tree and return true if any node is a
 * [SourceRule.SmartQuery]. Used by [SmartQueryRefresh] to decide
 * which stations need periodic re-evaluation.
 *
 * All branches are listed explicitly so that adding a new [SourceRule]
 * subtype produces an exhaustiveness warning rather than silently
 * falling through to false.
 */
internal fun SourceRule.containsSmartQuery(): Boolean = when (this) {
    is SourceRule.SmartQuery -> true
    is SourceRule.FolderSelector -> false
    is SourceRule.TagFilter -> false
    is SourceRule.PlaylistSelector -> false
    is SourceRule.ArtistSelector -> false
    is SourceRule.AlbumSelector -> false
    is SourceRule.DurationFilter -> false
    is SourceRule.FileFormatFilter -> false
    is SourceRule.PathPattern -> false
    is SourceRule.MediaSourceSelector -> false
    is SourceRule.Union -> children.any { it.containsSmartQuery() }
    is SourceRule.Intersection -> children.any { it.containsSmartQuery() }
    is SourceRule.Difference -> left.containsSmartQuery() || right.containsSmartQuery()
    is SourceRule.TopN -> source.containsSmartQuery()
}
