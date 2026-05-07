package com.visorcraft.radioshed.source

import android.net.Uri
import androidx.core.content.FileProvider

/**
 * [FileProvider] subclass that reports `image/jpeg` for the artwork
 * directory, regardless of file extension. The persisted artwork
 * files are named after their SHA-256 hash with no extension (so
 * duplicate cover art across an album is stored once on disk), which
 * means [androidx.core.content.FileProvider]'s default `getType()` —
 * which guesses MIME from the filename suffix — returns
 * `application/octet-stream`.
 *
 * Android Auto (and Bluetooth AVRCP cover-art consumers) reject
 * non-image MIME types from the metadata pipeline, leaving the cover
 * blank. Reporting `image/jpeg` here unblocks the AA loader without
 * having to migrate every artwork file to a `.jpg`-suffixed name on
 * disk.
 *
 * The path-based discrimination (`/artwork/`) keeps the existing
 * backup-share + external-storage paths unchanged — those still get
 * the parent class's content-type guess.
 */
class RadioShedFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? {
        // FileProvider URIs encode the path as
        //   /<name>/<filename> where <name> matches the `name=`
        //   attribute in file_provider_paths.xml.
        // For artwork we set name="artwork" so URIs look like
        //   content://<authority>/artwork/<sha256>
        // and uri.path starts with "/artwork/".
        if (uri.path?.startsWith("/artwork/") == true) {
            return "image/jpeg"
        }
        return super.getType(uri)
    }
}
