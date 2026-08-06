package com.sumatrapdf.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SentenceSplitterTest {

    @Test
    fun splitsOnSentenceEnders() {
        val out = splitIntoSentences("One. Two! Three? Four.")
        assertEquals(listOf("One.", "Two!", "Three?", "Four."), out)
    }

    @Test
    fun joinsHardWrappedLinesIntoOneSentence() {
        val out = splitIntoSentences("The quick brown\n   fox jumps over\nthe lazy dog.")
        assertEquals(listOf("The quick brown fox jumps over the lazy dog."), out)
    }

    @Test
    fun dropsBlankRuns() {
        assertEquals(emptyList<String>(), splitIntoSentences("   \n\n  \t "))
        assertEquals(emptyList<String>(), splitIntoSentences(""))
    }

    @Test
    fun treatsNonBreakingSpaceAsASpace() {
        val out = splitIntoSentences("Chapter 1 begins.")
        assertEquals(listOf("Chapter 1 begins."), out)
    }

    @Test
    fun breaksOverlongSentencesOnWordBoundaries() {
        val word = "alpha "
        val long = word.repeat(200).trim() + "."
        val out = splitIntoSentences(long, maxChunk = 100)
        assertTrue("expected several chunks, got ${out.size}", out.size > 1)
        for (chunk in out) {
            assertTrue("chunk of ${chunk.length} exceeds the limit", chunk.length <= 100)
            assertTrue("chunk should not start or end with a space", chunk == chunk.trim())
        }
        assertEquals(long.replace(".", ""), out.joinToString(" ").replace(".", ""))
    }

    @Test
    fun keepsEveryWordWhenSplitting() {
        val source = "Alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu."
        val out = splitIntoSentences(source, maxChunk = 20)
        val rejoined = out.joinToString(" ")
        assertEquals(source.split(" ").size, rejoined.split(" ").size)
    }
}

class WebLookupTest {

    @Test
    fun encodesTheSelectionIntoEveryProvider() {
        val selection = "fox & hound"
        for (kind in WebLookup.values()) {
            val url = lookupUrl(kind, selection)
            assertTrue("$kind did not encode the selection: $url", url.contains("fox+%26+hound"))
            assertTrue("$kind is not https: $url", url.startsWith("https://"))
        }
    }

    @Test
    fun usesTheUrlsTheWindowsAppUses() {
        assertTrue(lookupUrl(WebLookup.Google, "x").startsWith("https://www.google.com/search?q="))
        assertTrue(lookupUrl(WebLookup.Bing, "x").startsWith("https://www.bing.com/search?q="))
        assertTrue(lookupUrl(WebLookup.Wikipedia, "x").startsWith("https://wikipedia.org/w/index.php?search="))
        assertTrue(lookupUrl(WebLookup.GoogleScholar, "x").startsWith("https://scholar.google.com/scholar?q="))
    }
}

class MimeTypeTest {

    @Test
    fun mapsTheFormatsTheManifestAdvertises() {
        assertEquals("application/pdf", mimeTypeFor("/books/a.pdf"))
        assertEquals("application/pdf", mimeTypeFor("/books/A.PDF"))
        assertEquals("application/epub+zip", mimeTypeFor("/books/a.epub"))
        assertEquals("application/oxps", mimeTypeFor("/books/a.xps"))
        assertEquals("application/vnd.comicbook+zip", mimeTypeFor("/books/a.cbz"))
        assertEquals("application/octet-stream", mimeTypeFor("/books/a.unknown"))
        assertEquals("application/octet-stream", mimeTypeFor("/books/noextension"))
    }
}

class FolderNavigationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun makeBooks(vararg names: String): List<String> =
        names.map { folder.newFile(it).absolutePath }

    @Test
    fun walksTheFolderInCaseInsensitiveNameOrder() {
        makeBooks("beta.pdf", "Alpha.pdf", "gamma.epub", "notes.txt.bak", "cover.png")
        val alpha = folder.root.resolve("Alpha.pdf").absolutePath
        val beta = folder.root.resolve("beta.pdf").absolutePath
        val gamma = folder.root.resolve("gamma.epub").absolutePath

        assertEquals(beta, neighbourDocument(alpha, 1))
        assertEquals(gamma, neighbourDocument(beta, 1))
        assertEquals(beta, neighbourDocument(gamma, -1))
        assertEquals(alpha, neighbourDocument(beta, -1))
    }

    @Test
    fun stopsAtBothEndsOfTheFolder() {
        makeBooks("one.pdf", "two.pdf")
        val one = folder.root.resolve("one.pdf").absolutePath
        val two = folder.root.resolve("two.pdf").absolutePath
        assertNull(neighbourDocument(one, -1))
        assertNull(neighbourDocument(two, 1))
    }

    @Test
    fun ignoresFilesThatAreNotDocuments() {
        makeBooks("a.pdf", "b.png", "c.zip", "d.epub")
        val listed = siblingDocuments(folder.root.resolve("a.pdf").absolutePath)
        assertEquals(2, listed.size)
        assertTrue(listed.all { it.endsWith(".pdf") || it.endsWith(".epub") })
    }

    @Test
    fun returnsNothingForAFileThatIsNotInItsOwnListing() {
        makeBooks("a.pdf")
        val ghost = folder.root.resolve("missing.pdf").absolutePath
        assertNull(neighbourDocument(ghost, 1))
    }
}

// src/FileHistory.cpp::cmpOpenCount / cmpRecentlyOpened. `index` is the
// position in the recency list, which the real code stamps on before
// sorting; the tests set it directly.
class FileHistoryOrderTest {

    private fun entry(
        name: String,
        openCount: Int = 0,
        index: Int = 0,
        pinned: Boolean = false,
    ) = FileHistoryEntry(
        path = "/books/$name",
        displayName = name,
        openCount = openCount,
        index = index,
        isPinned = pinned,
    )

    private fun names(list: List<FileHistoryEntry>) = list.map { it.displayName }

    @Test
    fun frequencyOrderPutsTheMostOpenedFirst() {
        val list = listOf(
            entry("rare.pdf", openCount = 1, index = 0),
            entry("often.pdf", openCount = 9, index = 1),
            entry("sometimes.pdf", openCount = 4, index = 2),
        )
        assertEquals(
            listOf("often.pdf", "sometimes.pdf", "rare.pdf"),
            names(list.sortedWith(::cmpOpenCount)),
        )
    }

    @Test
    fun equalOpenCountsFallBackToRecency() {
        val list = listOf(
            entry("older.pdf", openCount = 3, index = 5),
            entry("newer.pdf", openCount = 3, index = 1),
        )
        assertEquals(listOf("newer.pdf", "older.pdf"), names(list.sortedWith(::cmpOpenCount)))
    }

    @Test
    fun pinnedEntriesComeFirstAndSortByNameInBothOrderings() {
        val list = listOf(
            entry("zzz.pdf", openCount = 99, index = 0),
            entry("pinned-b.pdf", openCount = 0, index = 7, pinned = true),
            entry("pinned-a.pdf", openCount = 0, index = 3, pinned = true),
        )
        assertEquals(
            listOf("pinned-a.pdf", "pinned-b.pdf", "zzz.pdf"),
            names(list.sortedWith(::cmpOpenCount)),
        )
        assertEquals(
            listOf("pinned-a.pdf", "pinned-b.pdf", "zzz.pdf"),
            names(list.sortedWith(::cmpRecentlyOpened)),
        )
    }

    @Test
    fun recencyOrderIgnoresOpenCount() {
        val list = listOf(
            entry("hot.pdf", openCount = 50, index = 4),
            entry("cold.pdf", openCount = 1, index = 0),
        )
        assertEquals(listOf("cold.pdf", "hot.pdf"), names(list.sortedWith(::cmpRecentlyOpened)))
    }
}

// src/base/Str.cpp::CmpNatural
class NaturalSortTest {

    @Test
    fun comparesDigitRunsAsNumbers() {
        assertTrue(compareNatural("chapter 2.pdf", "chapter 10.pdf") < 0)
        assertTrue(compareNatural("chapter 10.pdf", "chapter 9.pdf") > 0)
    }

    @Test
    fun ignoresLeadingZeroesWhenComparingMagnitudes() {
        assertTrue(compareNatural("part 007", "part 8") < 0)
        assertTrue(compareNatural("part 007", "part 6") > 0)
    }

    @Test
    fun equalNumbersFallBackToALexicographicTiebreak() {
        assertTrue(compareNatural("part 007", "part 7") < 0)
        assertTrue(compareNatural("part 7", "part 007") > 0)
        assertEquals(0, compareNatural("part 7", "part 7"))
    }

    // Case only decides the order once everything else is equal, and
    // then via the lexicographic tiebreak on the originals, so an
    // upper-case name sorts next to its lower-case twin rather than in
    // a separate block of capitals.
    @Test
    fun caseOnlyMattersAsATiebreak() {
        assertTrue(compareNatural("Alpha", "alpha") < 0)
        assertTrue(compareNatural("alpha", "Alpha") > 0)
        assertEquals(0, compareNatural("Alpha", "Alpha"))
        assertTrue(compareNatural("Alpha", "beta") < 0)
        assertTrue(compareNatural("Beta", "alpha") > 0)
    }

    @Test
    fun sortsSpecialCharactersBeforeLettersAndDigits() {
        assertTrue(compareNatural("_notes.pdf", "alpha.pdf") < 0)
        assertTrue(compareNatural("alpha.pdf", "_notes.pdf") > 0)
    }

    @Test
    fun runsOfWhitespaceDoNotChangeTheOrdering() {
        assertTrue(compareNatural("a   b", "a c") < 0)
        assertTrue(compareNatural("a   c", "a b") > 0)
        assertTrue(compareNatural("a   b", "a b") < 0)
    }

    @Test
    fun sortsAListTheWayTheStartPageShowsIt() {
        val input = listOf("Book 10.pdf", "book 2.pdf", "Book 1.pdf", "atlas.pdf")
        assertEquals(
            listOf("atlas.pdf", "Book 1.pdf", "book 2.pdf", "Book 10.pdf"),
            input.sortedWith { a, b -> compareNatural(a, b) },
        )
    }
}

// src/DisplayMode.cpp::ZoomToString / ZoomFromString and
// DisplayModeToString / DisplayModeFromString.
class FileStateEncodingTest {

    @Test
    fun everyZoomModeSurvivesARoundTrip() {
        for (level in ZoomLevel.values()) {
            val custom = if (level == ZoomLevel.Custom) 1.25f else 1f
            val (back, backCustom) = zoomFromString(zoomToString(level, custom))!!
            assertEquals(level, back)
            assertEquals(custom, backCustom, 0.0001f)
        }
    }

    // The float encoding this replaced wrote 0f for fit width, fit height
    // and fit content alike, so two of the four fit modes were lost on
    // every reopen.
    @Test
    fun fitHeightAndFitContentAreNoLongerCollapsedOntoFitWidth() {
        assertEquals("fit height", zoomToString(ZoomLevel.FitHeight, 1f))
        assertEquals("fit content", zoomToString(ZoomLevel.FitContent, 1f))
        assertEquals(ZoomLevel.FitHeight, zoomFromString("fit height")!!.first)
        assertEquals(ZoomLevel.FitContent, zoomFromString("fit content")!!.first)
    }

    @Test
    fun usesTheSettingsFileSpelling() {
        assertEquals("fit page", zoomToString(ZoomLevel.FitPage, 1f))
        assertEquals("single page", displayModeToString(DisplayMode.SinglePage, false))
        assertEquals("continuous", displayModeToString(DisplayMode.SinglePage, true))
        assertEquals("continuous book view", displayModeToString(DisplayMode.BookView, true))
    }

    @Test
    fun rejectsZoomOutsideTheDesktopRange() {
        assertNull(zoomFromString("0"))
        assertNull(zoomFromString("9000"))
        assertNull(zoomFromString("not a number"))
        assertEquals(ZoomLevel.Custom, zoomFromString("6400")!!.first)
        assertEquals(ZoomLevel.Custom, zoomFromString("8.33")!!.first)
    }

    @Test
    fun everyDisplayModeSurvivesARoundTrip() {
        for (mode in DisplayMode.values()) {
            for (continuous in listOf(false, true)) {
                val back = displayModeFromString(displayModeToString(mode, continuous))!!
                assertEquals(mode, back.first)
                assertEquals(continuous, back.second)
            }
        }
    }

    @Test
    fun unknownStringsFallBackToTheCallersDefault() {
        assertNull(displayModeFromString("automatic"))
        assertNull(displayModeFromString(null))
        assertNull(zoomFromString(null))
    }

    @Test
    fun migratesTheLayoutNamesThePortUsedBefore() {
        assertEquals("single page", migrateLegacyLayout("Single"))
        assertEquals("facing", migrateLegacyLayout("Facing"))
        assertEquals("book view", migrateLegacyLayout("Book"))
        assertEquals("continuous", migrateLegacyLayout("SingleContinuous"))
        assertEquals("continuous facing", migrateLegacyLayout("FacingContinuous"))
        assertEquals("continuous book view", migrateLegacyLayout("BookContinuous"))
        assertEquals(
            DisplayMode.BookView to true,
            displayModeFromString(migrateLegacyLayout("BookContinuous")),
        )
    }
}

// src/FilterHighlightDraw.cpp::SplitFilterToWords / FilterMatches
class StartPageFilterTest {

    @Test
    fun everyWordMustMatchSomewhere() {
        val words = splitFilterToWords("  war   peace ")
        assertEquals(listOf("war", "peace"), words)
        assertTrue(filterMatches("War and Peace.epub", words))
        assertTrue(!filterMatches("War of the Worlds.epub", words))
    }

    @Test
    fun anEmptyFilterHasNoWordsAndMatchesEverything() {
        val words = splitFilterToWords("   ")
        assertEquals(emptyList<String>(), words)
        assertTrue(filterMatches("anything.pdf", words))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(filterMatches("Moby-Dick.pdf", splitFilterToWords("MOBY dick")))
    }
}
