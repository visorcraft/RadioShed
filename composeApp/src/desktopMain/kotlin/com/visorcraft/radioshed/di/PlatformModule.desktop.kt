package com.visorcraft.radioshed.di

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.AudioEngineDesktop
import com.visorcraft.radioshed.audio.EqualizerController
import com.visorcraft.radioshed.audio.NullEqualizerController
import com.visorcraft.radioshed.audio.NullTtsEngine
import com.visorcraft.radioshed.audio.TtsEngine
import com.visorcraft.radioshed.crash.CrashLogReader
import com.visorcraft.radioshed.crash.CrashLogSharer
import com.visorcraft.radioshed.data.DatabaseDriverFactory
import com.visorcraft.radioshed.permissions.StoragePermissions
import com.visorcraft.radioshed.source.ArtworkPersister
import com.visorcraft.radioshed.source.BackupPackager
import com.visorcraft.radioshed.source.BackupSharer
import com.visorcraft.radioshed.source.BackupStorage
import com.visorcraft.radioshed.source.ConnectivityChecker
import com.visorcraft.radioshed.source.CoverArtFetcher
import com.visorcraft.radioshed.source.DesktopArtworkPersister
import com.visorcraft.radioshed.source.DesktopBackupPackager
import com.visorcraft.radioshed.source.DesktopBackupSharer
import com.visorcraft.radioshed.source.DesktopBackupStorage
import com.visorcraft.radioshed.source.DesktopConnectivityChecker
import com.visorcraft.radioshed.source.DesktopCrashLogReader
import com.visorcraft.radioshed.source.DesktopCrashLogSharer
import com.visorcraft.radioshed.source.DesktopFileBrowser
import com.visorcraft.radioshed.source.DesktopFileEnumerator
import com.visorcraft.radioshed.source.DesktopFolderRevealer
import com.visorcraft.radioshed.source.DesktopHistoryExporter
import com.visorcraft.radioshed.source.DesktopPlaylistExporter
import com.visorcraft.radioshed.source.DesktopRingtoneSetter
import com.visorcraft.radioshed.source.DesktopTagReader
import com.visorcraft.radioshed.source.DesktopTagWriter
import com.visorcraft.radioshed.source.DesktopTrackSharer
import com.visorcraft.radioshed.source.FileBrowser
import com.visorcraft.radioshed.source.FileEnumerator
import com.visorcraft.radioshed.source.FolderRevealer
import com.visorcraft.radioshed.source.HistoryExporter
import com.visorcraft.radioshed.source.ListenBrainzClient
import com.visorcraft.radioshed.source.LocalLyricsLoader
import com.visorcraft.radioshed.source.LyricsFetcher
import com.visorcraft.radioshed.source.MusicBrainzClient
import com.visorcraft.radioshed.source.DesktopLocalLyricsLoader
import com.visorcraft.radioshed.source.DesktopLyricsFetcher
import com.visorcraft.radioshed.source.NullCoverArtFetcher
import com.visorcraft.radioshed.source.NullListenBrainzClient
import com.visorcraft.radioshed.source.NullMusicBrainzClient
import com.visorcraft.radioshed.source.NullSubsonicClient
import com.visorcraft.radioshed.source.NullWikipediaBioFetcher
import com.visorcraft.radioshed.source.PlaylistExporter
import com.visorcraft.radioshed.source.RingtoneSetter
import com.visorcraft.radioshed.source.StorageVolumes
import com.visorcraft.radioshed.source.SubsonicClient
import com.visorcraft.radioshed.source.SyncthingDetector
import com.visorcraft.radioshed.source.TagReader
import com.visorcraft.radioshed.source.TagWriter
import com.visorcraft.radioshed.source.TrackSharer
import com.visorcraft.radioshed.source.WikipediaBioFetcher
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.prefs.Preferences

/**
 * Desktop Koin module. Mirrors the iOS module's structure but binds
 * desktop-specific implementations: real DB driver, real file
 * scanning + tag reading + artwork persisting, JNA libvlc audio, and
 * Null* stubs for the surfaces deferred from the spike (lyrics fetchers,
 * MusicBrainz, ListenBrainz, etc.).
 *
 * Settings: backed by `java.util.prefs.Preferences` under the
 * `com/visorcraft/radioshed` user node. Cross-platform with the
 * Android `SharedPreferencesSettings` semantics — same keys, same
 * types, same put/get contracts.
 */
actual val platformModule: Module = module {
    single { DatabaseDriverFactory() }
    single<Settings> {
        PreferencesSettings(Preferences.userRoot().node("com/visorcraft/radioshed"))
    }

    // Phase 3: scanner platform impls — real for desktop.
    single<FileEnumerator> { DesktopFileEnumerator() }
    single<TagReader> { DesktopTagReader() }
    single<TagWriter> { DesktopTagWriter() }
    single<BackupPackager> { DesktopBackupPackager() }
    single<MusicBrainzClient> { NullMusicBrainzClient() }
    single<WikipediaBioFetcher> { NullWikipediaBioFetcher() }
    single<ListenBrainzClient> { NullListenBrainzClient() }
    single<SubsonicClient> { NullSubsonicClient() }
    single<ArtworkPersister> { DesktopArtworkPersister() }
    single<CoverArtFetcher> { NullCoverArtFetcher() }
    single<ConnectivityChecker> { DesktopConnectivityChecker() }
    single<LyricsFetcher> { DesktopLyricsFetcher() }
    single<LocalLyricsLoader> { DesktopLocalLyricsLoader() }
    single<BackupStorage> { DesktopBackupStorage() }
    single<BackupSharer> { DesktopBackupSharer() }
    single<FileBrowser> { DesktopFileBrowser() }
    single<FolderRevealer> { DesktopFolderRevealer() }
    single<RingtoneSetter> { DesktopRingtoneSetter() }
    single<TrackSharer> { DesktopTrackSharer() }
    single<PlaylistExporter> { DesktopPlaylistExporter() }
    single<HistoryExporter> { DesktopHistoryExporter() }
    single { StoragePermissions() }
    single { StorageVolumes() }
    single { SyncthingDetector() }

    // Phase 4: audio engine — JNA libvlc binding, lazily initialised on
    // first play() call so a missing libvlc surfaces as a clear error
    // rather than crashing app startup.
    single<AudioEngine> { AudioEngineDesktop() }
    single<EqualizerController> { NullEqualizerController() }
    single<TtsEngine> { NullTtsEngine() }

    single<CrashLogReader> { DesktopCrashLogReader() }
    single<CrashLogSharer> { DesktopCrashLogSharer() }
}
