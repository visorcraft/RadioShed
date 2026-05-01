package com.visorcraft.radioshed.data

import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.Album
import com.visorcraft.radioshed.domain.Artist
import com.visorcraft.radioshed.domain.Track
import kotlinx.coroutines.withContext

/**
 * Library search. v1 uses LIKE because SQLDelight 2.1's parser doesn't
 * handle FTS5 MATCH cleanly; revisit when the parser catches up. Acceptable
 * for libraries up to ~10k tracks; above that we'd want FTS5 or a custom
 * inverted index.
 *
 * The DSL parser (`artist:Hendrix year:>1968 genre:rock`) is deferred to
 * a future pass — for now, the [query] argument is treated as plain text
 * matched substring-wise (case-insensitive) against title / artist name /
 * album title.
 */
class SearchRepository(private val db: RadioShedDatabase) {

    suspend fun searchTracks(query: String, limit: Long = 50): List<Track> =
        withContext(IODispatcher) {
            val pattern = query.toLikePattern() ?: return@withContext emptyList()
            db.libraryQueries.searchTracksByTitleLike(pattern, limit)
                .executeAsList().map { it.toDomain() }
        }

    suspend fun searchArtists(query: String, limit: Long = 50): List<Artist> =
        withContext(IODispatcher) {
            val pattern = query.toLikePattern() ?: return@withContext emptyList()
            db.libraryQueries.searchArtistsByNameLike(pattern, limit)
                .executeAsList().map { it.toDomain() }
        }

    suspend fun searchAlbums(query: String, limit: Long = 50): List<Album> =
        withContext(IODispatcher) {
            val pattern = query.toLikePattern() ?: return@withContext emptyList()
            db.libraryQueries.searchAlbumsByTitleLike(pattern, limit)
                .executeAsList().map { it.toDomain() }
        }

    suspend fun searchAll(query: String, limitPerCategory: Long = 20): SearchResults =
        withContext(IODispatcher) {
            SearchResults(
                tracks = searchTracks(query, limitPerCategory),
                artists = searchArtists(query, limitPerCategory),
                albums = searchAlbums(query, limitPerCategory),
            )
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
    val escaped = trimmed
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
    return "%$escaped%"
}
