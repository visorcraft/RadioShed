package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.ScrobbleQueueRepository
import com.visorcraft.radioshed.data.SettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Queue-then-drain coordinator for ListenBrainz scrobbles.
 *
 * Offline-first: every completed play is submitted live when possible,
 * but a failed submission is persisted to the durable
 * [ScrobbleQueueRepository] so it survives an app restart or a network
 * outage and gets flushed later by [drain]. The queue is capped at
 * [ScrobbleQueueRepository.CAP] (500) rows — the newest plays win — and
 * a row that keeps failing is dropped once it crosses [MAX_ATTEMPTS]
 * (10) so a single poisoned entry can't wedge the head of the FIFO.
 *
 * Submission semantics:
 *  - [submitOrEnqueue] tries a live submit first (no-op when scrobbling
 *    is disabled or no token is set). On success it opportunistically
 *    [drain]s the backlog — the live call proves the network came back,
 *    so this is the natural moment to catch up. On failure it enqueues.
 *  - [drain] walks the queue oldest-first in batches of 25, deleting
 *    each row it submits successfully and STOPPING at the first failure
 *    (it bumps that row's attempt count and backs off rather than
 *    hammering a server that just rejected us). When a batch is fully
 *    processed without any submit failure the next batch is fetched
 *    immediately, so a large backlog is fully flushed in a single [drain]
 *    call rather than requiring one external trigger per 25-row page.
 *    A single-flight [Mutex] guards [drain] so two concurrent callers
 *    (app-launch drain + a post-submit drain) can't double-submit the
 *    same rows.
 *
 * The network gate is supplied as [allowNetwork] — production binds it
 * to `NetworkPolicyGate.allowAutomaticFetch()` so the user's
 * Always / Wi-Fi only / Never / Manual preference is honoured.
 */
class ScrobbleSync(
    private val queue: ScrobbleQueueRepository,
    private val client: ListenBrainzClient,
    private val settings: SettingsStore,
    private val allowNetwork: () -> Boolean,
) {
    private val drainMutex = Mutex()

    /**
     * Try to submit a completed play live; enqueue it for later flush on
     * failure. No-op when scrobbling is disabled or no token is set. A
     * successful live submit opportunistically [drain]s the backlog to
     * catch up after an outage.
     */
    suspend fun submitOrEnqueue(
        artist: String,
        track: String,
        album: String?,
        listenedAtSeconds: Long,
    ) {
        if (!settings.getListenBrainzEnabled()) return
        val token = settings.getListenBrainzToken()
        if (token.isBlank()) return

        val listen =
            ListenBrainzClient.Listen(
                artist = artist,
                track = track,
                album = album,
                listenedAtSeconds = listenedAtSeconds,
                token = token,
            )
        val submitted = allowNetwork() && client.submit(listen)
        if (submitted) {
            drain()
        } else {
            queue.enqueue(artist, track, album, listenedAtSeconds)
        }
    }

    /**
     * Flush the offline backlog oldest-first until the queue is empty or a
     * submit fails. Skips entirely when the network gate denies. Fetches
     * rows in batches of 25; once a batch is fully processed (every row
     * either submitted or dropped for exceeding [MAX_ATTEMPTS]) the next
     * batch is fetched immediately, so the entire backlog is cleared in one
     * call. A submit failure bumps that row's attempt count and stops the
     * drain so we don't hammer a server that just rejected us — the next
     * call resumes from that row. Single-flight so concurrent drains can't
     * double-submit.
     */
    suspend fun drain() {
        if (!allowNetwork()) return
        if (!settings.getListenBrainzEnabled()) return
        val token = settings.getListenBrainzToken()
        if (token.isBlank()) return

        drainMutex.withLock {
            while (true) {
                val batch = queue.oldestBatch()
                if (batch.isEmpty()) break
                var submitFailed = false
                for (row in batch) {
                    if (row.attempts >= MAX_ATTEMPTS) {
                        // Poisoned row — drop it without burning another
                        // submit so it can't wedge the head of the FIFO.
                        queue.deleteById(row.id)
                        continue
                    }
                    val listen =
                        ListenBrainzClient.Listen(
                            artist = row.artist,
                            track = row.track,
                            album = row.album,
                            listenedAtSeconds = row.listenedAtSeconds,
                            token = token,
                        )
                    if (client.submit(listen)) {
                        queue.deleteById(row.id)
                    } else {
                        // Back off on the first failure — don't hammer a
                        // server that just rejected us; the next drain
                        // resumes from this row.
                        queue.bumpAttempts(row.id)
                        submitFailed = true
                        break
                    }
                }
                if (submitFailed) break
            }
        }
    }

    private companion object {
        /** Attempts after which a row is dropped rather than re-submitted. */
        const val MAX_ATTEMPTS = 10L
    }
}
