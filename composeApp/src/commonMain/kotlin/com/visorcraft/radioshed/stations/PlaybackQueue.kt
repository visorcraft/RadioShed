package com.visorcraft.radioshed.stations

import com.visorcraft.radioshed.domain.PlayOrder
import com.visorcraft.radioshed.domain.PlaybackRule
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.domain.Weighting
import kotlin.random.Random

/**
 * Pulls the next track for a station given a candidate set + [PlaybackRule].
 *
 * Smart-shuffle invariants (v1):
 *  - **No-repeat memory**: the last `noRepeatDepth` track-ids never come up
 *    again in the same window. When the depth is larger than the candidate
 *    set we let the oldest entries fall off rather than refusing to pick.
 *  - **Weighting**: `Uniform` is true random; `FavorRecent` biases towards
 *    higher `file_ctime`; the remaining policies fall back to `Uniform`
 *    in v1 because they need richer per-track stats (play counts, ratings)
 *    that Phase 5 part 2 wires through.
 *
 * Deferred to Phase 5 part 2:
 *  - **Artist / album spacing** — needs the artist-id pre-loaded per
 *    candidate; cheap to add once `SourceRuleEvaluator` returns enriched
 *    candidates.
 *  - **Per-station dislikes** — `RatingRepository.dislikedTrackIdsForStation`
 *    is plumbed; the queue accepts a `dislikedIds` set to filter out.
 *
 * [random] is parametric so unit tests can pin a deterministic seed.
 */
class PlaybackQueue(
    candidates: List<Track>,
    private val rule: PlaybackRule,
    private val dislikedIds: Set<Long> = emptySet(),
    private val random: Random = Random.Default,
    initialSequentialCursor: Int = 0,
    /**
     * (trackId → primaryArtistId) lookup. Optional: when empty (e.g.,
     * ad-hoc playTracks queues seeded outside the StationPlayback path)
     * artist spacing degrades gracefully to a no-op while album spacing
     * still applies.
     */
    private val artistByTrackId: Map<Long, Long> = emptyMap(),
    /**
     * (trackId → play_count) for FavorUnderPlayed weighting. Empty map
     * makes the weighting fall back to uniform — fine for ad-hoc
     * queues where we don't pre-resolve.
     */
    private val playCountByTrackId: Map<Long, Long> = emptyMap(),
    /**
     * (trackId → stars) for FavorHigherRated weighting. Same fallback
     * semantics as [playCountByTrackId].
     */
    private val ratingByTrackId: Map<Long, Double> = emptyMap(),
) {

    /**
     * Pre-filter dislikes once at construction time and use the
     * resulting list everywhere. Prevents an indexing mismatch where
     * sequentialCursor referred to the unfiltered candidate list while
     * `pool` (the actual playable set) was disliked-filtered — a
     * `startIndex = 7` could land on the wrong track if any of the
     * first 7 candidates was disliked. Now the cursor and pool share
     * the same index space.
     */
    private val candidates: List<Track> = candidates.filter { it.id !in dislikedIds }

    /** Recently-played track ids, oldest first. Bounded at `2 × noRepeatDepth`. */
    private val recent: ArrayDeque<Long> = ArrayDeque()

    /**
     * Recently-picked album ids (most recent at the END). Used to space
     * out same-album hits in shuffle modes — without this, an album-
     * heavy candidate set tends to pick three in a row from the same
     * album, especially with small libraries. Sized at [ALBUM_SPACING],
     * which is intentionally small (default 3) so we don't completely
     * starve out a small library.
     */
    private val recentAlbums: ArrayDeque<Long> = ArrayDeque()

    /**
     * User-injected "play next" / "add to queue" buffer. Drained by
     * [next] BEFORE consulting the rule-driven candidate pool so a
     * tap on "Play next" is honored even on a station mid-shuffle.
     *
     * Insertion ergonomics:
     *  - "Play next" → [enqueueNext] addFirst (LIFO so successive
     *    "Play next" picks queue with the most-recently-picked first,
     *    matching Spotify / Apple Music semantics).
     *  - "Add to queue" → [enqueueLast] addLast (FIFO append).
     *
     * No-repeat / album / artist spacing buffers DO get bumped when
     * priority items play, so they correctly affect subsequent rule-
     * driven picks even if all the priority items came from the
     * same album.
     */
    private val priority: ArrayDeque<Track> = ArrayDeque()

    /**
     * Recently-picked primary-artist ids. Sized smaller than album
     * spacing because users notice "same artist twice in a row" more
     * than "same album twice in a row" — we want to be tighter here.
     * Falls back to no-op when [artistByTrackId] is empty (which is
     * the case for ad-hoc playTracks queues).
     */
    private val recentArtists: ArrayDeque<Long> = ArrayDeque()

    /** Index for sequential mode. */
    private var sequentialCursor: Int = initialSequentialCursor.coerceAtLeast(0)

    val isEmpty: Boolean get() = candidates.isEmpty()

    val candidateCount: Int get() = candidates.size

    /** Pick the next track, advancing internal state. Returns null if no
     *  candidates remain (after dislikes are filtered). */
    fun next(): Track? {
        // Priority items always come first — even if the candidate pool
        // is empty (e.g., last station candidate just played), a queued
        // "Play next" track still resolves.
        if (priority.isNotEmpty()) {
            val picked = priority.removeFirst()
            recordPlayed(picked.id, picked.albumId, primaryArtistOf(picked))
            return picked
        }
        if (candidates.isEmpty()) return null
        return when (rule.order) {
            PlayOrder.Sequential -> nextSequential(candidates)
            PlayOrder.Shuffle, PlayOrder.SmartShuffle, PlayOrder.WeightedRandom ->
                nextSmartShuffle(candidates)
        }
    }

    /** Inject [track] to play before the next rule-driven pick. LIFO. */
    fun enqueueNext(track: Track) {
        priority.addFirst(track)
    }

    /**
     * Inject [tracks] so they play in the given order before the next
     * rule-driven pick. Use this from "Play this album next" / "Play
     * this artist next" so the caller doesn't need to know the queue's
     * LIFO addFirst implementation detail (and so the order can't
     * silently invert if [enqueueNext] semantics ever change).
     */
    fun enqueueNextAll(tracks: List<Track>) {
        // addFirst is LIFO, so iterate in reverse to preserve order.
        for (i in tracks.indices.reversed()) priority.addFirst(tracks[i])
    }

    /** Append [track] to the priority buffer's tail. FIFO append. */
    fun enqueueLast(track: Track) {
        priority.addLast(track)
    }

    /** Append [tracks] to the priority buffer's tail in order. */
    fun enqueueLastAll(tracks: List<Track>) {
        for (t in tracks) priority.addLast(t)
    }

    /** Snapshot of the priority buffer for the UI (Up Next sheet). */
    fun prioritySnapshot(): List<Track> = priority.toList()

    /** Drop everything the user queued via Play next / Add to queue. */
    fun clearPriority() {
        priority.clear()
    }

    /**
     * Remove the track at [index] from the priority buffer. No-op when
     * the index is out of range — the UI may race the queue if the
     * current track transitions while the user is mid-tap.
     *
     * Single-operation removal so concurrent next() calls never see
     * an empty deque: a clear-then-rebuild approach opens a window
     * where the rule pipeline takes over and silently skips queued
     * tracks. ArrayDeque.removeAt is O(n) but bounded by queue size
     * (typically <50), so it's fine.
     */
    fun removePriorityAt(index: Int) {
        if (index in priority.indices) {
            priority.removeAt(index)
        }
    }

    /**
     * Move the priority entry at [from] to [to]. Used by Up Next
     * drag-to-reorder. Both indices are clamped to the priority
     * range so a stale UI tap (after a track auto-consumed the
     * priority head) becomes a no-op rather than throwing.
     */
    fun movePriority(from: Int, to: Int) {
        if (from == to) return
        if (from !in priority.indices || to !in priority.indices) return
        val track = priority.removeAt(from)
        priority.add(to, track)
    }

    private fun nextSequential(pool: List<Track>): Track {
        val track = pool[sequentialCursor.coerceIn(0, pool.size - 1)]
        sequentialCursor = (sequentialCursor + 1) % pool.size
        recordPlayed(track.id, track.albumId, primaryArtistOf(track))
        return track
    }

    private fun nextSmartShuffle(pool: List<Track>): Track {
        // Three avoid filters, applied jointly. Aged out in priority
        // order on starvation (track > album > artist) so we relax the
        // most specific constraint first — letting a track repeat is
        // worse than letting the same artist's other album recur.
        //   1. Recent track ids   (no-repeat memory, configurable)
        //   2. Recent album ids   (album-spacing, fixed window)
        //   3. Recent artist ids  (artist-spacing, smaller window)
        var avoid = effectiveAvoidSet(pool.size)
        var avoidAlbums = effectiveAlbumAvoidSet()
        var avoidArtists = effectiveArtistAvoidSet()
        var available = pool.filter {
            it.id !in avoid &&
                it.albumId !in avoidAlbums &&
                primaryArtistOf(it) !in avoidArtists
        }
        while (available.isEmpty()) {
            when {
                recent.isNotEmpty() -> recent.removeFirst()
                recentAlbums.isNotEmpty() -> recentAlbums.removeFirst()
                recentArtists.isNotEmpty() -> recentArtists.removeFirst()
                else -> { available = pool; break }  // last-ditch fallback
            }
            avoid = effectiveAvoidSet(pool.size)
            avoidAlbums = effectiveAlbumAvoidSet()
            avoidArtists = effectiveArtistAvoidSet()
            available = pool.filter {
                it.id !in avoid &&
                    it.albumId !in avoidAlbums &&
                    primaryArtistOf(it) !in avoidArtists
            }
        }
        if (available.isEmpty()) available = pool

        val picked = pickWeighted(available)
        recordPlayed(picked.id, picked.albumId, primaryArtistOf(picked))
        return picked
    }

    /** Last `rule.albumSpacing` picked album ids — used to space repeats. */
    private fun effectiveAlbumAvoidSet(): Set<Long> {
        val depth = rule.albumSpacing.coerceAtLeast(0)
        if (depth == 0 || recentAlbums.isEmpty()) return emptySet()
        val end = recentAlbums.size
        val start = (end - depth).coerceAtLeast(0)
        return recentAlbums.toList().subList(start, end).toSet()
    }

    private fun effectiveArtistAvoidSet(): Set<Long> {
        val depth = rule.artistSpacing.coerceAtLeast(0)
        if (depth == 0 || recentArtists.isEmpty()) return emptySet()
        val end = recentArtists.size
        val start = (end - depth).coerceAtLeast(0)
        return recentArtists.toList().subList(start, end).toSet()
    }

    private fun primaryArtistOf(track: Track): Long? =
        artistByTrackId[track.id]

    /**
     * Returns the set of recent ids to avoid. Capped at `pool.size - 1` so
     * we always leave at least one candidate available.
     */
    private fun effectiveAvoidSet(poolSize: Int): Set<Long> {
        val depth = rule.noRepeatDepth.coerceAtLeast(0).coerceAtMost(poolSize - 1)
        if (depth == 0) return emptySet()
        // recent stores oldest-first; the LAST `depth` entries are the most
        // recent and the ones we want to avoid.
        val end = recent.size
        val start = (end - depth).coerceAtLeast(0)
        return recent.toList().subList(start, end).toSet()
    }

    private fun pickWeighted(pool: List<Track>): Track {
        if (pool.size == 1) return pool.single()
        return when (rule.weighting) {
            Weighting.Uniform -> pool[random.nextInt(pool.size)]
            Weighting.FavorRecent -> {
                // Weight ∝ rank by ctime, oldest=1, newest=N. Cheap softmax-ish.
                val sorted = pool.sortedBy { it.fileCtime }
                val weights = sorted.indices.map { (it + 1).toDouble() }
                weightedPick(sorted, weights)
            }
            Weighting.FavorUnderPlayed -> {
                // weight = 1 / (plays + 1) — never-played tracks get
                // weight 1.0, played-once 0.5, played-twice 0.33, etc.
                // Smoothly biases toward unheard material without
                // making popular tracks impossible to recur. Falls
                // back to uniform when the play-count map is empty.
                if (playCountByTrackId.isEmpty()) {
                    pool[random.nextInt(pool.size)]
                } else {
                    val weights = pool.map { t ->
                        val plays = playCountByTrackId[t.id] ?: 0L
                        1.0 / (plays + 1.0)
                    }
                    weightedPick(pool, weights)
                }
            }
            Weighting.FavorHigherRated -> {
                // Bias proportional to (stars + 1)² so 5★ tracks are
                // ~6× more likely to be picked than unrated. Unrated
                // tracks (rating absent from map) treated as 0★ → small
                // but non-zero weight so the queue doesn't starve when
                // the user hasn't rated anything yet.
                if (ratingByTrackId.isEmpty()) {
                    pool[random.nextInt(pool.size)]
                } else {
                    val weights = pool.map { t ->
                        val stars = ratingByTrackId[t.id] ?: 0.0
                        val w = stars + 1.0
                        w * w
                    }
                    weightedPick(pool, weights)
                }
            }
        }
    }

    private fun weightedPick(items: List<Track>, weights: List<Double>): Track {
        val total = weights.sum()
        var r = random.nextDouble() * total
        for (i in items.indices) {
            r -= weights[i]
            if (r <= 0.0) return items[i]
        }
        return items.last()
    }

    private fun recordPlayed(id: Long, albumId: Long?, artistId: Long?) {
        recent.addLast(id)
        val cap = (rule.noRepeatDepth.coerceAtLeast(0) * 2)
            .coerceAtLeast(MIN_RECENT_CAP)
        while (recent.size > cap) recent.removeFirst()
        if (albumId != null) {
            recentAlbums.addLast(albumId)
            // 2× the spacing gives slack so we don't fall back to
            // immediate re-eligibility on the next pick. Cap at a
            // small floor (4) for stations with spacing=0 to avoid
            // unbounded growth.
            val albumCap = (rule.albumSpacing * 2).coerceAtLeast(MIN_SPACING_CAP)
            while (recentAlbums.size > albumCap) recentAlbums.removeFirst()
        }
        if (artistId != null) {
            recentArtists.addLast(artistId)
            val artistCap = (rule.artistSpacing * 2).coerceAtLeast(MIN_SPACING_CAP)
            while (recentArtists.size > artistCap) recentArtists.removeFirst()
        }
    }

    /** Snapshot of recently-played ids, oldest first. Useful for tests. */
    fun recentSnapshot(): List<Long> = recent.toList()

    /**
     * Peek up to [count] upcoming tracks WITHOUT advancing internal state.
     *
     * For [PlayOrder.Sequential] the result is exact — same tracks in
     * the same order the queue would actually play.
     *
     * For shuffle / weighted modes the next pick is randomized, so the
     * returned list is a "preview pool" — the eligible candidates after
     * applying no-repeat memory and dislikes, NOT a literal prediction
     * of what the queue will actually pick. The Up Next UI labels this
     * accordingly so users don't expect deterministic ordering on a
     * shuffle station.
     */
    fun peekUpcoming(count: Int): List<Track> {
        if (count <= 0) return emptyList()
        // Priority items come first — they're guaranteed to play in
        // that order regardless of shuffle mode. Then the rule-driven
        // preview fills the rest of the requested count.
        val priorityHead = priority.toList().take(count)
        val remainingCount = count - priorityHead.size
        if (remainingCount <= 0 || candidates.isEmpty()) return priorityHead
        val ruleSlice = when (rule.order) {
            PlayOrder.Sequential -> {
                val n = remainingCount.coerceAtMost(candidates.size)
                (0 until n).map { offset ->
                    candidates[(sequentialCursor + offset) % candidates.size]
                }
            }
            PlayOrder.Shuffle, PlayOrder.SmartShuffle, PlayOrder.WeightedRandom -> {
                // Apply the SAME three filters as next() so the preview
                // matches what the queue will actually pick from. Without
                // album/artist filtering, the Up Next sheet on a heavy-
                // shuffle station shows tracks that next() will actively
                // skip past.
                val avoid = effectiveAvoidSet(candidates.size)
                val avoidAlbums = effectiveAlbumAvoidSet()
                val avoidArtists = effectiveArtistAvoidSet()
                val available = candidates.filter {
                    it.id !in avoid &&
                        it.albumId !in avoidAlbums &&
                        primaryArtistOf(it) !in avoidArtists
                }
                available.take(remainingCount)
            }
        }
        return priorityHead + ruleSlice
    }

    private companion object {
        const val MIN_RECENT_CAP = 16

        /** Lower bound for the album/artist recent-deque size so we
         *  always retain *some* spacing memory even when a station has
         *  spacing configured to 0. Keeps the buffer-shrink behaviour
         *  predictable. */
        const val MIN_SPACING_CAP = 4
    }
}
