package com.visorcraft.radioshed.data

/**
 * Lightweight DSL for the Search bar. Supports field-scoped queries
 * (`artist:Hendrix`, `genre:rock`, `year:>1968`) interleaved with plain
 * free text. Implicit AND between terms.
 *
 * Supported fields:
 *  - `title:<text>`     substring on track title
 *  - `artist:<text>`    substring on primary artist name
 *  - `album:<text>`     substring on album title
 *  - `genre:<text>`     exact-or-substring match against the GENRE tag
 *  - `format:<text>`    exact match against the audio format (mp3, flac, …)
 *  - `year:<expr>`      year filter, where expr is one of:
 *      `1968`           exact
 *      `>1968`, `>=1968` greater-than / greater-or-equal
 *      `<1970`, `<=1970` less-than / less-or-equal
 *      `1968-1970`      inclusive range
 *
 * Plain free-text tokens (no `field:` prefix) are matched against the
 * union of title/artist/album, same as the legacy non-DSL behaviour, so
 * a query without any colons behaves identically to before. Quoted
 * values (`"king crimson"`) preserve their internal whitespace.
 *
 * Designed deliberately small: no OR, no NOT, no parens. We can extend
 * later if users actually want them; for now keeping the surface
 * predictable and the parser stupid-simple.
 */

sealed interface SearchTerm {
    data class Free(val text: String) : SearchTerm
    data class Field(val key: String, val value: String) : SearchTerm
    data class YearRange(val min: Int?, val max: Int?) : SearchTerm
}

data class ParsedSearchQuery(
    val terms: List<SearchTerm>,
) {
    val hasFieldTerms: Boolean = terms.any { it is SearchTerm.Field || it is SearchTerm.YearRange }
    val freeText: String = terms
        .filterIsInstance<SearchTerm.Free>()
        .joinToString(" ") { it.text }
        .trim()
}

object SearchDsl {

    private val supportedFields = setOf(
        "title", "artist", "album", "genre", "format", "year",
    )

    fun parse(input: String): ParsedSearchQuery {
        val tokens = tokenize(input)
        val terms = mutableListOf<SearchTerm>()
        for (raw in tokens) {
            val colon = raw.indexOf(':')
            if (colon <= 0 || colon == raw.length - 1) {
                terms += SearchTerm.Free(raw)
                continue
            }
            val key = raw.substring(0, colon).lowercase()
            val value = raw.substring(colon + 1)
            if (key !in supportedFields) {
                // Unknown field — treat the whole token as free text so
                // typos still find something instead of silently dropping.
                terms += SearchTerm.Free(raw)
                continue
            }
            if (key == "year") {
                parseYearTerm(value)?.let { terms += it } ?: run {
                    terms += SearchTerm.Free(raw)
                }
            } else {
                terms += SearchTerm.Field(key, stripQuotes(value))
            }
        }
        return ParsedSearchQuery(terms)
    }

    /**
     * Tokenize honouring double-quoted runs so `artist:"king crimson"`
     * stays a single token. Escaping isn't supported — quotes inside
     * quotes terminate the run, same as a shell-y minimal split.
     */
    private fun tokenize(input: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in input) {
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    sb.append(ch)
                }
                ch.isWhitespace() && !inQuotes -> {
                    if (sb.isNotEmpty()) {
                        out += sb.toString()
                        sb.clear()
                    }
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun stripQuotes(value: String): String =
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }

    private fun parseYearTerm(raw: String): SearchTerm? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        // Operator-only inputs like `>`, `<`, `>=`, `<=` carry user
        // intent ("filter on year") but no operand. Returning null
        // would let the caller fall back to free text and the user's
        // `year:>` query would match every track. Return an
        // impossible-range YearRange instead so the SQL builder
        // produces "no match", which is what the user expected.
        val isOperatorOnly = v == ">" || v == "<" || v == ">=" || v == "<=" ||
            v == "-" || v.startsWith(">") && v.removePrefix(">=").removePrefix(">").trim().isEmpty() ||
            v.startsWith("<") && v.removePrefix("<=").removePrefix("<").trim().isEmpty()
        if (isOperatorOnly) {
            return SearchTerm.YearRange(min = Int.MAX_VALUE, max = Int.MIN_VALUE)
        }
        // Range: 1968-1970
        val dash = v.indexOf('-', startIndex = 1)
        if (dash > 0 && dash < v.length - 1 && !v.startsWith(">") && !v.startsWith("<")) {
            val a = v.substring(0, dash).toIntOrNull() ?: return null
            val b = v.substring(dash + 1).toIntOrNull() ?: return null
            return SearchTerm.YearRange(min = minOf(a, b), max = maxOf(a, b))
        }
        return when {
            v.startsWith(">=") -> v.removePrefix(">=").toIntOrNull()?.let {
                SearchTerm.YearRange(min = it, max = null)
            }
            v.startsWith("<=") -> v.removePrefix("<=").toIntOrNull()?.let {
                SearchTerm.YearRange(min = null, max = it)
            }
            v.startsWith(">") -> v.removePrefix(">").toIntOrNull()?.let {
                SearchTerm.YearRange(min = it + 1, max = null)
            }
            v.startsWith("<") -> v.removePrefix("<").toIntOrNull()?.let {
                SearchTerm.YearRange(min = null, max = it - 1)
            }
            else -> v.toIntOrNull()?.let {
                SearchTerm.YearRange(min = it, max = it)
            }
        }
    }
}
