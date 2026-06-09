package com.visorcraft.radioshed.di

import android.content.Context
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.AudioEngineAndroid
import com.visorcraft.radioshed.audio.EqualizerController
import com.visorcraft.radioshed.audio.AndroidEqualizerController
import com.visorcraft.radioshed.crash.AndroidCrashLogReader
import com.visorcraft.radioshed.crash.AndroidCrashLogSharer
import com.visorcraft.radioshed.crash.CrashLogReader
import com.visorcraft.radioshed.crash.CrashLogSharer
import com.visorcraft.radioshed.data.DatabaseDriverFactory
import com.visorcraft.radioshed.permissions.StoragePermissions
import com.visorcraft.radioshed.source.AndroidArtworkPersister
import com.visorcraft.radioshed.source.AndroidBackupSharer
import com.visorcraft.radioshed.source.AndroidBackupStorage
import com.visorcraft.radioshed.source.AndroidConnectivityChecker
import com.visorcraft.radioshed.source.AndroidCoverArtFetcher
import com.visorcraft.radioshed.source.AndroidFileBrowser
import com.visorcraft.radioshed.source.AndroidFileEnumerator
import com.visorcraft.radioshed.source.AndroidFolderRevealer
import com.visorcraft.radioshed.source.AndroidLocalLyricsLoader
import com.visorcraft.radioshed.source.AndroidLyricsFetcher
import com.visorcraft.radioshed.source.AndroidTagReader
import com.visorcraft.radioshed.source.AndroidTagWriter
import com.visorcraft.radioshed.source.AndroidBackupPackager
import com.visorcraft.radioshed.source.BackupPackager
import com.visorcraft.radioshed.source.AndroidMusicBrainzClient
import com.visorcraft.radioshed.source.AndroidWikipediaBioFetcher
import com.visorcraft.radioshed.source.AndroidListenBrainzClient
import com.visorcraft.radioshed.source.MusicBrainzClient
import com.visorcraft.radioshed.source.WikipediaBioFetcher
import com.visorcraft.radioshed.source.ListenBrainzClient
import com.visorcraft.radioshed.source.AndroidSubsonicClient
import com.visorcraft.radioshed.source.SubsonicClient
import com.visorcraft.radioshed.source.ArtworkPersister
import com.visorcraft.radioshed.source.BackupSharer
import com.visorcraft.radioshed.source.BackupStorage
import com.visorcraft.radioshed.source.ConnectivityChecker
import com.visorcraft.radioshed.source.CoverArtFetcher
import com.visorcraft.radioshed.source.FileBrowser
import com.visorcraft.radioshed.source.FileEnumerator
import com.visorcraft.radioshed.source.AndroidRingtoneSetter
import com.visorcraft.radioshed.source.AndroidHistoryExporter
import com.visorcraft.radioshed.source.AndroidPlaylistExporter
import com.visorcraft.radioshed.source.AndroidTrackSharer
import com.visorcraft.radioshed.source.HistoryExporter
import com.visorcraft.radioshed.source.PlaylistExporter
import com.visorcraft.radioshed.source.FolderRevealer
import com.visorcraft.radioshed.source.RingtoneSetter
import com.visorcraft.radioshed.source.TrackSharer
import com.visorcraft.radioshed.source.LocalLyricsLoader
import com.visorcraft.radioshed.source.LyricsFetcher
import com.visorcraft.radioshed.source.StorageVolumes
import com.visorcraft.radioshed.source.TagReader
import com.visorcraft.radioshed.source.TagWriter
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single { DatabaseDriverFactory(androidContext()) }
    single<Settings> {
        SharedPreferencesSettings(
            androidContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        )
    }

    // Phase 3: scanner platform impls
    single<FileEnumerator> { AndroidFileEnumerator() }
    single<TagReader> { AndroidTagReader() }
    single<TagWriter> { AndroidTagWriter() }
    single<BackupPackager> { AndroidBackupPackager() }
    single<MusicBrainzClient> { AndroidMusicBrainzClient() }
    single<WikipediaBioFetcher> { AndroidWikipediaBioFetcher() }
    single<ListenBrainzClient> { AndroidListenBrainzClient() }
    single<SubsonicClient> { AndroidSubsonicClient() }
    single<ArtworkPersister> { AndroidArtworkPersister(androidContext()) }
    single<CoverArtFetcher> { AndroidCoverArtFetcher() }
    single<ConnectivityChecker> { AndroidConnectivityChecker(androidContext()) }
    single<LyricsFetcher> { AndroidLyricsFetcher() }
    single<LocalLyricsLoader> { AndroidLocalLyricsLoader() }
    single<BackupStorage> { AndroidBackupStorage(androidContext()) }
    single<BackupSharer> { AndroidBackupSharer(androidContext()) }
    single<FileBrowser> { AndroidFileBrowser() }
    single<FolderRevealer> { AndroidFolderRevealer(androidContext()) }
    single<RingtoneSetter> { AndroidRingtoneSetter(androidContext()) }
    single<TrackSharer> { AndroidTrackSharer(androidContext()) }
    single<PlaylistExporter> { AndroidPlaylistExporter(androidContext()) }
    single<HistoryExporter> { AndroidHistoryExporter(androidContext()) }
    single { StoragePermissions(androidContext()) }
    single { StorageVolumes(androidContext()) }

    // Phase 4: audio engine
    single<AudioEngine> { AudioEngineAndroid(androidContext()) }
    // Equalizer — attaches to ExoPlayer's audio session lazily when
    // RadioShedMediaSessionService reports onAudioSessionIdChanged.
    single<EqualizerController> { AndroidEqualizerController(get()) }

    // Crash log reader — backed by ApplicationExitInfo (API 30+).
    single<CrashLogReader> { AndroidCrashLogReader(androidContext()) }
    single<CrashLogSharer> { AndroidCrashLogSharer(androidContext()) }

    // Syncthing detection for the setup wizard. Probe-only — we don't
    // talk to Syncthing's REST API or modify its config; the wizard
    // just deep-links into the app at the right moments.
    single { com.visorcraft.radioshed.source.SyncthingDetector(androidContext()) }

    single<HttpClient> {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }
}

private const val PREFS_NAME = "radioshed.prefs"
