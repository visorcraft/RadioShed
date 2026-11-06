package com.visorcraft.radioshed.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FtsMatchQueryTest {
    @Test
    fun blankQueryReturnsNull() {
        assertNull(buildFtsMatchQuery(""))
        assertNull(buildFtsMatchQuery("   "))
    }

    @Test
    fun quotesSpecialCharactersForPrefixMatch() {
        assertEquals("\"AC/DC\"*", quoteFtsPrefixToken("AC/DC"))
        assertEquals("\"C++\"*", quoteFtsPrefixToken("C++"))
        assertEquals("\"foo-bar\"*", quoteFtsPrefixToken("foo-bar"))
        assertEquals("\"don't\"*", quoteFtsPrefixToken("don't"))
    }

    @Test
    fun multiTokenQueryJoinsQuotedPrefixes() {
        assertEquals("\"AC/DC\"* \"live\"*", buildFtsMatchQuery("AC/DC live"))
    }

    @Test
    fun escapesEmbeddedDoubleQuotes() {
        assertEquals("\"say \"\"hi\"\"\"*", quoteFtsPrefixToken("say \"hi\""))
    }
}
