package com.visorcraft.radioshed.di

import app.cash.sqldelight.db.SqlDriver
import com.visorcraft.radioshed.data.ArtworkCacheRepository
import com.visorcraft.radioshed.data.AudioSettingsRepository
import com.visorcraft.radioshed.data.BackupRepository
import com.visorcraft.radioshed.data.CastTargetRepository
import com.visorcraft.radioshed.data.DatabaseDriverFactory
import com.visorcraft.radioshed.data.DatabaseMaintenanceRepository
import com.visorcraft.radioshed.data.DjCacheRepository
import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.LyricsCacheRepository
import com.visorcraft.radioshed.data.PlaylistRepository
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.data.ScrobbleQueueRepository
import com.visorcraft.radioshed.data.SearchRepository
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.data.WidgetConfigRepository
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.audio.SleepTimer
import com.visorcraft.radioshed.car.DriveModeController
import com.visorcraft.radioshed.scanner.Scanner
import com.visorcraft.radioshed.source.BackupService
import com.visorcraft.radioshed.source.LyricsService
import com.visorcraft.radioshed.source.MediaSourceRegistry
import com.visorcraft.radioshed.source.MissingArtworkSync
import com.visorcraft.radioshed.source.MissingLyricsSync
import com.visorcraft.radioshed.source.NetworkPolicyGate
import com.visorcraft.radioshed.source.ScrobbleSync
import com.visorcraft.radioshed.source.SubsonicSource
import com.visorcraft.radioshed.stations.DjAnnouncer
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
    single { ScrobbleQueueRepository(get()) }

    single { ArtworkCacheRepository(get()) }
    single { LyricsCacheRepository(get()) }
    single { DjCacheRepository(get()) }
    single { DatabaseMaintenanceRepository(get()) }

    single { AudioSettingsRepository(get()) }
    single { CastTargetRepository(get()) }
    single { WidgetConfigRepository(get()) }
    single { BackupRepository(get()) }

    // Phase 3: scanner. FileEnumerator + TagReader come from platformModule.
    single { Scanner(get(), get(), get(), get(), get(), get()) }

    // Connectivity policy gate — read by every online fetcher to honour
    // the user's Always / Wi-Fi only / Never / Manual preference.
    single {
        // Disambiguate the two constructors — Koin's `get()` can't
        // pick between (PolicySettingsSource, ConnectivityChecker)
        // and (SettingsStore, ConnectivityChecker) without a type
        // hint. Pass the concrete SettingsStore so production
        // routes through the convenience adapter.
        NetworkPolicyGate(
            get<com.visorcraft.radioshed.data.SettingsStore>(),
            get<com.visorcraft.radioshed.source.ConnectivityChecker>(),
        )
    }

    // Scrobble queue-then-drain coordinator. ListenBrainzClient comes
    // from platformModule (real on Android and desktop, Null stub on iOS).
    // The network gate is passed as a lambda over the NetworkPolicyGate's
    // automatic-fetch check — same gate StationPlayback consulted before
    // the live submit moved here.
    single {
        ScrobbleSync(
            queue = get(),
            client = get(),
            settings = get(),
            allowNetwork = { get<NetworkPolicyGate>().allowAutomaticFetch() },
        )
    }

    // Cover Art Archive sweep. CoverArtFetcher is platform-specific
    // (Android uses HttpURLConnection; iOS is a Null stub for now).
    single { MissingArtworkSync(get(), get(), get(), get()) }

    // LRClib lyrics sweep — same shape as MissingArtworkSync but
    // routes through LyricsService.resolve so the existing local-
    // sidecar-then-network flow + cache management is reused.
    single { MissingLyricsSync(get(), get(), get()) }

    // Sleep timer — singleton so multiple UI surfaces observe the same state.
    single { SleepTimer(get(), get()) }

    // Lyrics: cache + fetcher + local sidecar loader, orchestrated by LyricsService.
    single { LyricsService(get(), get(), get(), get(), get(), get()) }

    // Backup & restore: zipped bundles (manifest.json + artwork blobs)
    // on Android, plain-JSON fallback elsewhere. The packager + artwork
    // persister come from the platform module — packager is null on iOS,
    // which makes export silently fall back to JSON-only output.
    single {
        BackupService(
            get(), get(), get(), get(), get(), get(), get(), get(),
        )
    }

    // Phase 5: stations engine. AudioEngine comes from platformModule.
    // MediaSourceRegistry starts empty — cloud/remote MediaSources
    // register themselves when the user configures one (see App.kt's
    // launch-time SubsonicSource registration).
    single { MediaSourceRegistry() }

    // Subsonic-protocol MediaSource over the platform SubsonicClient.
    // Reads server credentials live from SettingsStore on every walk, so
    // a single instance bound here always reflects the current config.
    // The network gate is a lambda over NetworkPolicyGate.allowAutomaticFetch()
    // — same seam ScrobbleSync uses. NOT registered into the
    // MediaSourceRegistry from here; App.kt registers it at launch when a
    // server is configured and re-registers after a successful save.
    single {
        SubsonicSource(
            client = get(),
            settings = get(),
            allowNetwork = { get<NetworkPolicyGate>().allowAutomaticFetch() },
        )
    }
    single { SourceRuleEvaluator(get(), get(), get(), get()) }
    single {
        com.visorcraft.radioshed.stations.SmartQueryRefresh(
            settings = get(),
            stationRepo = get(),
            evaluate = { get<SourceRuleEvaluator>().evaluate(it) },
        )
    }
    // Phase DJ: announcer orchestrates TTS announcements between tracks.
    // Constructor order: (settings, djCache, tts, clipPlayer, audioEngine,
    // sleepTimer, weather). TtsEngine + ClipPlayer + WeatherFetcher come from
    // platformModule (Null on desktop/iOS). SleepTimer lets the DJ stand down
    // during a sleep fade so the two volume drivers don't fight (registered above).
    single { DjAnnouncer(get(), get(), get(), get(), get(), get(), get()) }
    single { StationPlayback(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { PresetSeeder(get(), get()) }

    // Auto Drive Mode: activates Drive Mode from BluetoothWatcher matches +
    // schedule windows. Given a dedicated app-lifetime scope (SupervisorJob so
    // a collector failure can't tear down the whole controller; crashGuard so
    // an uncaught exception logs instead of popping the OS "stopped" dialog).
    // BluetoothWatcher is platform-bound (real on Android, Null elsewhere).
    // App.kt calls start() once at launch.
    single {
        DriveModeController(
            watcher = get(),
            settings = get(),
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() +
                    kotlinx.coroutines.Dispatchers.Default +
                    com.visorcraft.radioshed.util.crashGuard("DriveModeController"),
            ),
        )
    }
}

/** Convenience entry-point used by both platforms. */
fun appModules(): List<Module> = listOf(platformModule, sharedModule)
