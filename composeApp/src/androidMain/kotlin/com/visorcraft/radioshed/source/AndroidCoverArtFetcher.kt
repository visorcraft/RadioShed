package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/**
 * Cover Art Archive client. Uses [HttpURLConnection] from the JDK rather
 * than dragging in a full HTTP-client dependency — the surface area we
 * need is "GET, follow one redirect, read bytes," which the JDK does
 * fine. Adding Ktor or OkHttp would inflate the APK by ~500KB for one
 * GET request.
 *
 * Why we follow redirects manually:
 *  CAA's `/release/{mbid}/front-500` returns a `307 Temporary Redirect`
 *  to an `archive.org` mirror URL. [HttpURLConnection.setInstanceFollowRedirects]
 *  WILL follow that — but only within the same protocol scheme; CAA is
 *  https and archive.org is also https, so the default suffices. We
 *  still explicitly enable it to be defensive.
 *
 * User-Agent: per CAA's terms of service, identifying the client is
 * required. Anonymous requests sometimes get rate-limited; identifying
 * ourselves keeps us in the well-behaved bucket.
 */
class AndroidCoverArtFetcher : CoverArtFetcher {

    override suspend fun fetchByReleaseMbid(mbid: String): ByteArray? = withContext(IODispatcher) {
        if (mbid.isBlank()) return@withContext null
        val url = URI("$CAA_BASE/release/$mbid/front-$SIZE_PX").toURL()
        runCatching {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "image/*")
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                conn.inputStream.use { it.readBytes() }
                    .takeIf { it.isNotEmpty() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private companion object {
        const val CAA_BASE = "https://coverartarchive.org"
        const val SIZE_PX = 500
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        // CAA asks clients to identify themselves; "AppName/Version (contact)"
        // is the canonical pattern. We don't expose contact info in v1 —
        // app name + version is sufficient for ToS compliance.
        const val USER_AGENT = "RadioShed/1.0 (https://radioshed.app)"
    }
}
