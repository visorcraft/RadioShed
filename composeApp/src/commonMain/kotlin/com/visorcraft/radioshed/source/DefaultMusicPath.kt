package com.visorcraft.radioshed.source

/**
 * Platform-aware best-guess for "where is the user's music?". Used as
 * the initial placeholder in the onboarding folder picker and the
 * Library Settings "Add folder" dialog so the field starts with a
 * sensible value the user can either accept or override via Browse.
 *
 * Resolves to:
 *  - **Android**: `/storage/emulated/0/Music`
 *  - **Desktop**: `~/Music` (using the JVM `user.home` system property)
 *  - **iOS**:     empty string — iOS surfaces user folders only via the
 *                 document picker, not as a known absolute path.
 *
 * The returned path is NOT validated for existence; the picker UI
 * renders it as a placeholder, and the eventual scan handles a
 * non-existent folder by reporting "no tracks found" rather than
 * crashing.
 */
expect fun defaultMusicFolderPath(): String
