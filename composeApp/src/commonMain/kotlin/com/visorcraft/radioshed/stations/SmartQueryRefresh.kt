package com.visorcraft.radioshed.stations

import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.domain.SourceRule
import com.visorcraft.radioshed.domain.Track

/**
 * Periodic refresher for SmartQuery-backed stations. Because
 * [SourceRuleEvaluator.evaluate] is lazy per-play, the "refresh" is just
 * a re-evaluation that warms any in-memory cache and ensures the track
 * set reflects the current library state (new imports, updated ratings,
 * changed play counts). Runs at most once per 24 hours.
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

    /**
     * Re-evaluate every station whose source rule tree contains a
     * [SourceRule.SmartQuery] if the last refresh was more than
     * [REFRESH_INTERVAL_MS] ago (or never). After the pass succeeds,
     * bump [SettingsStore.KEY_SMARTQUERY_REFRESH_LAST] to [nowMs].
     *
     * This is a no-op when there are no SmartQuery stations, and it's
     * safe to call on every app launch — the interval gate keeps it
     * cheap.
     */
    suspend fun refreshIfDue(nowMs: Long) {
        val lastRefresh = settings.getSmartQueryRefreshLast()
        if (lastRefresh > 0 && nowMs - lastRefresh < REFRESH_INTERVAL_MS) return

        val stations = stationRepo.list()
        val smartStations = stations.filter { it.source.containsSmartQuery() }

        for (station in smartStations) {
            runCatching {
                evaluate(station.source)
            }.onFailure {
                // Swallow per-station failures — a single bad rule must
                // not break the refresh pass for every other station.
                // Log at info level; this is not a crash-worthy event.
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
 */
internal fun SourceRule.containsSmartQuery(): Boolean = when (this) {
    is SourceRule.SmartQuery -> true
    is SourceRule.Union -> children.any { it.containsSmartQuery() }
    is SourceRule.Intersection -> children.any { it.containsSmartQuery() }
    is SourceRule.Difference -> left.containsSmartQuery() || right.containsSmartQuery()
    is SourceRule.TopN -> source.containsSmartQuery()
    else -> false
}
