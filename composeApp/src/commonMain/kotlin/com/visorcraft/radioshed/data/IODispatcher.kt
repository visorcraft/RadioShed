package com.visorcraft.radioshed.data

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Cross-platform IO dispatcher. Resolves to [kotlinx.coroutines.Dispatchers.IO] on Android/JVM
 * (a dedicated thread pool sized for blocking IO), and to [kotlinx.coroutines.Dispatchers.Default]
 * on Native/iOS where IO is not (yet) public API.
 */
internal expect val IODispatcher: CoroutineDispatcher
