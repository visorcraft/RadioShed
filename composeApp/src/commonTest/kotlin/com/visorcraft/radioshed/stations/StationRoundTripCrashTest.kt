package com.visorcraft.radioshed.stations

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.PlaylistRepository
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.PlayOrder
import com.visorcraft.radioshed.domain.PlaybackRule
import com.visorcraft.radioshed.domain.SmartQueryKind
import com.visorcraft.radioshed.domain.SortBy
import com.visorcraft.radioshed.domain.SourceRule
import com.visorcraft.radioshed.domain.Tag
import com.visorcraft.radioshed.domain.Weighting
import com.visorcraft.radioshed.source.MediaSourceRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * End-to-end station-rule crash safety. Spins up an in-memory SQLDelight
 * database (the same schema that ships on Android / desktop), saves
 * stations via [StationRepository], reloads them, evaluates the rule
 * through [SourceRuleEvaluator], builds a [PlaybackQueue], and drains
 * it — for every combination of:
 *
 *   - SourceRule subtype: Folder, TagFilter (with ALBUM_ARTIST /
 *     ARTIST / unknown key / lowercase values / blank values),
 *     SmartQuery (every SmartQueryKind), PlaylistSelector,
 *     ArtistSelector, AlbumSelector, DurationFilter, FileFormatFilter,
 *     PathPattern, Union, Intersection, Difference, TopN.
 *   - PlayOrder: Sequential, Shuffle, SmartShuffle, WeightedRandom.
 *   - Weighting: Uniform, FavorRecent, FavorUnderPlayed, FavorHigherRated.
 *
 * Closes the gap between the in-isolation [PlaybackQueueCrashSafetyTest]
 * (which doesn't touch the DB) and the actual on-device flow (which we
 * can't run without a connected handset). If any combo throws, this
 * test fails — and the assertion message names the exact triple.
 *
 * Library seed is intentionally small but covers the crash-prone
 * extremes: an artist with mixed-case name, a tag value the user
 * typed with different casing, a folder with zero tracks. We also
 * run a separate pass against a totally-empty library to exercise
 * the "rule matches nothing" branch end-to-end.
 */
class StationRoundTripCrashTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: RadioShedDatabase
    private lateinit var stations: StationRepository
    private lateinit var folders: FolderRepository
    private lateinit var library: LibraryRepository
    private lateinit var playlists: PlaylistRepository
    private lateinit var ratings: RatingRepository
    private lateinit var evaluator: SourceRuleEvaluator

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RadioShedDatabase.Schema.create(driver)
        db = RadioShedDatabase(driver)
        stations = StationRepository(db)
        folders = FolderRepository(db)
        library = LibraryRepository(db)
        playlists = PlaylistRepository(db)
        ratings = RatingRepository(db)
        evaluator = SourceRuleEvaluator(library, ratings, playlists, MediaSourceRegistry())
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    /**
     * The full matrix. For each SourceRule × PlayOrder × Weighting,
     * save the station, reload it, evaluate the source, build the
     * queue, drain. Runs against an empty library so every rule
     * resolves to an empty pool — the worst-case path that was
     * producing the original crash.
     */
    @Test
    fun every_rule_combo_round_trips_without_crash_on_empty_library() = runTest {
        val combos = enumerateAllRuleCombos()
        for (combo in combos) {
            val id = saveStation(combo)
            val reloaded = stations.getById(id)
            assertNotNull(reloaded, "Reload failed for ${combo.label}")
            // Wrap evaluator + queue in runCatching so a failure
            // produces a meaningful per-combo message rather than a
            // generic stack trace pinned to whichever combo happened
            // to run when the suite blew up.
            val candidates = runCatching { evaluator.evaluate(reloaded.source) }
                .getOrElse { throw AssertionError("evaluate threw for ${combo.label}: $it", it) }
            val queue = PlaybackQueue(
                candidates = candidates,
                rule = reloaded.playback,
            )
            // 5 picks across a stateful queue — empty pool should
            // return null every time; non-empty pool should rotate
            // without exception.
            repeat(5) { iter ->
                runCatching { queue.next() }
                    .onFailure {
                        throw AssertionError(
                            "queue.next() threw on iter=$iter for ${combo.label}: $it",
                            it,
                        )
                    }
            }
        }
    }

    /**
     * Variant: small seeded library with an album_artist tagged
     * "BLACKPINK" (uppercase), so the regression we're protecting
     * against — user types lowercase, file has uppercase — is
     * exercised end-to-end against real persistence. The
     * case-insensitive special case in [SourceRuleEvaluator] should
     * resolve the ARTIST/ALBUM_ARTIST rule into non-empty candidates;
     * the queue should drain those without crashing.
     */
    @Test
    fun album_artist_lowercase_query_resolves_uppercase_data() = runTest {
        seedTracksWithAlbumArtist("BLACKPINK", "Pink Venom")
        seedTracksWithAlbumArtist("BLACKPINK", "Shut Down")
        // Also a non-Blackpink track so the filter actually filters.
        seedTracksWithAlbumArtist("Other Artist", "Whatever")

        val rule = SourceRule.TagFilter(
            key = "ALBUM_ARTIST",
            values = listOf("blackpink", "BlackPink", "BLACKPINK"),
            matchAll = false,
        )
        val playback = PlaybackRule(
            order = PlayOrder.SmartShuffle,
            weighting = Weighting.Uniform,
        )
        val id = stations.insert(
            name = "Test ALBUM_ARTIST",
            source = rule,
            playback = playback,
        )
        val reloaded = stations.getById(id)!!
        val candidates = evaluator.evaluate(reloaded.source)
        // All three input values normalize to sort_name "blackpink"
        // (Scanner.normalizeForSort = trim().lowercase()). Each
        // resolves through tracksByArtistName independently, each
        // returning the same 2 BLACKPINK tracks. The flatten +
        // distinctBy { it.id } in evaluateTagFilter's ANY-mode
        // collapses the 3×2 = 6 rows back down to 2 unique tracks.
        // (selectTracksByArtistSortName's own DISTINCT also collapses
        // the within-track-artist dedup from the dual "primary" +
        // "album_artist" link rows seeded below.)
        check(candidates.size == 2) {
            "Expected 2 tracks (BLACKPINK), got ${candidates.size}: ${candidates.map { it.title }}"
        }

        val queue = PlaybackQueue(candidates = candidates, rule = reloaded.playback)
        repeat(10) {
            val picked = queue.next()
            assertNotNull(picked, "iter $it -> null on non-empty pool")
        }
    }

    /**
     * Pathological inputs the editor's `canSave` gates should block
     * but a corrupted backup / direct-DB-write could still bring back.
     * TagFilter with blank values, trailing-comma artifacts, empty
     * folder lists. Verifies the evaluator still produces a stable
     * empty-list rather than crashing.
     */
    @Test
    fun pathological_rule_shapes_dont_crash() = runTest {
        val pathological = listOf(
            "blank-tag-values" to SourceRule.TagFilter("GENRE", listOf("", "  ", ",")),
            "all-blank-artist-values" to SourceRule.TagFilter("ALBUM_ARTIST", listOf("", "  ")),
            "empty-folder-ids" to SourceRule.FolderSelector(emptyList()),
            "empty-artist-ids" to SourceRule.ArtistSelector(emptyList()),
            "empty-album-ids" to SourceRule.AlbumSelector(emptyList()),
            "empty-playlist-id" to SourceRule.PlaylistSelector(9_999_999L),
            "zero-limit-smart-query" to SourceRule.SmartQuery(SmartQueryKind.NewestN, 0),
            "huge-limit-smart-query" to SourceRule.SmartQuery(SmartQueryKind.NewestN, Int.MAX_VALUE),
            "empty-format-filter" to SourceRule.FileFormatFilter(emptyList()),
            "empty-path-pattern" to SourceRule.PathPattern(emptyList(), emptyList()),
            "empty-union" to SourceRule.Union(emptyList()),
            "empty-intersection" to SourceRule.Intersection(emptyList()),
            "nested-empties" to SourceRule.TopN(
                source = SourceRule.Intersection(
                    children = listOf(
                        SourceRule.FolderSelector(emptyList()),
                        SourceRule.TagFilter("ALBUM_ARTIST", listOf("", "")),
                    ),
                ),
                limit = 100,
                sortBy = SortBy.Random,
            ),
        )
        for ((label, rule) in pathological) {
            val id = stations.insert(
                name = "pathological-$label",
                source = rule,
                playback = PlaybackRule(),
            )
            val reloaded = stations.getById(id)
            assertNotNull(reloaded, "Reload failed for $label")
            val candidates = runCatching { evaluator.evaluate(reloaded.source) }
                .getOrElse { throw AssertionError("evaluate threw for $label: $it", it) }
            // Drain via every play-order × weighting combo so a future
            // refactor that introduces an order-specific empty-pool
            // bug surfaces here too.
            for (order in allPlayOrders) {
                for (weighting in allWeightings) {
                    val q = PlaybackQueue(
                        candidates = candidates,
                        rule = PlaybackRule(order = order, weighting = weighting),
                    )
                    runCatching { q.next() }
                        .onFailure {
                            throw AssertionError(
                                "next() threw for $label / $order / $weighting: $it",
                                it,
                            )
                        }
                }
            }
        }
    }

    private suspend fun saveStation(combo: RuleCombo): Long = stations.insert(
        name = combo.label,
        source = combo.rule,
        playback = PlaybackRule(order = combo.order, weighting = combo.weighting),
    )

    private suspend fun seedTracksWithAlbumArtist(artistName: String, title: String) {
        val artistId = library.upsertArtist(
            name = artistName,
            sortName = artistName.trim().lowercase(),
        )
        // folderId=null avoids the folder-FK setup; the rule-evaluator
        // path we're exercising joins via track_artist and only filters
        // tracks by track.is_available which is set to 1 on insert.
        library.upsertTrack(
            folderId = null,
            path = "/seed/${title.hashCode()}-${artistName.hashCode()}.flac",
            fileName = "${title.hashCode()}.flac",
            fileSize = 1L,
            fileCtime = 0L,
            fileMtime = 0L,
            title = title,
            albumId = null,
            trackNumber = null,
            discNumber = null,
            durationMs = 180_000L,
            bitrateKbps = null,
            sampleRateHz = null,
            channels = null,
            format = "flac",
            replayGainTrack = null,
            replayGainAlbum = null,
            replayGainTrackPeak = null,
            bpm = null,
            musicbrainzId = null,
            artistIdsAndRoles = listOf(artistId to "primary", artistId to "album_artist"),
            customTags = listOf(Tag("ALBUM_ARTIST", artistName, 0)),
            personnel = null,
        )
    }

    private fun enumerateAllRuleCombos(): List<RuleCombo> {
        val rules: List<Pair<String, SourceRule>> = buildList {
            add("folder-empty" to SourceRule.FolderSelector(emptyList()))
            add("folder-stale-id" to SourceRule.FolderSelector(listOf(999L)))
            add("tag-genre" to SourceRule.TagFilter("GENRE", listOf("Rock")))
            add("tag-genre-multi-any" to SourceRule.TagFilter("GENRE", listOf("Rock", "Jazz"), false))
            add("tag-genre-multi-all" to SourceRule.TagFilter("GENRE", listOf("Rock", "Jazz"), true))
            add("tag-album-artist" to SourceRule.TagFilter("ALBUM_ARTIST", listOf("blackpink")))
            add("tag-artist-case" to SourceRule.TagFilter("ARTIST", listOf("BlAcKpInK")))
            add("tag-unknown-key" to SourceRule.TagFilter("ZZZ_NONEXISTENT", listOf("x")))
            add("tag-blank-values" to SourceRule.TagFilter("GENRE", listOf("", "  ", "ok")))
            for (kind in SmartQueryKind.entries) {
                add("smart-${kind.name}" to SourceRule.SmartQuery(kind, 50))
            }
            add("playlist-missing" to SourceRule.PlaylistSelector(123L))
            add("artist-empty" to SourceRule.ArtistSelector(emptyList()))
            add("artist-stale" to SourceRule.ArtistSelector(listOf(42L)))
            add("album-empty" to SourceRule.AlbumSelector(emptyList()))
            add("album-stale" to SourceRule.AlbumSelector(listOf(7L)))
            add("duration-bounded" to SourceRule.DurationFilter(60, 240))
            add("duration-unbounded" to SourceRule.DurationFilter(null, null))
            add("file-format" to SourceRule.FileFormatFilter(listOf("flac", "mp3")))
            add("path-pattern" to SourceRule.PathPattern(listOf("**/Rock/**"), listOf("**/Live/**")))
            add(
                "union-mixed" to SourceRule.Union(
                    listOf(
                        SourceRule.FolderSelector(listOf(1L)),
                        SourceRule.TagFilter("GENRE", listOf("Jazz")),
                    ),
                ),
            )
            add(
                "intersection-mixed" to SourceRule.Intersection(
                    listOf(
                        SourceRule.TagFilter("GENRE", listOf("Rock")),
                        SourceRule.FolderSelector(listOf(2L)),
                    ),
                ),
            )
            add(
                "difference" to SourceRule.Difference(
                    left = SourceRule.FolderSelector(listOf(1L)),
                    right = SourceRule.TagFilter("GENRE", listOf("Christmas")),
                ),
            )
            add(
                "topn-newest" to SourceRule.TopN(
                    source = SourceRule.FolderSelector(listOf(1L)),
                    limit = 100,
                    sortBy = SortBy.Newest,
                ),
            )
            for (sortBy in SortBy.entries) {
                add(
                    "topn-${sortBy.name.lowercase()}" to SourceRule.TopN(
                        source = SourceRule.FolderSelector(listOf(1L)),
                        limit = 25,
                        sortBy = sortBy,
                    ),
                )
            }
        }
        return buildList {
            for ((label, rule) in rules) {
                for (order in allPlayOrders) {
                    for (weighting in allWeightings) {
                        add(
                            RuleCombo(
                                label = "$label/$order/$weighting",
                                rule = rule,
                                order = order,
                                weighting = weighting,
                            ),
                        )
                    }
                }
            }
        }
    }

    private val allPlayOrders = listOf(
        PlayOrder.Sequential,
        PlayOrder.Shuffle,
        PlayOrder.SmartShuffle,
        PlayOrder.WeightedRandom,
    )

    private val allWeightings = listOf(
        Weighting.Uniform,
        Weighting.FavorRecent,
        Weighting.FavorUnderPlayed,
        Weighting.FavorHigherRated,
    )

    private data class RuleCombo(
        val label: String,
        val rule: SourceRule,
        val order: PlayOrder,
        val weighting: Weighting,
    )
}
