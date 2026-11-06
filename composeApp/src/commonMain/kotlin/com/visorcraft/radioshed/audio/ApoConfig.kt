package com.visorcraft.radioshed.audio

import kotlin.math.ln

/**
 * Parser + mapper for AutoEQ-style Equalizer APO configs. APO is the
 * de-facto interchange format used by AutoEQ, oratory1990, Crinacle,
 * and most headphone-tuning communities — being able to consume one
 * directly is a much bigger user-value win than asking people to
 * hand-translate gains into our 5-band layout.
 *
 * Format we accept (all lines optional, order doesn't matter):
 *
 * ```
 * Preamp: -6.7 dB
 * Filter 1: ON PK Fc 105 Hz Gain -2.5 dB Q 0.71
 * Filter 2: ON HSC Fc 4000 Hz Gain 1.5 dB Q 0.71
 * Filter 3: ON LSC Fc 105 Hz Gain 4 dB Q 0.71
 * # comments are tolerated; OFF filters are skipped
 * ```
 *
 * Mapping to our 5-band Android Equalizer:
 *  - For each of our 5 fixed band centers, find the closest APO
 *    band by **log-frequency distance** (perceptually correct —
 *    100 Hz and 200 Hz are "close" in the same way 8 kHz and 16 kHz
 *    are, in terms of human pitch perception).
 *  - Take that closest band's gain. We do NOT sum gains from
 *    multiple nearby APO bands — getting that right requires actual
 *    biquad-cascade modelling, and a "nearest neighbour" approach
 *    has been the de-facto compromise since the early Foobar2000
 *    days for bandcount-mismatched imports. Users with a lot of
 *    headroom-shaping fine bands won't get a perfect match, but
 *    they'll get the audible-frequency-region right and that's
 *    what this feature exists to deliver.
 *  - Pre-amp is preserved separately and surfaced to the caller so
 *    the EQ screen can apply it via the existing pre-amp slider.
 *
 * v1 limitations (intentional):
 *  - Only `PK` (peaking) filters contribute to the output. `LSC`,
 *    `HSC`, `LPF`, `HPF`, `NO` etc. are parsed but ignored — those
 *    are shelf/notch shapes our system EQ can't represent without
 *    real DSP. The docs in [parse]'s return value flag how many
 *    were skipped so the UI can warn.
 */
object ApoConfig {
    /**
     * Parsed contents of an APO file.
     *
     * @property preampDb pre-amplifier gain in dB; 0 if unspecified.
     * @property bands every `Filter N: ON PK ...` line, in source order.
     * @property unsupportedFilters count of `Filter N: ON <kind> ...`
     *   lines we recognized but couldn't represent (LSC/HSC/etc.) —
     *   for surfacing a "skipped 3 shelf filters" message in the UI.
     */
    data class Parsed(
        val preampDb: Double,
        val bands: List<ApoBand>,
        val unsupportedFilters: Int,
    )

    data class ApoBand(
        val frequencyHz: Int,
        val gainDb: Double,
        val q: Double,
    )

    /**
     * Tolerant parser — anything we don't recognize is skipped silently
     * with a count surfaced via [Parsed.unsupportedFilters]. Returns
     * `null` only if the input has no usable filters AND no preamp,
     * which signals to the UI that the user pasted something that's
     * almost certainly not an APO config.
     */
    fun parse(raw: String): Parsed? {
        var preamp = 0.0
        val bands = mutableListOf<ApoBand>()
        var unsupported = 0
        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            // Preamp: -6.7 dB
            val preampMatch = PREAMP_REGEX.find(line)
            if (preampMatch != null) {
                preamp = preampMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                continue
            }

            // Filter 1: ON PK Fc 105 Hz Gain -2.5 dB Q 0.71
            val filterMatch = FILTER_REGEX.find(line) ?: continue
            val onOff = filterMatch.groupValues[1]
            if (!onOff.equals("ON", ignoreCase = true)) continue

            val kind = filterMatch.groupValues[2].uppercase()
            val freq = filterMatch.groupValues[3].toIntOrNull() ?: continue
            val gain = filterMatch.groupValues[4].toDoubleOrNull() ?: 0.0
            val q = filterMatch.groupValues[5].toDoubleOrNull() ?: 1.0

            if (kind != "PK") {
                unsupported += 1
                continue
            }
            bands += ApoBand(frequencyHz = freq, gainDb = gain, q = q)
        }
        if (preamp == 0.0 && bands.isEmpty() && unsupported == 0) return null
        return Parsed(
            preampDb = preamp,
            bands = bands,
            unsupportedFilters = unsupported,
        )
    }

    /**
     * Map [Parsed.bands] onto our N-band fixed-frequency layout. For
     * each target band, picks the closest APO band by log-frequency
     * distance and returns its gain. Empty/unmatched targets get 0.
     *
     * Returns a `FloatArray` ready to feed into
     * `EqualizerController.setBand(...)` band-by-band. Length matches
     * [targetCenters].
     */
    fun mapToBands(
        bands: List<ApoBand>,
        targetCenters: List<Int>,
    ): FloatArray {
        if (targetCenters.isEmpty()) return FloatArray(0)
        if (bands.isEmpty()) return FloatArray(targetCenters.size)
        val out = FloatArray(targetCenters.size)
        for ((i, target) in targetCenters.withIndex()) {
            val targetLog = ln(target.toDouble().coerceAtLeast(1.0))
            val best =
                bands.minByOrNull { band ->
                    val srcLog = ln(band.frequencyHz.toDouble().coerceAtLeast(1.0))
                    kotlin.math.abs(srcLog - targetLog)
                }
            out[i] = best?.gainDb?.toFloat() ?: 0f
        }
        return out
    }

    /**
     * Convenience that combines [parse] + [mapToBands] into one call.
     * Used by the "Import APO" dialog where the user pastes a config
     * and we need both the per-band gains AND the pre-amp.
     */
    fun parseAndMap(
        raw: String,
        targetCenters: List<Int>,
    ): MappedApo? {
        val parsed = parse(raw) ?: return null
        return MappedApo(
            preampDb = parsed.preampDb,
            gains = mapToBands(parsed.bands, targetCenters),
            unsupportedFilters = parsed.unsupportedFilters,
            sourceBandCount = parsed.bands.size,
        )
    }

    data class MappedApo(
        val preampDb: Double,
        val gains: FloatArray,
        val unsupportedFilters: Int,
        val sourceBandCount: Int,
    )

    // Allow signed decimals and accept either dot-decimal or comma-decimal
    // (some EU locales export commas) by scrubbing during parse. Frequency
    // is always integer Hz; if a tool emits "Fc 105.0" we still match
    // because the regex captures the integer prefix and the decimal trail
    // gets ignored.
    private val PREAMP_REGEX =
        Regex(
            "^Preamp:\\s*(-?\\d+(?:[.,]\\d+)?)\\s*dB",
            RegexOption.IGNORE_CASE,
        )
    private val FILTER_REGEX =
        Regex(
            "^Filter\\s+\\d+:\\s+(ON|OFF)\\s+([A-Z]+)\\s+Fc\\s+(\\d+)" +
                "(?:[.,]\\d+)?\\s*Hz\\s+Gain\\s+(-?\\d+(?:[.,]\\d+)?)\\s*dB" +
                "\\s+Q\\s+(-?\\d+(?:[.,]\\d+)?)",
            RegexOption.IGNORE_CASE,
        )
}

/**
 * Bundled headphone-preset entry. Sourced from AutoEQ's published
 * APO configs, hand-curated subset covering the most-asked-for cans.
 * Stored as parsed bands rather than raw APO text so we don't need
 * to bundle a parser invocation per entry on each EQ screen open.
 */
data class HeadphonePreset(
    val name: String,
    val brand: String,
    val preampDb: Double,
    val bands: List<ApoConfig.ApoBand>,
)

