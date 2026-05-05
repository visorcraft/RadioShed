package com.visorcraft.radioshed.source

/**
 * Cross-platform interface for the lead-paragraph artist bio
 * lookup. Hits Wikipedia's REST summary endpoint, which returns a
 * pre-extracted lead paragraph (no HTML, no template clutter) — the
 * shape we want for an in-app "About this artist" panel.
 *
 * Endpoint contract (Android impl):
 *   GET https://en.wikipedia.org/api/rest_v1/page/summary/<title>
 *
 * No API key, no authentication. Wikipedia asks for a User-Agent
 * that identifies the application and provides a contact channel —
 * we comply.
 */
interface WikipediaBioFetcher {
    /**
     * Fetch the lead-paragraph extract for [title] (typically the
     * artist's name). Returns null on miss / network failure /
     * disambiguation page (the summary endpoint surfaces those as a
     * specific `type` we treat as "no clean answer").
     */
    suspend fun fetchSummary(title: String): WikipediaSummary?
}

data class WikipediaSummary(
    /** Canonical page title (may differ from the queried [title] after redirect). */
    val title: String,
    /** Plain-text lead paragraph; never empty when this object is returned. */
    val extract: String,
    /** Canonical desktop URL — useful for an "Open on Wikipedia" link. */
    val pageUrl: String,
)

/** Stub used on platforms with no HTTP wiring (iOS today). */
class NullWikipediaBioFetcher : WikipediaBioFetcher {
    override suspend fun fetchSummary(title: String): WikipediaSummary? = null
}
