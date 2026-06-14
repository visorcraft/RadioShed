package com.visorcraft.radioshed.di

import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration

/**
 * Cross-platform Koin entry point. Android calls this from its Application
 * subclass with `androidContext` configured; iOS calls it from a small
 * Kotlin helper invoked by Swift on app launch.
 */
fun initKoin(appDeclaration: KoinAppDeclaration = {}): KoinApplication =
    startKoin {
        appDeclaration()
        modules(appModules())
    }
