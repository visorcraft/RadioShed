package com.visorcraft.radioshed.source

/**
 * Sets a track's audio file as the device's default ringtone. Used by
 * the Track Info dialog's Set as Ringtone action.
 *
 * Requires `WRITE_SETTINGS` on Android, which is granted via a
 * system Settings activity (not a normal runtime-permission dialog).
 * The Android impl returns [Result.NeedsPermission] when missing,
 * so the UI can route the user to that screen and re-attempt.
 *
 * iOS has no programmatic ringtone API — Apple gates ringtones to
 * the GarageBand workflow. Returns [Result.NotSupported] there.
 */
interface RingtoneSetter {
    suspend fun setAsRingtone(
        absolutePath: String,
        displayName: String,
    ): Result

    sealed interface Result {
        /** Ringtone successfully set as the system default. */
        data object Ok : Result

        /** WRITE_SETTINGS not granted; the impl launched the system
         *  permission screen so the user can grant it manually. */
        data object NeedsPermission : Result

        /** Platform doesn't support programmatic ringtone setting. */
        data object NotSupported : Result

        /** Anything else (file gone, MediaStore insert failed). */
        data class Failed(val reason: String) : Result
    }
}
