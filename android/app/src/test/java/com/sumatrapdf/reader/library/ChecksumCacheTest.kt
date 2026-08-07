package com.sumatrapdf.reader.library

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ChecksumCacheTest {

    private lateinit var cacheRoot: File
    private lateinit var metaDir: File

    @Before
    fun setUp() {
        cacheRoot = File.createTempFile("checksum-cache-test", "").also {
            it.delete()
            it.mkdirs()
        }
        LibraryCache.root = cacheRoot
        metaDir = File(cacheRoot, "meta").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        cacheRoot.deleteRecursively()
    }

    private fun metaFor(id: String): File = File(metaDir, "$id.json")

    private fun writeMeta(id: String, title: String, subjects: List<String>) {
        val f = metaFor(id)
        f.writeText(
            """{"title":"$title","subjects":${subjects.joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" }}}"""
        )
    }

    private fun book(
        path: String,
        title: String = path.substringAfterLast('/'),
        checksum: String? = null,
    ): Book = Book(
        id = path.hashCode().toString(),
        path = path,
        file = path.substringAfterLast('/'),
        ext = path.substringAfterLast('.', ""),
        title = title,
        author = null,
        volumes = emptyList(),
        year = null,
        pages = 0,
        ink = 0,
        art = null,
        sample = "",
        size = 0L,
        mtime = 0L,
        folder = "",
        seriesFolder = null,
        readable = true,
        checksum = checksum,
    )

    @Test
    fun promoteChecksumCache_promotesFromExistingPeer() {
        val peerPath = "/library/book-v1.pdf"
        val dupPath = "/library/book-v1 (copy).pdf"
        writeMeta(peerPath.hashCode().toString(), "Original", listOf("Fiction"))
        val promoted = promoteChecksumCache(
            listOf(
                book(peerPath, checksum = "abc123"),
                book(dupPath, title = "Copy", checksum = "abc123"),
            ),
        )
        assertEquals(1, promoted)
        val dupFile = metaFor(dupPath.hashCode().toString())
        assertTrue("dup should now have a meta file", dupFile.exists())
        // The duplicated cache file should hold the same JSON as the
        // peer — the scrape result, not just a stub.
        val original = metaFor(peerPath.hashCode().toString()).readText()
        val copied = dupFile.readText()
        assertEquals(original, copied)
        assertTrue("copied meta should still carry the subject",
            copied.contains("Fiction"))
    }

    @Test
    fun promoteChecksumCache_skipsWhenAlreadyCached() {
        val path = "/library/book.pdf"
        writeMeta(path.hashCode().toString(), "Original", listOf("Drama"))
        val promoted = promoteChecksumCache(listOf(book(path, checksum = "abc123")))
        assertEquals(0, promoted)
    }

    @Test
    fun promoteChecksumCache_doesNotPromoteFromItself() {
        val path = "/library/book.pdf"
        // No cache exists; nothing to promote to itself even if checksum is set.
        val promoted = promoteChecksumCache(listOf(book(path, checksum = "abc123")))
        assertEquals(0, promoted)
    }

    @Test
    fun promoteChecksumCache_ignoresBooksWithoutChecksum() {
        val path = "/library/no-checksum.pdf"
        writeMeta(path.hashCode().toString(), "Original", listOf("Bio"))
        val promoted = promoteChecksumCache(listOf(book(path, checksum = null)))
        // No cache on disk because we wrote it under the hashCode-keyed name,
        // but the book's `b.id` is its path's hashCode, so the lookup will
        // still find it. The point of this test is that the absence of a
        // checksum is not an error.
        assertEquals(0, promoted)
        assertTrue("cache file is in place",
            metaFor(path.hashCode().toString()).exists())
    }

    @Test
    fun promoteChecksumCache_picksAnyPeerWithMatchingChecksum() {
        // Three copies of the same book, only one has a cached meta.
        // Any one of the two non-cached ones should pull from the cached one.
        val cached = "/library/master.pdf"
        val other1 = "/library/copy-a.pdf"
        val other2 = "/library/copy-b.pdf"
        writeMeta(cached.hashCode().toString(), "Master", listOf("Reference"))
        val promoted = promoteChecksumCache(
            listOf(
                book(cached, checksum = "same"),
                book(other1, title = "A", checksum = "same"),
                book(other2, title = "B", checksum = "same"),
            ),
        )
        assertEquals(2, promoted)
        assertTrue(metaFor(other1.hashCode().toString()).exists())
        assertTrue(metaFor(other2.hashCode().toString()).exists())
    }
}
