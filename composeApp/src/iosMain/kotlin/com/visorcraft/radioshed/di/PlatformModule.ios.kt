package com.visorcraft.radioshed.di

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.AudioEngineIos
import com.visorcraft.radioshed.audio.EqualizerController
import com.visorcraft.radioshed.audio.NullEqualizerController
import com.visorcraft.radioshed.crash.CrashLogReader
import com.visorcraft.radioshed.crash.CrashLogSharer
import com.visorcraft.radioshed.crash.IosCrashLogReader
import com.visorcraft.radioshed.crash.IosCrashLogSharer
import com.visorcraft.radioshed.data.DatabaseDriverFactory
import com.visorcraft.radioshed.permissions.StoragePermissions
import com.visorcraft.radioshed.source.ArtworkPersister
import com.visorcraft.radioshed.source.CoverArtFetcher
import com.visorcraft.radioshed.source.FileBrowser
import com.visorcraft.radioshed.source.FileEnumerator
import com.visorcraft.radioshed.source.FolderRevealer
import com.visorcraft.radioshed.source.IosArtworkPersister
import com.visorcraft.radioshed.source.IosFileBrowser
import com.visorcraft.radioshed.source.IosFileEnumerator
import com.visorcraft.radioshed.source.IosFolderRevealer
import com.visorcraft.radioshed.source.IosRingtoneSetter
import com.visorcraft.radioshed.source.IosHistoryExporter
import com.visorcraft.radioshed.source.IosPlaylistExporter
import com.visorcraft.radioshed.source.IosTrackSharer
import com.visorcraft.radioshed.source.HistoryExporter
import com.visorcraft.radioshed.source.PlaylistExporter
import com.visorcraft.radioshed.source.RingtoneSetter
import com.visorcraft.radioshed.source.TrackSharer
import com.visorcraft.radioshed.source.IosTagReader
import com.visorcraft.radioshed.source.IosTagWriter
import com.visorcraft.radioshed.source.BackupPackager
import com.visorcraft.radioshed.source.NullBackupPackager
import com.visorcraft.radioshed.source.BackupSharer
import com.visorcraft.radioshed.source.BackupStorage
import com.visorcraft.radioshed.source.ConnectivityChecker
import com.visorcraft.radioshed.source.IosConnectivityChecker
import com.visorcraft.radioshed.source.LocalLyricsLoader
import com.visorcraft.radioshed.source.LyricsFetcher
import com.visorcraft.radioshed.source.NullBackupSharer
import com.visorcraft.radioshed.source.NullBackupStorage
import com.visorcraft.radioshed.source.NullCoverArtFetcher
import com.visorcraft.radioshed.source.NullLocalLyricsLoader
import com.visorcraft.radioshed.source.NullLyricsFetcher
import com.visorcraft.radioshed.source.StorageVolumes
import com.visorcraft.radioshed.source.TagReader
import com.visorcraft.radioshed.source.TagWriter
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSUserDefaults

actual val platformModule: Module = module {
    single { DatabaseDriverFactory() }
    single<Settings> { NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults) }

    // Phase 3: scanner platform impls (stubs until Mac-side iOS work lands)
    single<FileEnumerator> { IosFileEnumerator() }
    single<TagReader> { IosTagReader() }
    single<TagWriter> { IosTagWriter() }
    single<BackupPackager> { NullBackupPackager() }
    single<ArtworkPersister> { IosArtworkPersister() }
    single<CoverArtFetcher> { NullCoverArtFetcher() }
    single<ConnectivityChecker> { IosConnectivityChecker() }
    single<LyricsFetcher> { NullLyricsFetcher() }
    single<LocalLyricsLoader> { NullLocalLyricsLoader() }
    single<BackupStorage> { NullBackupStorage() }
    single<BackupSharer> { NullBackupSharer() }
    single<FileBrowser> { IosFileBrowser() }
    single<FolderRevealer> { IosFolderRevealer() }
    single<RingtoneSetter> { IosRingtoneSetter() }
    single<TrackSharer> { IosTrackSharer() }
    single<PlaylistExporter> { IosPlaylistExporter() }
    single<HistoryExporter> { IosHistoryExporter() }
    single { StoragePermissions() }
    single { StorageVolumes() }

    // Phase 4: audio engine stub
    single<AudioEngine> { AudioEngineIos() }
    single<EqualizerController> { NullEqualizerController() }

    // Crash log reader — iOS surfaces crashes via Xcode/TestFlight, not
    // an in-app feed. Stub returns empty.
    single<CrashLogReader> { IosCrashLogReader() }
    single<CrashLogSharer> { IosCrashLogSharer() }
}
