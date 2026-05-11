package com.visorcraft.radioshed.stations

import com.visorcraft.radioshed.domain.Track

/**
 * One track plus the inputs the scorer needs. Built by
 * `SourceRuleEvaluator.evaluateAdaptiveMix` from the candidate
 * pool + batch repository fetches; passed pure into [scoreAdaptiveMix]
 * so the math is unit-testable without a live DB.
 *
 * `lastPlayedAtMs` is null when the track has never played, in
 * which case it gets the maximum recency boost.
 *
 * `rating` is 0..5; 0 is treated as "unrated" with a baseline
 * boost (see [scoreAdaptiveMix]).
 */
data class AdaptiveMixCandidate(
    val track: Track,
    val playCount: Long,
    val lastPlayedAtMs: Long?,
    val rating: Double,
)

/**
 * Pure ranker for AdaptiveMix. Given a candidate pool, scores each
 * track on (play penalty, recency boost, rating boost, jitter) and
 * returns the top [limit] in descending score order.
 *
 * Weights:
 *  - Play penalty: 40 %. `(1 - playCount / maxPlays)`. Lifetime
 *    plays push the track down so over-played tracks fall behind.
 *    `maxPlays` is the candidate-pool maximum, clamped to ≥1 so a
 *    pool of all never-played tracks doesn't divide by zero.
 *  - Recency boost: 30 %. `min(daysSinceLastPlay / 30, 1)`. A
 *    track not played in the last month gets the full boost; one
 *    played today gets nothing. Never-played counts as max boost.
 *  - Rating boost: 30 %. `max(stars / 5, 0.2)`. Unrated tracks
 *    get a 0.2 floor so discovery still works on an un-rated
 *    library.
 *  - Jitter: ±5 %, seeded so the caller can reproduce a specific
 *    ordering for tests / debugging. In production the seed is
 *    `nowMillis()` so consecutive queue refreshes don't collapse
 *    onto the same Top-N.
 */
fun scoreAdaptiveMix(
    candidates: List<AdaptiveMixCandidate>,
    nowMs: Long,
    jitterSeed: Long,
    limit: Int,
): List<Track> {
    if (candidates.isEmpty() || limit <= 0) return emptyList()

    val maxPlays = candidates.maxOf { it.playCount }.coerceAtLeast(1L)
    val rng = kotlin.random.Random(jitterSeed)

    return candidates
        .map { c ->
            val playPenalty = (c.playCount.toDouble() / maxPlays).coerceIn(0.0, 1.0)
            val daysSince = if (c.lastPlayedAtMs == null) {
                Double.POSITIVE_INFINITY
            } else {
                (nowMs - c.lastPlayedAtMs).coerceAtLeast(0L) / MS_PER_DAY.toDouble()
            }
            val recencyBoost = (daysSince / RECENCY_NORMALISE_DAYS).coerceIn(0.0, 1.0)
            val ratingBoost = (c.rating / 5.0).coerceIn(0.0, 1.0)
                .let { if (c.rating <= 0.0) 0.2 else it }
            val jitter = rng.nextDouble(-0.05, 0.05)

            val score = (1.0 - playPenalty) * 0.4 +
                recencyBoost * 0.3 +
                ratingBoost * 0.3 +
                jitter
            c.track to score
        }
        .sortedByDescending { it.second }
        .take(limit)
        .map { it.first }
}

private const val MS_PER_DAY = 86_400_000L
private const val RECENCY_NORMALISE_DAYS = 30.0
