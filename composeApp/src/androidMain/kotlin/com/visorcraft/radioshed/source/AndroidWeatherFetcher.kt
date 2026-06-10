package com.visorcraft.radioshed.source

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI

/**
 * Open-Meteo-backed [WeatherFetcher] for the `{weather}` DJ token. Hits the
 * public, no-key endpoint:
 *
 *   GET https://api.open-meteo.com/v1/forecast
 *       ?latitude=<lat>&longitude=<lon>&current_weather=true
 *
 * Same JDK-only HTTP rationale as [AndroidLyricsFetcher] / [AndroidCoverArtFetcher]:
 * one GET, not worth dragging in Ktor/OkHttp.
 *
 * Location is a coarse last-known fix read straight off [LocationManager] —
 * only when ACCESS_COARSE_LOCATION is *already* granted. This class NEVER
 * requests the permission (granting stays user-initiated via system settings
 * or a future task) and NEVER throws: any missing piece (no permission, no
 * fix, network denied, parse error) returns null so the DJ simply drops the
 * weather sentence.
 *
 * Results are cached in-memory for [CACHE_TTL_MS] so a DJ session — which may
 * render many announcements — doesn't hammer the API.
 */
class AndroidWeatherFetcher(
    private val context: Context,
    private val networkGate: NetworkPolicyGate,
) : WeatherFetcher {

    private val json = Json { ignoreUnknownKeys = true }

    // 15-minute in-memory cache. A single mutex guards both reads (TTL check)
    // and the write after a successful fetch so two overlapping announcements
    // can't both fire a request for the same window.
    private val cacheMutex = Mutex()
    private var cached: WeatherSnapshot? = null
    private var cachedAtMs: Long = 0L

    override suspend fun current(): WeatherSnapshot? = withContext(IODispatcher) {
        runCatching {
            cacheMutex.withLock {
                val now = System.currentTimeMillis()
                cached?.let { if (now - cachedAtMs < CACHE_TTL_MS) return@withLock it }

                // Honour the user's connectivity preference before touching the
                // network — same gate every other automatic fetcher consults.
                if (!networkGate.allowAutomaticFetch()) return@withLock null

                val location = lastKnownCoarseLocation() ?: return@withLock null
                val snapshot = fetchSnapshot(location.first, location.second) ?: return@withLock null
                cached = snapshot
                cachedAtMs = now
                snapshot
            }
        }.getOrNull()
    }

    /**
     * Coarse last-known position as (latitude, longitude), or null. Reads only
     * if ACCESS_COARSE_LOCATION is already granted — we never prompt. Tries the
     * network provider first (the coarse, low-power fix), then GPS as a
     * fallback for devices/sessions where only GPS has a cached fix.
     */
    private fun lastKnownCoarseLocation(): Pair<Double, Double>? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        // Guard the check-then-use race: permission can be revoked between our
        // checkSelfPermission call above and getLastKnownLocation.
        val fix = try {
            manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (e: SecurityException) {
            return null
        } ?: return null
        return fix.latitude to fix.longitude
    }

    private fun fetchSnapshot(latitude: Double, longitude: Double): WeatherSnapshot? = runCatching {
        val url = URI(
            "$OPEN_METEO_BASE/v1/forecast" +
                "?latitude=$latitude&longitude=$longitude&current_weather=true",
        ).toURL()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val body = conn.inputStream.use { it.readBytes() }.decodeToString()
            val parsed = json.decodeFromString(ForecastResponse.serializer(), body)
            val current = parsed.currentWeather ?: return@runCatching null
            WeatherSnapshot(
                tempC = current.temperature,
                condition = describeWmoCode(current.weathercode),
            )
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    @Serializable
    private data class ForecastResponse(
        @kotlinx.serialization.SerialName("current_weather")
        val currentWeather: CurrentWeather? = null,
    )

    @Serializable
    private data class CurrentWeather(
        val temperature: Double,
        val weathercode: Int,
    )

    private companion object {
        const val OPEN_METEO_BASE = "https://api.open-meteo.com"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val CACHE_TTL_MS = 15L * 60 * 1000 // 15 minutes
    }
}
