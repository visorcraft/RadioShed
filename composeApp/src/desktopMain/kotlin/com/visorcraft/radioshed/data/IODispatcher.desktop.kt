package com.visorcraft.radioshed.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Desktop has the same JVM IO dispatcher as Android — a thread pool
 * sized for blocking work. SQLDelight's JdbcSqliteDriver, jaudiotagger
 * tag reads, and `Files.walk` traversal all funnel through this.
 */
internal actual val IODispatcher: CoroutineDispatcher = Dispatchers.IO
