package com.visorcraft.radioshed.di

/**
 * Called from Swift at app launch — see iosApp/iOSApp.swift.
 * Swift cannot pass a KoinAppDeclaration lambda directly, so we expose a
 * no-arg helper that just sets up the modules.
 */
fun doInitKoin() {
    initKoin()
}
