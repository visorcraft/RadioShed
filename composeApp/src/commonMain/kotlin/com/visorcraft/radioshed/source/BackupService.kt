package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.PlaylistRepository
import com.visorcraft.radioshed.data.RadioShedJson
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.data.nowMillis
import kotlinx.serialization.encodeToString

/**
 * Orchestrates JSON-bundle backup + restore. Round-trips through
 * [BackupStorage] for the file IO, and through the standard repos for
 * data access — so the same code path works regardless of where the
 * file lives (filesDir today, SAF document tree later).
 *
 * Restore semantics:
 *  - Stations: inserted as **new** rows (not merged by name). User-
 *    visible result: importing twice produces duplicates. For v1 this
 *    is acceptable — the user can long-press → Delete on duplicates.
 *  - Playlists: same; tracks are looked up by path and silently skipped
 *    if not found (e.g. file moved to a new location).
 *  - Ratings: looked up by path → upsert. A track that doesn't exist
 *    in the current library is skipped — ratings are reapplied if the
 *    user later adds the file back via folder scan.
 *
 * Built-in stations are NOT included in the bundle: the seeder will
 * recreate them on launch, and bundling them would create dupes for
 * users who restore on a fresh install.
 */
class BackupService(
    private val storage: BackupStorage,
    private val stationRepo: StationRepository,
    private val playlistRepo: PlaylistRepository,
    private val ratingRepo: RatingRepository,
    private val libraryRepo: LibraryRepository,
    private val settings: SettingsStore,
    private val packager: BackupPackager,
    private val artworkPersister: ArtworkPersister,
) {
    suspend fun listBackups(): List<BackupFile> = storage.list()

    /**
     * Cap on how many backup files we keep on disk. When [export]
     * succeeds we prune anything older than the most-recent N. This
     * keeps `filesDir/backups/` bounded — without it, a user who
     * makes a backup before each car drive would accumulate hundreds
     * of files. Read live from `SettingsStore.getBackupRetention()`
     * so the user can dial it up or down without restarting the app.
     */
    private val maxBackupsToRetain: Int
        get() = settings.getBackupRetention()

    suspend fun export(): ExportResult {
        val stations =
            stationRepo.list().filter { !it.isBuiltIn }.map { s ->
                BackupStation(
                    name = s.name,
                    color = s.color,
                    icon = s.icon,
                    customSubtitle = s.customSubtitle,
                    sourceJson = RadioShedJson.encodeToString(s.source),
                    playbackJson = RadioShedJson.encodeToString(s.playback),
                    isPinned = s.isPinned,
                    sortPosition = s.sortPosition,
                )
            }

        val playlists =
            playlistRepo.list().map { p ->
                BackupPlaylist(
                    name = p.name,
                    description = p.description,
                    trackPaths = playlistRepo.tracksIn(p.id).map { it.path },
                )
            }

        val ratings =
            ratingRepo.listAll().mapNotNull { r ->
                val track = libraryRepo.getTrackById(r.trackId) ?: return@mapNotNull null
                BackupRating(
                    trackPath = track.path,
                    stars = r.stars,
                    ratedAt = r.ratedAt,
                )
            }

        // Liked tracks: serialize keyed by path so the restore can find
        // the same file on disk after a fresh install / new device.
        // liked_at metadata is preserved so the Liked Songs view's
        // "most recently liked first" sort survives the round-trip.
        val likedTracks =
            ratingRepo.likedTracksWithTimestamps()
                .mapNotNull { (id, likedAt) ->
                    val track = libraryRepo.getTrackById(id) ?: return@mapNotNull null
                    BackupLikedTrack(trackPath = track.path, likedAt = likedAt)
                }

        // Settings + custom EQ presets — round-tripped so a fresh
        // install fully reconstructs the user's config, not just
        // their station/playlist data. Per-device state (last
        // station id, library tab index, etc.) is excluded inside
        // SettingsStore.dumpForBackup.
        val settingsDump = settings.dumpForBackup()
        val customEqPresets =
            settings.getCustomEqPresets().map {
                BackupEqPreset(name = it.name, gainsDb = it.gainsDb)
            }

        val bundle =
            BackupBundle(
                exportedAt = nowMillis(),
                stations = stations,
                playlists = playlists,
                ratings = ratings,
                likedTracks = likedTracks,
                settings = settingsDump,
                customEqPresets = customEqPresets,
            )
        val json = RadioShedJson.encodeToString(bundle)
        val jsonBytes = json.encodeToByteArray()
        // Try the zipped `.rsbak` format first — it bundles artwork
        // alongside the manifest so a fresh install fully reconstructs
        // the user's library look-and-feel rather than re-fetching every
        // album cover from Cover Art Archive (which the user might have
        // disabled via Connectivity policy). Falls back to plain JSON
        // if the platform doesn't ship a packager (iOS today) or if
        // packing failed for any reason.
        val artworkBlobs: Map<String, ByteArray> =
            runCatching {
                artworkPersister.listBlobs()
                    .associate { (name, bytes) -> "artwork/$name" to bytes }
            }.getOrDefault(emptyMap())
        val zipped = packager.pack(jsonBytes, artworkBlobs)
        val (filename, payload) =
            if (zipped != null) {
                "radioshed-backup-${nowMillis()}.rsbak" to zipped
            } else {
                "radioshed-backup-${nowMillis()}.json" to jsonBytes
            }
        val file =
            storage.write(filename, payload)
                ?: return ExportResult.Failed("Couldn't write backup file.")

        // Prune older backups to a small cap so the directory doesn't
        // accumulate unboundedly. Errors during prune are swallowed —
        // a failed delete is not a reason to fail the export the user
        // just successfully completed.
        //
        // Sort newest-first explicitly here. The BackupStorage.list()
        // contract isn't documented, and an alphabetical-ascending
        // implementation would have us delete the NEWEST files via
        // drop(N) — the exact opposite of what the user wants.
        //
        // Also exclude the just-written file from the prune candidate
        // list. Some Android filesystems (especially SAF-backed) take
        // a moment to surface newly-written files via list(); without
        // this filter, a list() that doesn't yet see the new file
        // could return exactly maxBackupsToRetain old entries and we'd
        // delete one of them on a stale list — including, in some
        // race orderings, the new file we just wrote.
        runCatching {
            val all =
                storage.list()
                    .filter { it.uri != file.uri }
                    .sortedByDescending { it.createdAt }
            if (all.size >= maxBackupsToRetain) {
                all.drop(maxBackupsToRetain - 1).forEach { stale ->
                    runCatching { storage.delete(stale.uri) }
                }
            }
        }

        return ExportResult.Saved(
            file = file,
            stationCount = stations.size,
            playlistCount = playlists.size,
            ratingCount = ratings.size,
        )
    }

    suspend fun restore(fileUri: String): RestoreResult {
        val bytes =
            storage.read(fileUri)
                ?: return RestoreResult.Failed("Couldn't read backup file.")
        return restoreFromBytes(bytes)
    }

    /**
     * Read-only inspection of a backup file. Parses the bundle but
     * applies nothing — useful for the "Restore?" dialog so the user
     * can see what they're about to merge before committing. Returns
     * `null` on parse failure (the UI surfaces a generic
     * "couldn't read" state in that case rather than offering a
     * preview of garbage).
     */
    suspend fun preview(fileUri: String): BundlePreview? {
        val bytes = storage.read(fileUri) ?: return null
        return previewFromBytes(bytes)
    }

    suspend fun previewFromBytes(bytes: ByteArray): BundlePreview? {
        // Auto-detect zip vs plain JSON. previewFromBytes / restoreFromBytes
        // both run through this branch so any caller — Backup screen,
        // system file picker, share-sheet import — gets the same
        // format-agnostic behaviour.
        val manifestBytes =
            if (looksLikeZip(bytes)) {
                packager.unpack(bytes)?.manifestJson ?: return null
            } else {
                bytes
            }
        val bundle =
            runCatching {
                RadioShedJson.decodeFromString<BackupBundle>(manifestBytes.decodeToString())
            }.getOrNull() ?: return null
        return BundlePreview(
            version = bundle.version,
            exportedAtMs = bundle.exportedAt,
            stationCount = bundle.stations.size,
            playlistCount = bundle.playlists.size,
            ratingCount = bundle.ratings.size,
            likedCount = bundle.likedTracks.size,
            settingsCount = bundle.settings.size,
            eqPresetCount = bundle.customEqPresets.size,
        )
    }

    /**
     * Lightweight read-only summary of a backup bundle's contents.
     * The Restore? dialog renders this before the user commits.
     */
    data class BundlePreview(
        val version: Int,
        val exportedAtMs: Long,
        val stationCount: Int,
        val playlistCount: Int,
        val ratingCount: Int,
        val likedCount: Int,
        val settingsCount: Int,
        val eqPresetCount: Int,
    )

    /**
     * Restore from raw bundle bytes — used by the "Import…" path
     * where the file lives outside our private backups dir (e.g. a
     * `content://` URI from the system file picker, or a downloaded
     * JSON shared from another device). Same parsing + import logic
     * as [restore]; just skips the storage.read step.
     */
    suspend fun restoreFromBytes(bytes: ByteArray): RestoreResult {
        // Detect zip vs plain JSON. The zip path also restores artwork
        // blobs alongside the manifest, so a fresh-install restore can
        // paint the library with its original cover art instead of
        // refetching from Cover Art Archive (which the user may have
        // disabled via Connectivity policy).
        val (manifestBytes, artworkBlobs) =
            if (looksLikeZip(bytes)) {
                val unpacked =
                    packager.unpack(bytes)
                        ?: return RestoreResult.Failed(
                            "Backup file is a zip but couldn't be opened.",
                        )
                unpacked.manifestJson to unpacked.blobs
            } else {
                bytes to emptyMap()
            }
        val bundle =
            runCatching {
                RadioShedJson.decodeFromString<BackupBundle>(manifestBytes.decodeToString())
            }.getOrElse {
                return RestoreResult.Failed("Backup file is corrupted: ${it.message ?: "decode failed"}")
            }
        if (artworkBlobs.isNotEmpty()) {
            // Strip the `artwork/` prefix so each entry restores under
            // its content-addressed basename — same shape the persister
            // wrote it with originally.
            val flat = artworkBlobs.mapKeys { it.key.removePrefix("artwork/") }
            runCatching { artworkPersister.restoreBlobs(flat) }
        }
        if (bundle.version < 1 || bundle.version > BackupBundle.CURRENT_VERSION) {
            return RestoreResult.Failed(
                "Backup format version ${bundle.version} is incompatible with this app " +
                    "(supported: 1..${BackupBundle.CURRENT_VERSION}).",
            )
        }

        // Track per-bucket failures separately from "skipped" (track
        // not in library). A failure is a thrown exception during the
        // insert / setRating / like call — almost certainly a SQLite
        // constraint or unrecognized JSON, distinct from the benign
        // "we don't have that track on this device" case. Both numbers
        // surface in the result so the user can tell which class of
        // problem to chase.
        var stationsAdded = 0
        var stationsFailed = 0
        bundle.stations.forEach { s ->
            runCatching {
                // StationRepository.insert returns the new row's id via
                // RETURNING. Use it directly to set the pinned flag —
                // the previous "list().lastOrNull { name == s.name }"
                // pattern silently picked the wrong row when names
                // collided.
                val newId =
                    stationRepo.insert(
                        name = s.name,
                        source = RadioShedJson.decodeFromString(s.sourceJson),
                        playback = RadioShedJson.decodeFromString(s.playbackJson),
                        color = s.color,
                        icon = s.icon,
                        customSubtitle = s.customSubtitle,
                        sortPosition = s.sortPosition,
                        isBuiltIn = false,
                    )
                if (s.isPinned) {
                    stationRepo.setPinned(newId, true)
                }
                stationsAdded += 1
            }.onFailure { stationsFailed += 1 }
        }

        var playlistsAdded = 0
        var playlistsFailed = 0
        bundle.playlists.forEach { p ->
            runCatching {
                val playlistId = playlistRepo.insert(name = p.name, description = p.description)
                p.trackPaths.forEachIndexed { idx, path ->
                    val track = libraryRepo.getTrackByPathOrSuffix(path) ?: return@forEachIndexed
                    playlistRepo.addTrack(playlistId, track.id, position = idx)
                }
                playlistsAdded += 1
            }.onFailure { playlistsFailed += 1 }
        }

        var ratingsApplied = 0
        var ratingsSkipped = 0
        var ratingsFailed = 0
        bundle.ratings.forEach { r ->
            val track = libraryRepo.getTrackByPathOrSuffix(r.trackPath)
            if (track == null) {
                ratingsSkipped += 1
                return@forEach
            }
            runCatching {
                ratingRepo.setRating(track.id, r.stars)
                ratingsApplied += 1
            }.onFailure { ratingsFailed += 1 }
        }

        var likedApplied = 0
        var likedSkipped = 0
        var likedFailed = 0
        bundle.likedTracks.forEach { l ->
            val track = libraryRepo.getTrackByPathOrSuffix(l.trackPath)
            if (track == null) {
                likedSkipped += 1
                return@forEach
            }
            runCatching {
                ratingRepo.like(track.id, l.likedAt)
                likedApplied += 1
            }.onFailure { likedFailed += 1 }
        }

        // Settings + EQ presets — only present in v3+ bundles.
        // SettingsStore.restoreFromBackup blocks per-device keys
        // defensively even if the bundle's a tampered older one.
        var settingsApplied = 0
        if (bundle.settings.isNotEmpty()) {
            runCatching {
                settings.restoreFromBackup(bundle.settings)
                settingsApplied = bundle.settings.size
            }
        }
        var eqPresetsApplied = 0
        var eqPresetsFailed = 0
        bundle.customEqPresets.forEach { p ->
            runCatching {
                settings.saveCustomEqPreset(
                    com.visorcraft.radioshed.data.CustomEqPreset(
                        name = p.name,
                        gainsDb = p.gainsDb,
                    ),
                )
                eqPresetsApplied += 1
            }.onFailure { eqPresetsFailed += 1 }
        }
        val totalFailed =
            stationsFailed + playlistsFailed + ratingsFailed +
                likedFailed + eqPresetsFailed

        return RestoreResult.Done(
            stationsAdded = stationsAdded,
            playlistsAdded = playlistsAdded,
            ratingsApplied = ratingsApplied,
            ratingsSkipped = ratingsSkipped,
            likedApplied = likedApplied,
            likedSkipped = likedSkipped,
            settingsApplied = settingsApplied,
            eqPresetsApplied = eqPresetsApplied,
            errors = totalFailed,
        )
    }

    suspend fun delete(fileUri: String): Boolean = storage.delete(fileUri)

    sealed interface ExportResult {
        data class Saved(
            val file: BackupFile,
            val stationCount: Int,
            val playlistCount: Int,
            val ratingCount: Int,
        ) : ExportResult

        data class Failed(val reason: String) : ExportResult
    }

    sealed interface RestoreResult {
        data class Done(
            val stationsAdded: Int,
            val playlistsAdded: Int,
            val ratingsApplied: Int,
            val ratingsSkipped: Int,
            val likedApplied: Int = 0,
            val likedSkipped: Int = 0,
            val settingsApplied: Int = 0,
            val eqPresetsApplied: Int = 0,
            /**
             * Total exceptions caught across all per-row inserts
             * (stations / playlists / ratings / liked / EQ presets).
             * Distinct from "skipped" (track not in library) — these
             * are unexpected failures the user should know about.
             */
            val errors: Int = 0,
        ) : RestoreResult

        data class Failed(val reason: String) : RestoreResult
    }
}
