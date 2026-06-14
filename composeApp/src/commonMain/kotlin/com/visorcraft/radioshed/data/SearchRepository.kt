package com.visorcraft.radioshed.data

import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.Album
import com.visorcraft.radioshed.domain.Artist
import com.visorcraft.radioshed.domain.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Library search. v1 uses LIKE because SQLDelight 2.1's parser doesn't
 * handle FTS5 MATCH cleanly; revisit when the parser catches up. Acceptable
 * for libraries up to ~10k tracks; above that we'd want FTS5 or a custom
 * inverted index.
 *
 * Supports a small DSL via [SearchDsl]:
 * `artist:Hendrix year:>1968 genre:rock`. When the query contains any
 * `field:value` token the DSL path is used; otherwise we fall back to
 * the legacy free-text search across title/artist/album. The DSL path
 * narrows results in SQL where possible and finishes with an in-memory
 * intersection for fields that don't have a dedicated index path
 * (year — pulled off `album.year`, applied post-fetch).
 */
class SearchRepository(private val db: RadioShedDatabase) {
    suspend fun searchTracks(
        query: String,
        limit: Long = 50,
    ): List<Track> {
        val parsed = SearchDsl.parse(query)
        return if (parsed.hasFieldTerms) {
            searchTracksDsl(parsed, limit)
        } else {
            withContext(IODispatcher) {
                val pattern = query.toLikePattern() ?: return@withContext emptyList()
                // Free text matches against title OR primary-artist name
                // OR album title — the three columns the filter chip
                // hint ("Title, artist, or album") promises. Title-only
                // was a regression: typing the artist name then finding
                // only the tracks whose *title* happened to repeat that
                // name (feat. credits) felt broken — see "search 'roddy
                // ricch' missed 'The Box'". Fan-out is three indexed
                // LIKE queries deduped by track id; cheap up to the
                // ~10k-track LIKE ceiling already documented for v1.
                val titleIds =
                    db.libraryQueries.searchTracksByTitleLike(pattern, limit)
                        .executeAsList().map { it.id }
                val artistIds =
                    db.libraryQueries.searchArtistsByNameLike(pattern, limit)
                        .executeAsList()
                        .flatMap { artist ->
                            db.libraryQueries.selectTracksByArtist(artist.id)
                                .executeAsList().map { it.id }
                        }
                val albumIds =
                    db.libraryQueries.searchAlbumsByTitleLike(pattern, limit)
                        .executeAsList()
                        .flatMap { album ->
                            db.libraryQueries.selectTracksByAlbum(album.id)
                                .executeAsList().map { it.id }
                        }
                val merged = LinkedHashSet<Long>(titleIds.size + artistIds.size + albumIds.size)
                merged.addAll(titleIds)
                merged.addAll(artistIds)
                merged.addAll(albumIds)
                if (merged.isEmpty()) return@withContext emptyList()
                val capped = merged.take(limit.toInt())
                db.libraryQueries.selectTracksByIds(capped)
                    .executeAsList()
                    .map { it.toDomain() }
                    // Re-apply the merge order (LIKE on title first,
                    // then artist matches, then album matches) so
                    // "best-match" results bubble to the top — the
                    // selectTracksByIds query returns rows in id order,
                    // not match-priority order.
                    .sortedBy { capped.indexOf(it.id) }
            }
        }
    }

    suspend fun searchArtists(
        query: String,
        limit: Long = 50,
    ): List<Artist> =
        withContext(IODispatcher) {
            val parsed = SearchDsl.parse(query)
            // Artist results are scoped only by the free-text portion of
            // the query; field terms like artist:/album:/year: don't make
            // sense as artist filters except for `artist:X`, which we
            // collapse into the search text.
            val text =
                parsed.terms.asSequence()
                    .mapNotNull {
                        when (it) {
                            is SearchTerm.Free -> it.text
                            is SearchTerm.Field -> if (it.key == "artist") it.value else null
                            is SearchTerm.YearRange -> null
                        }
                    }
                    .joinToString(" ")
                    .trim()
            val pattern = text.toLikePattern() ?: return@withContext emptyList()
            db.libraryQueries.searchArtistsByNameLike(pattern, limit)
                .executeAsList().map { it.toDomain() }
        }

    suspend fun searchAlbums(
        query: String,
        limit: Long = 50,
    ): List<Album> =
        withContext(IODispatcher) {
            val parsed = SearchDsl.parse(query)
            val text =
                parsed.terms.asSequence()
                    .mapNotNull {
                        when (it) {
                            is SearchTerm.Free -> it.text
                            is SearchTerm.Field -> if (it.key == "album") it.value else null
                            is SearchTerm.YearRange -> null
                        }
                    }
                    .joinToString(" ")
                    .trim()
            val pattern = text.toLikePattern() ?: return@withContext emptyList()
            db.libraryQueries.searchAlbumsByTitleLike(pattern, limit)
                .executeAsList().map { it.toDomain() }
        }

    suspend fun searchAll(
        query: String,
        limitPerCategory: Long = 20,
    ): SearchResults =
        withContext(IODispatcher) {
            // Run the three sub-queries concurrently. SQLite serializes writes
            // but multiple concurrent reads work fine, and the dispatcher
            // overlap matters under the 200 ms search debounce on larger
            // libraries (otherwise the three queries run sequentially even
            // though the dispatcher could parallelize them).
            coroutineScope {
                val tracks = async { searchTracks(query, limitPerCategory) }
                val artists = async { searchArtists(query, limitPerCategory) }
                val albums = async { searchAlbums(query, limitPerCategory) }
                SearchResults(
                    tracks = tracks.await(),
                    artists = artists.await(),
                    albums = albums.await(),
                )
            }
        }

    /**
     * DSL execution path. Resolves each field term to a candidate track
     * id set, intersects them, then applies year + free-text last. We
     * over-fetch slightly (limit * 4) at each leaf so the post-
     * intersection result still has [limit] items in the common case.
     */
    private suspend fun searchTracksDsl(
        parsed: ParsedSearchQuery,
        limit: Long,
    ): List<Track> =
        withContext(IODispatcher) {
            val fieldTerms = parsed.terms.filterIsInstance<SearchTerm.Field>()
            val yearTerms = parsed.terms.filterIsInstance<SearchTerm.YearRange>()
            val perLeafLimit = (limit * 4).coerceAtLeast(50)

            // Resolve each field term to a track-id set. Empty result for
            // any term short-circuits to empty (AND semantics — if one
            // filter has zero matches the whole query has zero matches).
            var idSet: Set<Long>? = null
            for (term in fieldTerms) {
                val ids = idsForFieldTerm(term, perLeafLimit) ?: continue
                idSet = if (idSet == null) ids.toSet() else idSet.intersect(ids)
                if (idSet.isEmpty()) return@withContext emptyList()
            }

            // Free text: run the existing LIKE search and intersect.
            val freeText = parsed.freeText
            if (freeText.isNotBlank()) {
                val pattern = freeText.toLikePattern()
                if (pattern != null) {
                    val freeIds =
                        db.libraryQueries
                            .searchTracksByTitleLike(pattern, perLeafLimit)
                            .executeAsList()
                            .map { it.id }
                    idSet = if (idSet == null) freeIds.toSet() else idSet.intersect(freeIds)
                    if (idSet.isEmpty()) return@withContext emptyList()
                }
            }

            // No constraints found at all (every term failed to parse) →
            // empty rather than a "match everything" surprise.
            val ids = idSet ?: return@withContext emptyList()

            // Resolve ids → tracks via a single IN(?) query. Previously
            // this loop fired one selectTrackById per id, which on a
            // typical "artist:Beatles" search hit the DB ~200 times for
            // a single keystroke and dominated query latency. Cap inflate
            // is fine here — `tracks` is trimmed to `limit` after year
            // filtering since album.year isn't on the track row.
            val tracks =
                db.libraryQueries.selectTracksByIds(ids)
                    .executeAsList()
                    .map { it.toDomain() }
            val yearFiltered =
                if (yearTerms.isEmpty()) {
                    tracks
                } else {
                    // Pre-resolve every distinct album id to its year in ONE
                    // query, then look up from a map per-track. Old code did
                    // one selectAlbumById per track which hit the DB N times
                    // again on top of the track lookup.
                    val albumIds = tracks.mapNotNull { it.albumId }.distinct()
                    val yearByAlbum: Map<Long, Int?> =
                        if (albumIds.isEmpty()) {
                            emptyMap()
                        } else {
                            db.libraryQueries.selectAlbumsByIds(albumIds)
                                .executeAsList()
                                .associate { it.id to it.year?.toInt() }
                        }
                    tracks.filter { t ->
                        val year = t.albumId?.let { yearByAlbum[it] }
                        yearTerms.all { range ->
                            if (year == null) {
                                false
                            } else {
                                (range.min == null || year >= range.min) &&
                                    (range.max == null || year <= range.max)
                            }
                        }
                    }
                }
            yearFiltered.take(limit.toInt())
        }

    /**
     * Resolve one DSL [SearchTerm.Field] to a list of matching track ids.
     * Returns null when the term doesn't translate to a track filter
     * (caller treats null as "skip").
     */
    private fun idsForFieldTerm(
        term: SearchTerm.Field,
        limit: Long,
    ): List<Long>? {
        val pattern = term.value.toLikePattern() ?: return emptyList()
        return when (term.key) {
            "title" ->
                db.libraryQueries
                    .searchTracksByTitleLike(pattern, limit)
                    .executeAsList().map { it.id }
            "artist" ->
                db.libraryQueries
                    .searchArtistsByNameLike(pattern, limit).executeAsList()
                    .flatMap { artist ->
                        db.libraryQueries.selectTracksByArtist(artist.id)
                            .executeAsList().map { it.id }
                    }
                    .distinct()
            "album" ->
                db.libraryQueries
                    .searchAlbumsByTitleLike(pattern, limit).executeAsList()
                    .flatMap { album ->
                        db.libraryQueries.selectTracksByAlbum(album.id)
                            .executeAsList().map { it.id }
                    }
                    .distinct()
            "genre" -> {
                // Genre matching uses the canonicalized GENRE tag value
                // — exact + case-insensitive substring fallback. The
                // canonicalize-on-launch pass keeps stored values in a
                // single casing; this match handles user typos.
                val exact =
                    db.libraryQueries.selectTracksByTag("GENRE", term.value)
                        .executeAsList().map { it.id }
                if (exact.isNotEmpty()) {
                    exact
                } else {
                    val lc = term.value.lowercase()
                    db.libraryQueries.distinctTagValues("GENRE").executeAsList()
                        .filter { it.lowercase().contains(lc) }
                        .flatMap {
                            db.libraryQueries.selectTracksByTag("GENRE", it)
                                .executeAsList().map { row -> row.id }
                        }
                        .distinct()
                }
            }
            "format" -> {
                // Format matching is exact (case-insensitive). The
                // scanner stores formats lowercased + canonical.
                db.libraryQueries.selectTracksByFormat(term.value.lowercase())
                    .executeAsList().map { it.id }
            }
            else -> null
        }
    }
}

data class SearchResults(
    val tracks: List<Track>,
    val artists: List<Artist>,
    val albums: List<Album>,
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && artists.isEmpty() && albums.isEmpty()
    val totalCount: Int get() = tracks.size + artists.size + albums.size
}

/**
 * Wraps the user's text in `%…%` and escapes the LIKE special characters
 * (`%`, `_`, and the backslash itself). Returns null for blank input so
 * callers don't run a "match everything" query.
 *
 * The corresponding queries in `Library.sq` declare `ESCAPE '\'` so the
 * backslash here is the active escape character.
 */
private fun String.toLikePattern(): String? {
    val trimmed = trim()
    if (trimmed.isEmpty()) return null
    val escaped =
        trimmed
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    return "%$escaped%"
}
