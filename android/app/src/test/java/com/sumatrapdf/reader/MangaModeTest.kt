package com.sumatrapdf.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaModeTest {

    @Test
    fun isMangaReversed_singlePage_isFalse() {
        assertFalse(isMangaReversed(DisplayMode.SinglePage, mangaMode = true))
        assertFalse(isMangaReversed(DisplayMode.SinglePage, mangaMode = false))
    }

    @Test
    fun isMangaReversed_facing_isTrueWhenManga() {
        assertTrue(isMangaReversed(DisplayMode.Facing, mangaMode = true))
        assertFalse(isMangaReversed(DisplayMode.Facing, mangaMode = false))
    }

    @Test
    fun isMangaReversed_bookView_isTrueWhenManga() {
        assertTrue(isMangaReversed(DisplayMode.BookView, mangaMode = true))
        assertFalse(isMangaReversed(DisplayMode.BookView, mangaMode = false))
    }

    @Test
    fun rowPairOrder_ltr_keepsLeftThenRight() {
        assertEquals(Pair(2, 5), rowPairOrder(left = 2, right = 5, mangaMode = false))
        assertEquals(Pair(-1, 7), rowPairOrder(left = -1, right = 7, mangaMode = false))
        assertEquals(Pair(3, -1), rowPairOrder(left = 3, right = -1, mangaMode = false))
    }

    @Test
    fun rowPairOrder_manga_swapsLeftAndRight() {
        assertEquals(Pair(5, 2), rowPairOrder(left = 2, right = 5, mangaMode = true))
        assertEquals(Pair(7, -1), rowPairOrder(left = -1, right = 7, mangaMode = true))
        assertEquals(Pair(3, -1), rowPairOrder(left = 3, right = -1, mangaMode = true))
    }

    @Test
    fun chunkedPairs_ltr_facing_firstAlone() {
        val rows = chunkedPairs((0 until 6).toList(), firstAlone = true, reverseWithinPair = false)
        assertEquals(
            listOf(
                0 to -1,
                1 to 2,
                3 to 4,
                5 to -1,
            ),
            rows,
        )
    }

    @Test
    fun chunkedPairs_manga_facing_firstAlone_reversesWithinPair() {
        val rows = chunkedPairs((0 until 6).toList(), firstAlone = true, reverseWithinPair = true)
        assertEquals(
            listOf(
                0 to -1,
                2 to 1,
                4 to 3,
                5 to -1,
            ),
            rows,
        )
    }

    @Test
    fun chunkedPairs_ltr_facing_noFirstAlone() {
        val rows = chunkedPairs((0 until 5).toList(), firstAlone = false, reverseWithinPair = false)
        assertEquals(
            listOf(
                0 to 1,
                2 to 3,
                4 to -1,
            ),
            rows,
        )
    }

    @Test
    fun chunkedPairs_empty() {
        val rows = chunkedPairs(emptyList(), firstAlone = true, reverseWithinPair = false)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun chunkedPairs_singlePage() {
        val rows = chunkedPairs(listOf(0), firstAlone = true, reverseWithinPair = false)
        assertEquals(listOf(0 to -1), rows)
    }

    @Test
    fun chunkedPairs_manga_isNoOpWithSingleRow() {
        val rows = chunkedPairs(listOf(0), firstAlone = true, reverseWithinPair = true)
        assertEquals(listOf(0 to -1), rows)
    }
}
