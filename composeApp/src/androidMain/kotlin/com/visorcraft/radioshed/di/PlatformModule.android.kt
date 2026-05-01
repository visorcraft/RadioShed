package com.visorcraft.radioshed.di

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.AudioEngineAndroid
import com.visorcraft.radioshed.data.DatabaseDriverFactory
import com.visorcraft.radioshed.permissions.StoragePermissions
import com.visorcraft.radioshed.source.AndroidFileEnumerator
import com.visorcraft.radioshed.source.AndroidTagReader
import com.visorcraft.radioshed.source.FileEnumerator
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
    single { StoragePermissions(androidContext()) }
    single { StorageVolumes(androidContext()) }

    // Phase 4: audio engine
    single<AudioEngine> { AudioEngineAndroid(androidContext()) }
}

private const val PREFS_NAME = "radioshed.prefs"
