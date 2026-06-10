package com.visorcraft.radioshed.data

import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.db.Scrobble_queue
import kotlinx.coroutines.withContext

/**
 * Durable, offline-first scrobble queue.
 *
 * Every completed play is enqueued here before any submission attempt
 * so a scrobble survives an app restart or a network outage and gets
 * flushed later by the scrobble sender. Rows are deleted once
 * successfully submitted ([deleteById]); [bumpAttempts] tracks repeated
 * flush failures so the sender can back off or eventually drop a
 * poisoned row.
 *
 * The queue is capped at [CAP] (500) rows — [enqueue] prunes the oldest
 * overflow in the same transaction as the insert, so a long offline
 * stretch can't grow the DB unbounded. The newest 500 plays are the
 * ones worth keeping.
 */
class ScrobbleQueueRepository(private val db: RadioShedDatabase) {

    /**
     * Persist a finished play, then prune to the [CAP] in the same
     * transaction so the queue is never observed over the cap.
     */
    suspend fun enqueue(
        artist: String,
        track: String,
        album: String?,
        listenedAtSeconds: Long,
    ) = withContext(IODispatcher) {
        db.scrobblesQueries.transaction {
            db.scrobblesQueries.enqueue(
                artist = artist,
                track = track,
                album = album,
                listened_at_seconds = listenedAtSeconds,
                created_at = nowMillis(),
            )
            db.scrobblesQueries.pruneOverCap()
        }
    }

    /** Oldest [limit] queued scrobbles, in FIFO (insertion) order. */
    suspend fun oldestBatch(limit: Int = 25): List<QueuedScrobble> = withContext(IODispatcher) {
        db.scrobblesQueries.oldestBatch(limit.toLong()).executeAsList().map { it.toQueuedScrobble() }
    }

    /** Remove a row once it has been successfully submitted. */
    suspend fun deleteById(id: Long) = withContext(IODispatcher) {
        db.scrobblesQueries.deleteById(id)
    }

    /** Record a failed flush attempt so the sender can back off. */
    suspend fun bumpAttempts(id: Long) = withContext(IODispatcher) {
        db.scrobblesQueries.bumpAttempts(id)
    }

    /** Total queued rows — used for diagnostics / UI badges. */
    suspend fun countAll(): Long = withContext(IODispatcher) {
        db.scrobblesQueries.countAll().executeAsOne()
    }

    internal companion object {
        /**
         * Maximum rows retained; older overflow is pruned on enqueue.
         * SQL counterpart (pruneOverCap in Scrobbles.sq) hard-codes this value — update both together.
         */
        const val CAP = 500
    }
}

/** A single queued scrobble row, mapped from the generated SQL type. */
data class QueuedScrobble(
    val id: Long,
    val artist: String,
    val track: String,
    val album: String?,
    val listenedAtSeconds: Long,
    val attempts: Long,
    val createdAt: Long,
)

private fun Scrobble_queue.toQueuedScrobble(): QueuedScrobble = QueuedScrobble(
    id = id,
    artist = artist,
    track = track,
    album = album,
    listenedAtSeconds = listened_at_seconds,
    attempts = attempts,
    createdAt = created_at,
)
