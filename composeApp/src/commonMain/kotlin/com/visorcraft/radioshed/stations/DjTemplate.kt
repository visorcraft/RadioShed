package com.visorcraft.radioshed.stations

import kotlin.random.Random

/**
 * Holds the runtime values that may be substituted into a DJ template string.
 * Any field set to null causes every sentence that references it to be dropped
 * from the rendered output rather than producing a literal "null".
 */
data class DjTemplateVars(
    val station: String?,
    val track: String?,
    val artist: String?,
    val nextTrack: String?,
    val time: String?,
    val weather: String?,
)

/**
 * Lightweight template engine for on-air DJ copy.
 *
 * Templates are plain strings that may contain placeholder tokens in curly
 * braces, e.g. `"Now on {station}: {track} by {artist}."`.
 *
 * Supported tokens: `{station}`, `{track}`, `{artist}`, `{next_track}`,
 * `{time}`, `{weather}`.
 *
 * Sentences (split on `.`, `!`, `?` — terminator kept with its sentence) that
 * contain a **known** token whose value is null are dropped entirely. Unknown
 * tokens (e.g. `{nope}`) are left verbatim. A template with no terminators is
 * treated as a single sentence.
 */
object DjTemplate {

    /** All known placeholder names mapped to their [DjTemplateVars] accessor. */
    private val KNOWN_VARS = mapOf(
        "station" to DjTemplateVars::station,
        "track" to DjTemplateVars::track,
        "artist" to DjTemplateVars::artist,
        "next_track" to DjTemplateVars::nextTrack,
        "time" to DjTemplateVars::time,
        "weather" to DjTemplateVars::weather,
    )

    private val SENTENCE_SPLIT_REGEX = Regex("""(?<=[.!?])\s+""")
    private val TOKEN_REGEX = Regex("""\{([^}]+)}""")

    /**
     * Renders [template] by substituting known variables from [vars].
     *
     * Sentences whose known-variable tokens resolve to null are omitted.
     * Unknown tokens are left unchanged. Sentences are rejoined with a single
     * space.
     *
     * @param template  The template string, may contain `{token}` placeholders.
     * @param vars      The runtime values to substitute.
     * @return          The rendered string with no unresolved null placeholders.
     */
    fun render(template: String, vars: DjTemplateVars): String {
        val sentences = splitSentences(template)
        val kept = sentences.filter { sentence ->
            // Drop sentence if it contains a known token whose value is null.
            val tokens = TOKEN_REGEX.findAll(sentence).map { it.groupValues[1] }.toList()
            tokens.none { name -> KNOWN_VARS.containsKey(name) && KNOWN_VARS[name]!!.invoke(vars) == null }
        }.map { sentence ->
            // Substitute all known tokens that have non-null values.
            TOKEN_REGEX.replace(sentence) { match ->
                val name = match.groupValues[1]
                val accessor = KNOWN_VARS[name]
                if (accessor != null) accessor.invoke(vars) ?: match.value else match.value
            }
        }
        return kept.joinToString(" ")
    }

    /**
     * Picks a variant from [variants] using a seeded [Random] for
     * deterministic, reproducible selection.
     *
     * If the chosen variant equals [avoid] and there is more than one variant,
     * the next index (wrapping around) is returned instead, ensuring no
     * immediate repeat.
     *
     * @param variants  Non-empty list of template strings to choose from.
     * @param seed      Seed for the random number generator.
     * @param avoid     Optional string to avoid picking (e.g. the last variant
     *                  shown on air).
     * @return          A string from [variants], differing from [avoid] when
     *                  possible.
     */
    fun pickVariant(variants: List<String>, seed: Long, avoid: String? = null): String {
        val rng = Random(seed)
        val index = rng.nextInt(variants.size)
        val pick = variants[index]
        if (pick == avoid && variants.size > 1) {
            val nextIndex = (index + 1) % variants.size
            return variants[nextIndex]
        }
        return pick
    }

    /** Splits a template into sentences, keeping each terminator attached. */
    private fun splitSentences(template: String): List<String> {
        val parts = SENTENCE_SPLIT_REGEX.split(template.trim())
        return if (parts.size == 1) parts else parts.map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/**
 * Default DJ copy templates grouped by slot.
 *
 * Each slot provides 3–4 natural-sounding radio-DJ variants that make use of
 * the known placeholders. Variants containing `{weather}` degrade gracefully:
 * when the weather value is null the weather sentence is dropped, leaving a
 * shorter but still coherent line.
 */
val DEFAULT_DJ_TEMPLATES: Map<String, List<String>> = mapOf(
    "station_id" to listOf(
        "You're locked in to {station}.",
        "This is {station}, your soundtrack for the day.",
        "Stay tuned — you're listening to {station}.",
        "It's {time}, and you're with {station}.",
    ),
    "up_next" to listOf(
        "Coming up: {next_track}.",
        "Up next on {station}: {next_track}.",
        "Don't go anywhere. Outside it's {weather}. Next up is {next_track}.",
        "Stay with us — {next_track} is coming right up.",
    ),
    "recap" to listOf(
        "That was {track} by {artist}.",
        "You just heard {track} — {artist}, right here on {station}.",
        "That was {track} by {artist}. It's {weather} out there, so keep it warm in here.",
        "{artist} with {track}. Classic.",
    ),
    "time" to listOf(
        "It's {time}.",
        "The time is {time} — you're on {station}.",
        "Just gone {time} here on {station}.",
    ),
)
