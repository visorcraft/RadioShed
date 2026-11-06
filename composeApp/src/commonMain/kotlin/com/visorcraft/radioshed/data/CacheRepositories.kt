package com.visorcraft.radioshed.data

import com.visorcraft.radioshed.db.RadioShedDatabase
import kotlinx.coroutines.withContext

class ArtworkCacheRepository(private val db: RadioShedDatabase) {
    suspend fun put(
        key: String,
        blobPath: String,
        source: String,
        sizeBytes: Long,
    ) = withContext(IODispatcher) {
        val now = nowMillis()
        db.cachesQueries.putArtwork(key, blobPath, source, now, sizeBytes, now)
    }

    suspend fun get(key: String): ArtworkCacheEntry? =
        withContext(IODispatcher) {
            db.cachesQueries.getArtwork(key).executeAsOneOrNull()?.let { row ->
                ArtworkCacheEntry(
                    cacheKey = row.cache_key,
                    blobPath = row.blob_path,
                    source = row.source,
                    fetchedAt = row.fetched_at,
                    sizeBytes = row.size_bytes,
                    lastAccessedAt = row.last_accessed_at,
                )
            }
        }

    suspend fun touch(key: String) =
        withContext(IODispatcher) {
            db.cachesQueries.touchArtwork(nowMillis(), key)
        }

    suspend fun olderThan(threshold: Long): List<ArtworkCacheEntry> =
        withContext(IODispatcher) {
            db.cachesQueries.selectArtworkOlderThan(threshold).executeAsList().map { row ->
                ArtworkCacheEntry(
                    cacheKey = row.cache_key,
                    blobPath = row.blob_path,
                    source = row.source,
                    fetchedAt = row.fetched_at,
                    sizeBytes = row.size_bytes,
                    lastAccessedAt = row.last_accessed_at,
                )
            }
        }

    suspend fun delete(key: String) =
        withContext(IODispatcher) {
            db.cachesQueries.deleteArtwork(key)
        }

    /**
     * Wipe every artwork_cache row. Callers ALSO need to delete the
     * on-disk blobs (`ArtworkPersister.clearAll()`) and clear the
     * album.artwork_uri pointers (`LibraryRepository.clearAllAlbumArtwork()`)
     * for a full reset — the AppSettings "Clear artwork cache"
     * action does all three together.
     */
    suspend fun deleteAll() =
        withContext(IODispatcher) {
            db.cachesQueries.deleteAllArtwork()
        }
}

class LyricsCacheRepository(private val db: RadioShedDatabase) {
    suspend fun put(
        trackId: Long,
        syncedLrc: String?,
        plainText: String?,
        source: String,
    ) = withContext(IODispatcher) {
        db.cachesQueries.putLyrics(trackId, syncedLrc, plainText, source, nowMillis())
    }

    suspend fun get(trackId: Long): LyricsCacheEntry? =
        withContext(IODispatcher) {
            db.cachesQueries.getLyrics(trackId).executeAsOneOrNull()?.let { row ->
                LyricsCacheEntry(
                    trackId = row.track_id,
                    syncedLrc = row.synced_lrc,
                    plainText = row.plain_text,
                    source = row.source,
                    fetchedAt = row.fetched_at,
                )
            }
        }

    suspend fun delete(trackId: Long) =
        withContext(IODispatcher) {
            db.cachesQueries.deleteLyrics(trackId)
        }

    /**
     * Wipes every cached lyrics row. Overrides (manual artist/title
     * corrections) are preserved — those represent user input we
     * don't want to lose. Next playback of any track will re-fetch
     * from LRClib / re-extract from tags.
     */
    suspend fun deleteAll() =
        withContext(IODispatcher) {
            db.cachesQueries.deleteAllLyrics()
        }

    /**
     * Track IDs that need a lyrics sweep — never fetched, OR cached
     * as a stale `lrclib:miss` (older than [missOlderThanMs]). The
     * caller normally passes `now - 30 days` so the negative-cache
     * TTL is honoured: tracks recently confirmed-missing are skipped,
     * tracks marked miss long enough ago that LRClib may have added
     * them are retried.
     */
    suspend fun trackIdsMissingLyrics(missOlderThanMs: Long): List<Long> =
        withContext(IODispatcher) {
            db.cachesQueries.selectTrackIdsMissingLyrics(missOlderThanMs)
                .executeAsList()
        }

    /**
     * User-supplied (artist, title) override for a track's lyrics
     * search. When set, [LyricsService] uses it as the first
     * candidate before falling through to tag-derived variations.
     */
    suspend fun setOverride(
        trackId: Long,
        artist: String,
        title: String,
    ) = withContext(IODispatcher) {
        db.cachesQueries.putLyricsOverride(trackId, artist, title, nowMillis())
    }

    suspend fun getOverride(trackId: Long): Pair<String, String>? =
        withContext(IODispatcher) {
            db.cachesQueries.getLyricsOverride(trackId).executeAsOneOrNull()?.let {
                it.artist to it.title
            }
        }

    suspend fun clearOverride(trackId: Long) =
        withContext(IODispatcher) {
            db.cachesQueries.deleteLyricsOverride(trackId)
        }
}

class DjCacheRepository(private val db: RadioShedDatabase) {
    suspend fun put(
        cacheKey: String,
        blobPath: String,
        templateHash: String,
        voiceId: String,
        variablesJson: String,
        durationMs: Long,
    ) = withContext(IODispatcher) {
        val now = nowMillis()
        db.cachesQueries.putDjAudio(
            cache_key = cacheKey,
            blob_path = blobPath,
            template_hash = templateHash,
            voice_id = voiceId,
            variables_json = variablesJson,
            generated_at = now,
            last_used_at = now,
            duration_ms = durationMs,
        )
    }

    suspend fun get(cacheKey: String): DjCacheEntry? =
        withContext(IODispatcher) {
            db.cachesQueries.getDjAudio(cacheKey).executeAsOneOrNull()?.let { row ->
                DjCacheEntry(
                    cacheKey = row.cache_key,
                    blobPath = row.blob_path,
                    templateHash = row.template_hash,
                    voiceId = row.voice_id,
                    variablesJson = row.variables_json,
                    generatedAt = row.generated_at,
                    lastUsedAt = row.last_used_at,
                    durationMs = row.duration_ms,
                )
            }
        }

    suspend fun touch(cacheKey: String) =
        withContext(IODispatcher) {
            db.cachesQueries.touchDjAudio(nowMillis(), cacheKey)
        }

    suspend fun delete(cacheKey: String) =
        withContext(IODispatcher) {
            db.cachesQueries.deleteDjAudio(cacheKey)
        }

    /**
     * Wipes every cached DJ audio row. Used by the App Settings
     * "Clear DJ cache" action; on-disk blobs are removed separately
     * by walking the cache dir at the call site.
     */
    suspend fun deleteAll() =
        withContext(IODispatcher) {
            db.cachesQueries.deleteAllDjAudio()
        }

    /**
     * Evict DJ clips not used since [olderThanMs] (epoch millis): delete each
     * stale row AND the on-disk blob it points at. Returns the number of rows
     * removed.
     *
     * The return value of [deleteFileIfExists] is intentionally discarded — a
     * row is always dropped regardless of whether the blob delete succeeded.
     * `false` can mean the file was already gone *or* that a live blob could
     * not be deleted (locked / permissions error on desktop or iOS, which
     * returns false without throwing). In the latter case the file is accepted
     * as stranded: this is bounded, rare, and fully reclaimable via the user's
     * "Clear DJ cache" action or OS storage tools. Keeping the row would cause
     * trim to rescan and retry the missing-blob path on every future call,
     * which is worse. Reuses the `last_used_at`-indexed
     * `selectDjAudioOlderThan` read and the delete-by-key `deleteDjAudio`
     * query, so no new SQL or migration is needed. Every step is wrapped so
     * eviction can never throw into a caller.
     */
    suspend fun trimStale(olderThanMs: Long): Int =
        withContext(IODispatcher) {
            var removed = 0
            runCatching {
                val stale = db.cachesQueries.selectDjAudioOlderThan(olderThanMs).executeAsList()
                stale.forEach { row ->
                    runCatching {
                        com.visorcraft.radioshed.source.deleteFileIfExists(row.blob_path)
                        db.cachesQueries.deleteDjAudio(row.cache_key)
                        removed += 1
                    }
                }
            }
            removed
        }

    /** Snapshot of every cached DJ row — used so the call site can
     *  delete the on-disk blobs each row points at before wiping the
     *  table. */
    suspend fun listAll(): List<DjCacheEntry> =
        withContext(IODispatcher) {
            // Re-uses the touch index by querying with `last_used_at < +inf`;
            // SQLite treats Long.MAX_VALUE as a clean upper bound so the
            // index drives the read.
            db.cachesQueries.selectDjAudioOlderThan(Long.MAX_VALUE)
                .executeAsList()
                .map { row ->
                    DjCacheEntry(
                        cacheKey = row.cache_key,
                        blobPath = row.blob_path,
                        templateHash = row.template_hash,
                        voiceId = row.voice_id,
                        variablesJson = row.variables_json,
                        generatedAt = row.generated_at,
                        lastUsedAt = row.last_used_at,
                        durationMs = row.duration_ms,
                    )
                }
        }
}

data class ArtworkCacheEntry(
    val cacheKey: String,
    val blobPath: String,
    val source: String,
    val fetchedAt: Long,
    val sizeBytes: Long,
    val lastAccessedAt: Long,
)

data class LyricsCacheEntry(
    val trackId: Long,
    val syncedLrc: String?,
    val plainText: String?,
    val source: String,
    val fetchedAt: Long,
)

data class DjCacheEntry(
    val cacheKey: String,
    val blobPath: String,
    val templateHash: String,
    val voiceId: String,
    val variablesJson: String,
    val generatedAt: Long,
    val lastUsedAt: Long,
    val durationMs: Long,
)
