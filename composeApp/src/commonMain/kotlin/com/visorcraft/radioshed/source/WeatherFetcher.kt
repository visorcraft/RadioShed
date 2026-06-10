package com.visorcraft.radioshed.source

import kotlin.math.roundToInt

/**
 * Best-effort current-weather snapshot for DJ announcements. Purely
 * decorative: the DJ template's `{weather}` token is filled from this when
 * available, and the sentence is dropped when it isn't (see [WeatherFetcher]).
 */
data class WeatherSnapshot(val tempC: Double, val condition: String)

/**
 * Fetches a current-weather snapshot for the device's coarse location, used
 * only to fill the `{weather}` token in DJ announcement copy.
 *
 * Returns null whenever a snapshot can't be produced — no location permission,
 * no last-known fix, the network policy denying the fetch, or any error. The
 * contract is best-effort and MUST NOT throw: the DJ is decorative, so a
 * failure simply drops the weather sentence rather than breaking playback.
 */
interface WeatherFetcher {
    /** Null when unavailable (no location, network denied, error). Never throws. */
    suspend fun current(): WeatherSnapshot?
}

/** Stub used on platforms without a weather client (desktop, iOS). */
class NullWeatherFetcher : WeatherFetcher {
    override suspend fun current(): WeatherSnapshot? = null
}

/**
 * Maps an Open-Meteo WMO weather-interpretation code to a short, human
 * condition string suitable for spoken DJ copy. Ranges follow the WMO code
 * table Open-Meteo documents for `current_weather.weathercode`. Anything
 * outside the known buckets falls back to "unsettled".
 */
internal fun describeWmoCode(code: Int): String = when (code) {
    0 -> "clear" // clear sky
    in 1..3 -> "partly cloudy" // mainly clear / partly cloudy / overcast
    45, 48 -> "foggy" // fog and depositing rime fog
    in 51..67 -> "rainy" // drizzle + rain (incl. freezing) and rain showers' lower band
    in 71..86 -> "snowy" // snow fall, snow grains, and snow showers
    in 95..99 -> "stormy" // thunderstorm, with or without hail
    else -> "unsettled"
}

/**
 * Formats a [WeatherSnapshot] into DJ copy like "12 degrees and clear". The
 * temperature is rounded to the nearest whole degree (Celsius) so spoken copy
 * stays terse.
 */
internal fun formatWeather(snapshot: WeatherSnapshot): String =
    "${snapshot.tempC.roundToInt()} degrees and ${snapshot.condition}"
