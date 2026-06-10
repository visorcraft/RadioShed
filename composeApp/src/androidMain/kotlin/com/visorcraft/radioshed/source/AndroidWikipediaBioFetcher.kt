package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Wikipedia REST summary client. Fetches the lead-paragraph extract
 * for a given page title.
 *
 * Page-title encoding rules per the REST API docs:
 *  - Spaces become underscores in the URL path. Wikipedia accepts
 *    URL-encoded spaces too, but the underscore form is what
 *    redirects use internally and gives the cleanest cache hits.
 *  - Special characters are still URL-encoded (`%26` for `&`, etc.).
 *
 * Disambiguation handling: when the page is a disambiguation hub
 * (`type == "disambiguation"`), we return null. The user almost
 * certainly didn't want "list of bands named X" — they wanted the
 * specific artist, and the right move is to surface a dropdown of
 * candidates instead. That UX is deferred; for now, no result is
 * better than the wrong result.
 */
class AndroidWikipediaBioFetcher : WikipediaBioFetcher {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchSummary(title: String): WikipediaSummary? =
        withContext(IODispatcher) {
            if (title.isBlank()) return@withContext null
            val pageTitle = title.trim().replace(' ', '_')
            // urlEncode handles every special, then we restore the
            // underscore-spaces (urlEncode would percent-encode the
            // underscore back to %5F otherwise — fine for the server,
            // but breaks the readable-URL invariant the API likes).
            val encoded = URLEncoder.encode(pageTitle, "UTF-8")
                .replace("+", "%20")
                .replace("%5F", "_")
            val url = URI("$WP_BASE/api/rest_v1/page/summary/$encoded").toURL()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) return@withContext null
                val body = conn.inputStream.use { it.readBytes() }.decodeToString()
                val parsed = runCatching {
                    json.decodeFromString(WpSummary.serializer(), body)
                }.getOrNull() ?: return@withContext null
                if (parsed.type == "disambiguation") return@withContext null
                val extract = parsed.extract?.takeIf { it.isNotBlank() }
                    ?: return@withContext null
                WikipediaSummary(
                    title = parsed.title ?: title,
                    extract = extract,
                    pageUrl = parsed.contentUrls?.desktop?.page
                        ?: "https://en.wikipedia.org/wiki/$encoded",
                )
            } catch (e: java.io.IOException) {
                null
            } finally {
                conn.disconnect()
            }
        }

    @Serializable
    private data class WpSummary(
        val type: String? = null,
        val title: String? = null,
        val extract: String? = null,
        @kotlinx.serialization.SerialName("content_urls")
        val contentUrls: ContentUrls? = null,
    )

    @Serializable
    private data class ContentUrls(val desktop: PageUrl? = null)

    @Serializable
    private data class PageUrl(val page: String? = null)

    private companion object {
        const val WP_BASE = "https://en.wikipedia.org"
        const val USER_AGENT =
            "RadioShed/0.1 (https://radioshed.app)"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
    }
}
