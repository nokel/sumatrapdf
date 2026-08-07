package com.sumatrapdf.reader.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SeriesLookupTest {

    // yearMatches: the user's "1995 book vs 2005 rewrite" filter.
    @Test
    fun yearMatches_nullBookYear_doesNotFilter() {
        assertTrue(yearMatches(null, 1995))
        assertTrue(yearMatches(null, null))
        assertTrue(yearMatches(null, 2005))
    }

    @Test
    fun yearMatches_nullHitYear_doesNotFilter() {
        assertTrue(yearMatches(1995, null))
        assertTrue(yearMatches(2005, null))
    }

    @Test
    fun yearMatches_sameYear_passes() {
        assertTrue(yearMatches(1995, 1995))
        assertTrue(yearMatches(2000, 2000))
    }

    @Test
    fun yearMatches_withinTolerance_passes() {
        // Reprints can be a year off (1995 paperback, 1996 hardcover)
        assertTrue(yearMatches(1995, 1996))
        assertTrue(yearMatches(1996, 1995))
    }

    @Test
    fun yearMatches_outsideTolerance_fails() {
        // The textbook case the user is calling out: a 1995 book
        // matched against a 2005 rewrite must be rejected.
        assertFalse(yearMatches(1995, 2005))
        assertFalse(yearMatches(2005, 1995))
        assertFalse(yearMatches(1990, 2000))
    }

    // normaliseSeriesName: "Animorphs" and "Animorphs (10)" must
    // hash to the same bucket so the groupBy() in seriesFor() can
    // correlate across sources.
    @Test
    fun normaliseSeriesName_stripsVolume() {
        assertEquals("animorphs", normaliseSeriesName("Animorphs"))
        assertEquals("animorphs", normaliseSeriesName("Animorphs (10)"))
        assertEquals("animorphs", normaliseSeriesName("Animorphs #11"))
        assertEquals("animorphs", normaliseSeriesName("Animorphs, Vol. 3"))
        assertEquals("onimai", normaliseSeriesName("Onimai"))
    }

    @Test
    fun normaliseSeriesName_isCaseInsensitive() {
        assertEquals("animorphs", normaliseSeriesName("ANIMORPHS"))
        assertEquals("animorphs", normaliseSeriesName("Animorphs"))
    }

    @Test
    fun normaliseSeriesName_stripsTrailingPunctuation() {
        assertEquals("animorphs", normaliseSeriesName("Animorphs:"))
        assertEquals("animorphs", normaliseSeriesName("Animorphs  "))
    }

    // The pipeline: collect, filter, correlate. We test seriesFor()'s
    // correlation contract via a small fake: given a list of SourceHits
    // and a book year, which one survives the year filter and wins the
    // correlation?
    @Test
    fun correlate_prefersSourceWithMostVotes() {
        val hits = listOf(
            SourceHit("wikidata", "Animorphs", 1996, weight = 1.0),
            SourceHit("openlibrary", "Animorphs", 1996, weight = 0.9),
            SourceHit("worldcat", "Animorphs", 1997, weight = 0.9),
        )
        val grouped = hits.filter { yearMatches(1996, it.year) }
            .groupBy { normaliseSeriesName(it.name) }
        val winner = grouped.maxByOrNull { it.value.sumOf { h -> h.weight } }!!
        assertEquals("animorphs", winner.key)
        assertEquals(3, winner.value.size)
    }

    @Test
    fun correlate_yearMismatchExcludesHit() {
        val hits = listOf(
            SourceHit("wikidata", "Animorphs", 1996, weight = 1.0),
            // 2005 rewrite — wrong match for a 1995 book
            SourceHit("openlibrary", "Animorphs", 2005, weight = 0.9),
        )
        val compatible = hits.filter { yearMatches(1995, it.year) }
        assertEquals(1, compatible.size)
        assertEquals("wikidata", compatible[0].source)
    }

    @Test
    fun correlate_normalisesVolumeSuffix() {
        // "Animorphs" and "Animorphs (10)" must group together so
        // two sources don't disagree over a volume tag.
        val hits = listOf(
            SourceHit("wikidata", "Animorphs", 1996, weight = 1.0),
            SourceHit("openlibrary", "Animorphs (10)", 1996, weight = 0.9),
        )
        val grouped = hits.groupBy { normaliseSeriesName(it.name) }
        assertEquals(1, grouped.size)
        assertTrue(grouped.containsKey("animorphs"))
    }

    @Test
    fun earliestYear_picksTheFirstRelease() {
        // Series omnibus volumes can carry a 1995 cover but a 2005
        // latest-issue date. The earliest year is the one the user
        // wants on the Info tab (matches their copy).
        val hits = listOf(
            SourceHit("a", "Animorphs", 2005, weight = 1.0),
            SourceHit("b", "Animorphs", 1995, weight = 0.9),
            SourceHit("c", "Animorphs", 1998, weight = 0.8),
        )
        assertEquals(1995, earliestYear(hits))
    }

    @Test
    fun earliestYear_nullsIgnored() {
        val hits = listOf(
            SourceHit("a", "Animorphs", 2005, weight = 1.0),
            SourceHit("b", "Animorphs", year = null, weight = 0.9),
            SourceHit("c", "Animorphs", 1998, weight = 0.8),
        )
        assertEquals(1998, earliestYear(hits))
    }

    @Test
    fun earliestYear_allNulls() {
        val hits = listOf(
            SourceHit("a", "Animorphs", year = null, weight = 1.0),
            SourceHit("b", "Animorphs", year = null, weight = 0.9),
        )
        assertNull(earliestYear(hits))
    }

    // WIKIDATA_YEAR regex: pulls the year out of a Wikibase time value.
    @Test
    fun wikidataYear_parsesTimeString() {
        val m = WIKIDATA_YEAR.find("+1996-00-00T00:00:00Z")
        assertNotNull(m)
        assertEquals("1996", m!!.groupValues[1])
    }

    @Test
    fun wikidataYear_parsesPlainYear() {
        val m = WIKIDATA_YEAR.find("1996")
        assertNotNull(m)
        assertEquals("1996", m!!.groupValues[1])
    }

    @Test
    fun wikidataYear_acceptsNegative() {
        val m = WIKIDATA_YEAR.find("-1995-00-00T00:00:00Z")
        assertNotNull(m)
        assertEquals("1995", m!!.groupValues[1])
    }
}
