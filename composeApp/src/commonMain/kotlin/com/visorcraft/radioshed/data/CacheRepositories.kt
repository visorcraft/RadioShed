package com.visorcraft.radioshed.data

import com.visorcraft.radioshed.db.RadioShedDatabase
import kotlinx.coroutines.withContext

class ArtworkCacheRepository(private val db: RadioShedDatabase) {

    suspend fun put(key: String, blobPath: String, source: String, sizeBytes: Long) =
        withContext(IODispatcher) {
            val now = nowMillis()
            db.cachesQueries.putArtwork(key, blobPath, source, now, sizeBytes, now)
        }

    suspend fun get(key: String): ArtworkCacheEntry? = withContext(IODispatcher) {
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

    suspend fun touch(key: String) = withContext(IODispatcher) {
        db.cachesQueries.touchArtwork(nowMillis(), key)
    }

    suspend fun olderThan(threshold: Long): List<ArtworkCacheEntry> = withContext(IODispatcher) {
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

    suspend fun delete(key: String) = withContext(IODispatcher) {
        db.cachesQueries.deleteArtwork(key)
    }
}

class LyricsCacheRepository(private val db: RadioShedDatabase) {

    suspend fun put(trackId: Long, syncedLrc: String?, plainText: String?, source: String) =
        withContext(IODispatcher) {
            db.cachesQueries.putLyrics(trackId, syncedLrc, plainText, source, nowMillis())
        }

    suspend fun get(trackId: Long): LyricsCacheEntry? = withContext(IODispatcher) {
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

    suspend fun delete(trackId: Long) = withContext(IODispatcher) {
        db.cachesQueries.deleteLyrics(trackId)
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

    suspend fun get(cacheKey: String): DjCacheEntry? = withContext(IODispatcher) {
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

    suspend fun touch(cacheKey: String) = withContext(IODispatcher) {
        db.cachesQueries.touchDjAudio(nowMillis(), cacheKey)
    }

    suspend fun delete(cacheKey: String) = withContext(IODispatcher) {
        db.cachesQueries.deleteDjAudio(cacheKey)
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
