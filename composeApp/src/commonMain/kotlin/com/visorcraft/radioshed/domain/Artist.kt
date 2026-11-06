package com.visorcraft.radioshed.domain

import kotlinx.serialization.Serializable

@Serializable
data class Artist(
    val id: Long,
    val name: String,
    val sortName: String,
    val musicbrainzId: String? = null,
    val imageUri: String? = null,
    val bio: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * An alternate display name for an [Artist]. Two paths populate this:
 *  - Explicit user edit (Edit Artist dialog) — "Slim Shady" added as
 *    an alias for "Eminem" so tagged tracks with either name link
 *    to the same row.
 *  - Merge artifact — when artist A is merged into artist B, A's
 *    name is automatically recorded as an alias on B so future
 *    scans don't recreate A.
 *
 * [aliasSortName] is the normalized lookup key (lower-cased +
 * trimmed, matching `Scanner.normalizeForSort`); [aliasName] is
 * the display string the user typed.
 */
@Serializable
data class ArtistAlias(
    val id: Long,
    val artistId: Long,
    val aliasName: String,
    val aliasSortName: String,
    val createdAt: Long,
)
