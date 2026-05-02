package com.visorcraft.radioshed.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchDslTest {

    @Test
    fun plainTextProducesFreeTerms() {
        val parsed = SearchDsl.parse("hendrix experience")
        assertEquals(2, parsed.terms.size)
        assertTrue(parsed.terms.all { it is SearchTerm.Free })
        assertTrue(!parsed.hasFieldTerms)
    }

    @Test
    fun fieldTermSplitsCorrectly() {
        val parsed = SearchDsl.parse("artist:Hendrix")
        assertEquals(1, parsed.terms.size)
        val term = parsed.terms.single() as SearchTerm.Field
        assertEquals("artist", term.key)
        assertEquals("Hendrix", term.value)
    }

    @Test
    fun quotedValuePreservesWhitespace() {
        val parsed = SearchDsl.parse("artist:\"king crimson\"")
        val term = parsed.terms.single() as SearchTerm.Field
        assertEquals("king crimson", term.value)
    }

    @Test
    fun yearExactProducesEqualMinAndMax() {
        val parsed = SearchDsl.parse("year:1968")
        val term = parsed.terms.single() as SearchTerm.YearRange
        assertEquals(1968, term.min)
        assertEquals(1968, term.max)
    }

    @Test
    fun yearGreaterThanIsExclusiveLowerBound() {
        val parsed = SearchDsl.parse("year:>1968")
        val term = parsed.terms.single() as SearchTerm.YearRange
        assertEquals(1969, term.min)
        assertEquals(null, term.max)
    }

    @Test
    fun yearGreaterEqualIsInclusiveLowerBound() {
        val parsed = SearchDsl.parse("year:>=1968")
        val term = parsed.terms.single() as SearchTerm.YearRange
        assertEquals(1968, term.min)
        assertEquals(null, term.max)
    }

    @Test
    fun yearLessThanIsExclusiveUpperBound() {
        val parsed = SearchDsl.parse("year:<1970")
        val term = parsed.terms.single() as SearchTerm.YearRange
        assertEquals(null, term.min)
        assertEquals(1969, term.max)
    }

    @Test
    fun yearRangeIsInclusive() {
        val parsed = SearchDsl.parse("year:1965-1970")
        val term = parsed.terms.single() as SearchTerm.YearRange
        assertEquals(1965, term.min)
        assertEquals(1970, term.max)
    }

    @Test
    fun mixedFreeAndFieldTokens() {
        val parsed = SearchDsl.parse("hendrix year:>1968 genre:rock")
        assertEquals(3, parsed.terms.size)
        assertTrue(parsed.hasFieldTerms)
        assertEquals("hendrix", parsed.freeText)
    }

    @Test
    fun unknownFieldFallsBackToFreeText() {
        val parsed = SearchDsl.parse("foo:bar")
        assertEquals(1, parsed.terms.size)
        assertTrue(parsed.terms.single() is SearchTerm.Free)
    }

    @Test
    fun trailingColonIsTreatedAsFreeText() {
        val parsed = SearchDsl.parse("artist:")
        assertTrue(parsed.terms.single() is SearchTerm.Free)
    }
}
