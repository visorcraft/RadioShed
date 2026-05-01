package com.visorcraft.radioshed.di

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.AudioEngineIos
import com.visorcraft.radioshed.data.DatabaseDriverFactory
import com.visorcraft.radioshed.permissions.StoragePermissions
import com.visorcraft.radioshed.source.FileEnumerator
import com.visorcraft.radioshed.source.IosFileEnumerator
import com.visorcraft.radioshed.source.IosTagReader
import com.visorcraft.radioshed.source.TagReader
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSUserDefaults

actual val platformModule: Module = module {
    single { DatabaseDriverFactory() }
    single<Settings> { NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults) }

    // Phase 3: scanner platform impls (stubs until Mac-side iOS work lands)
    single<FileEnumerator> { IosFileEnumerator() }
    single<TagReader> { IosTagReader() }
    single { StoragePermissions() }

    // Phase 4: audio engine stub
    single<AudioEngine> { AudioEngineIos() }
}
