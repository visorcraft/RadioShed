package com.visorcraft.radioshed.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val DEFAULT_PAGE_SIZE = 100
internal const val LOAD_AHEAD = 20

/**
 * Simple pagination helper for Compose lists/grids. Holds the already-loaded
 * items, loading flag, and whether more pages exist. Callers provide a
 * suspend [load] lambda that fetches one page given (limit, offset).
 */
class PagedListState<T> {
    var items by mutableStateOf<List<T>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(true)
        private set

    suspend fun loadPage(
        pageSize: Int = DEFAULT_PAGE_SIZE,
        load: suspend (limit: Long, offset: Long) -> List<T>,
    ) {
        if (isLoading || !hasMore) return
        isLoading = true
        try {
            val page = load(pageSize.toLong(), items.size.toLong())
            items = items + page
            hasMore = page.size >= pageSize
        } finally {
            isLoading = false
        }
    }

    fun reset() {
        items = emptyList()
        hasMore = true
        isLoading = false
    }
}
