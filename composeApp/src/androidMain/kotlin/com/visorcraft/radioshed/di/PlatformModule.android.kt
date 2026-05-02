package com.visorcraft.radioshed.di

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import com.visorcraft.radioshed.audio.AndroidEqualizerController
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.AudioEngineAndroid
import com.visorcraft.radioshed.audio.EqualizerController
import com.visorcraft.radioshed.data.DatabaseDriverFactory
import com.visorcraft.radioshed.permissions.StoragePermissions
import com.visorcraft.radioshed.source.AndroidArtworkPersister
import com.visorcraft.radioshed.source.AndroidBackupStorage
import com.visorcraft.radioshed.source.AndroidCoverArtFetcher
import com.visorcraft.radioshed.source.AndroidFileBrowser
import com.visorcraft.radioshed.source.AndroidFileEnumerator
import com.visorcraft.radioshed.source.AndroidLyricsFetcher
import com.visorcraft.radioshed.source.AndroidTagReader
import com.visorcraft.radioshed.source.ArtworkPersister
import com.visorcraft.radioshed.source.BackupStorage
import com.visorcraft.radioshed.source.CoverArtFetcher
import com.visorcraft.radioshed.source.FileBrowser
import com.visorcraft.radioshed.source.FileEnumerator
import com.visorcraft.radioshed.source.LyricsFetcher
import com.visorcraft.radioshed.source.StorageVolumes
import com.visorcraft.radioshed.source.TagReader
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
    single<ArtworkPersister> { AndroidArtworkPersister(androidContext()) }
    single<CoverArtFetcher> { AndroidCoverArtFetcher() }
    single<LyricsFetcher> { AndroidLyricsFetcher() }
    single<BackupStorage> { AndroidBackupStorage(androidContext()) }
    single<FileBrowser> { AndroidFileBrowser() }
    single { StoragePermissions(androidContext()) }
    single { StorageVolumes(androidContext()) }

    // Phase 4: audio engine
    single<AudioEngine> { AudioEngineAndroid(androidContext()) }
    // Equalizer — attaches to ExoPlayer's audio session lazily when
    // RadioShedMediaSessionService reports onAudioSessionIdChanged.
    single<EqualizerController> { AndroidEqualizerController(get()) }
}

private const val PREFS_NAME = "radioshed.prefs"
