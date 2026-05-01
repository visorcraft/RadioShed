package com.visorcraft.radioshed.di

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import com.visorcraft.radioshed.data.DatabaseDriverFactory
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
}

private const val PREFS_NAME = "radioshed.prefs"
