package com.visorcraft.radioshed.domain

/**
 * Per-track play history projection used by the AdaptiveMix
 * smart-query scorer. Mirrors the columns on the `play_count`
 * table without coupling commonMain to SQLDelight types.
 *
 * `lastPlayedAt` is null when the track has never been played
 * (no `play_count` row, or the row exists but the timestamp is
 * unset — should not happen in practice but the column is
 * nullable in schema for forward-compat).
 */
data class PlayCountStats(
    val count: Long,
    val lastPlayedAt: Long?,
)
