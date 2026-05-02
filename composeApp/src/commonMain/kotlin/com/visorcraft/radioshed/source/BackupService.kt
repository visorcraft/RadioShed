package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.PlaylistRepository
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.data.RadioShedJson
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
) {

    suspend fun listBackups(): List<BackupFile> = storage.list()

    suspend fun export(): ExportResult {
        val stations = stationRepo.list().filter { !it.isBuiltIn }.map { s ->
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

        val playlists = playlistRepo.list().map { p ->
            BackupPlaylist(
                name = p.name,
                description = p.description,
                trackPaths = playlistRepo.tracksIn(p.id).map { it.path },
            )
        }

        val ratings = ratingRepo.listAll().mapNotNull { r ->
            val track = libraryRepo.getTrackById(r.trackId) ?: return@mapNotNull null
            BackupRating(
                trackPath = track.path,
                stars = r.stars,
                ratedAt = r.ratedAt,
            )
        }

        val bundle = BackupBundle(
            exportedAt = nowMillis(),
            stations = stations,
            playlists = playlists,
            ratings = ratings,
        )
        val json = RadioShedJson.encodeToString(bundle)
        val filename = "radioshed-backup-${nowMillis()}.json"
        val file = storage.write(filename, json.encodeToByteArray())
            ?: return ExportResult.Failed("Couldn't write backup file.")

        return ExportResult.Saved(
            file = file,
            stationCount = stations.size,
            playlistCount = playlists.size,
            ratingCount = ratings.size,
        )
    }

    suspend fun restore(fileUri: String): RestoreResult {
        val bytes = storage.read(fileUri)
            ?: return RestoreResult.Failed("Couldn't read backup file.")
        val bundle = runCatching {
            RadioShedJson.decodeFromString<BackupBundle>(bytes.decodeToString())
        }.getOrElse {
            return RestoreResult.Failed("Backup file is corrupted: ${it.message ?: "decode failed"}")
        }
        if (bundle.version > BackupBundle.CURRENT_VERSION) {
            return RestoreResult.Failed(
                "Backup format version ${bundle.version} is newer than this app supports.",
            )
        }

        var stationsAdded = 0
        bundle.stations.forEach { s ->
            runCatching {
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
                    // Newly-inserted id isn't returned in the same row; refetch.
                    val match = stationRepo.list().lastOrNull { it.name == s.name }
                    match?.let { stationRepo.setPinned(it.id, true) }
                }
                stationsAdded += 1
            }
        }

        var playlistsAdded = 0
        bundle.playlists.forEach { p ->
            runCatching {
                val playlistId = playlistRepo.insert(name = p.name, description = p.description)
                p.trackPaths.forEachIndexed { idx, path ->
                    val track = libraryRepo.getTrackByPath(path) ?: return@forEachIndexed
                    playlistRepo.addTrack(playlistId, track.id, position = idx)
                }
                playlistsAdded += 1
            }
        }

        var ratingsApplied = 0
        var ratingsSkipped = 0
        bundle.ratings.forEach { r ->
            val track = libraryRepo.getTrackByPath(r.trackPath)
            if (track == null) { ratingsSkipped += 1; return@forEach }
            runCatching {
                ratingRepo.setRating(track.id, r.stars)
                ratingsApplied += 1
            }
        }

        return RestoreResult.Done(
            stationsAdded = stationsAdded,
            playlistsAdded = playlistsAdded,
            ratingsApplied = ratingsApplied,
            ratingsSkipped = ratingsSkipped,
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
        ) : RestoreResult
        data class Failed(val reason: String) : RestoreResult
    }
}
