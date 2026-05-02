package com.visorcraft.radioshed.di

import app.cash.sqldelight.db.SqlDriver
import com.visorcraft.radioshed.data.ArtworkCacheRepository
import com.visorcraft.radioshed.data.AudioSettingsRepository
import com.visorcraft.radioshed.data.BackupRepository
import com.visorcraft.radioshed.data.CastTargetRepository
import com.visorcraft.radioshed.data.DatabaseDriverFactory
import com.visorcraft.radioshed.data.DjCacheRepository
import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.LyricsCacheRepository
import com.visorcraft.radioshed.data.PlaylistRepository
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.data.SearchRepository
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.data.WidgetConfigRepository
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.scanner.Scanner
import com.visorcraft.radioshed.source.MissingArtworkSync
import com.visorcraft.radioshed.stations.PresetSeeder
import com.visorcraft.radioshed.stations.SourceRuleEvaluator
import com.visorcraft.radioshed.stations.StationPlayback
import org.koin.core.module.Module
import org.koin.dsl.module

/** Platform-specific bindings — DatabaseDriverFactory + Settings come from here. */
expect val platformModule: Module

/** Cross-platform bindings — database, all repositories, settings store. */
val sharedModule: Module = module {
    single<SqlDriver> { get<DatabaseDriverFactory>().create() }
    single { RadioShedDatabase(get()) }

    single { SettingsStore(get()) }

    single { FolderRepository(get()) }
    single { LibraryRepository(get()) }
    single { StationRepository(get()) }
    single { PlaylistRepository(get()) }
    single { RatingRepository(get()) }
    single { SearchRepository(get()) }

    single { ArtworkCacheRepository(get()) }
    single { LyricsCacheRepository(get()) }
    single { DjCacheRepository(get()) }

    single { AudioSettingsRepository(get()) }
    single { CastTargetRepository(get()) }
    single { WidgetConfigRepository(get()) }
    single { BackupRepository(get()) }

    // Phase 3: scanner. FileEnumerator + TagReader come from platformModule.
    single { Scanner(get(), get(), get(), get(), get()) }

    // Cover Art Archive sweep. CoverArtFetcher is platform-specific
    // (Android uses HttpURLConnection; iOS is a Null stub for now).
    single { MissingArtworkSync(get(), get(), get()) }

    // Phase 5: stations engine. AudioEngine comes from platformModule.
    single { SourceRuleEvaluator(get(), get(), get()) }
    single { StationPlayback(get(), get(), get(), get(), get()) }
    single { PresetSeeder(get(), get()) }
}

/** Convenience entry-point used by both platforms. */
fun appModules(): List<Module> = listOf(platformModule, sharedModule)
