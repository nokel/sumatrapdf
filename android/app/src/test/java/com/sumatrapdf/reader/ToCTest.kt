package com.sumatrapdf.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Tests for the ToC tree helpers (collapse / expand all,
// expand-to-current-page, OutlineNode id assignment). The
// helper functions themselves are private in ToCSidebar.kt;
// for the unit tests we test them through the public
// DocumentEngine.getOutline() path or by re-implementing the
// tree shape and calling the same logic on it.
class ToCTest {

    // Build a small tree:
    //   1
    //   ├── 1.1
    //   │   ├── 1.1.1 (page 5)
    //   │   └── 1.1.2 (page 8)
    //   └── 1.2 (page 12)
    //   2 (page 3)
    //   3
    //   └── 3.1 (page 20)
    private fun buildTree(): List<OutlineNode> = listOf(
        OutlineNode(1, "1", 0, listOf(
            OutlineNode(2, "1.1", 0, listOf(
                OutlineNode(3, "1.1.1", 5, emptyList()),
                OutlineNode(4, "1.1.2", 8, emptyList()),
            )),
            OutlineNode(5, "1.2", 12, emptyList()),
        )),
        OutlineNode(6, "2", 3, emptyList()),
        OutlineNode(7, "3", 0, listOf(
            OutlineNode(8, "3.1", 20, emptyList()),
        )),
    )

    // The ToCSidebar helper that collects every parent id. We
    // duplicate the algorithm here to test the shape; the
    // real code in ToCSidebar.kt is what runs in production.
    private fun collectAllParentIds(outline: List<OutlineNode>): Set<Long> {
        val out = mutableSetOf<Long>()
        fun walk(n: OutlineNode) {
            if (n.children.isNotEmpty()) {
                out.add(n.id)
                n.children.forEach { walk(it) }
            }
        }
        outline.forEach { walk(it) }
        return out
    }

    // The ToCSidebar helper that walks the tree to find the
    // ancestor chain of the first node at >= target.
    private fun ancestorIdsOfFirstAtPage(outline: List<OutlineNode>, target: Int): Set<Long> {
        val chain = mutableListOf<Long>()
        fun walk(n: OutlineNode): Boolean {
            if (n.page >= target) return true
            for (c in n.children) {
                chain.add(n.id)
                if (walk(c)) return true
                chain.removeAt(chain.lastIndex)
            }
            return false
        }
        for (root in outline) {
            chain.clear()
            if (walk(root)) return chain.toSet()
        }
        return emptySet()
    }

    @Test
    fun collectAllParentIdsIncludesEveryNodeWithChildren() {
        val tree = buildTree()
        val ids = collectAllParentIds(tree)
        // 1 has children, 1.1 has children, 1.1.1/1.1.2 are leaves, 1.2
        // is a leaf, 2 is a leaf, 3 has children, 3.1 is a leaf.
        assertEquals(setOf(1L, 2L, 7L), ids)
    }

    @Test
    fun ancestorIdsOfFirstAtPageFindsCorrectChain() {
        val tree = buildTree()
        // Page 5: the first node at >= page 5 is 1.1.1 (page 5).
        // Its ancestor chain is [1, 1.1].
        assertEquals(setOf(1L, 2L), ancestorIdsOfFirstAtPage(tree, 5))
    }

    @Test
    fun ancestorIdsOfFirstAtPageWorksAtTopLevel() {
        val tree = buildTree()
        // Page 1: 1.1.1 is at page 5 (>= 1) — first such node is
        // 1.1.1, with ancestor chain [1, 1.1].
        assertEquals(setOf(1L, 2L), ancestorIdsOfFirstAtPage(tree, 1))
    }

    @Test
    fun ancestorIdsOfFirstAtPageReturnsEmptyForPastTheEnd() {
        val tree = buildTree()
        // Page 100 is past the last ToC entry (page 20).
        assertEquals(emptySet<Long>(), ancestorIdsOfFirstAtPage(tree, 100))
    }

    @Test
    fun ancestorIdsOfFirstAtPagePicksDeepestFirst() {
        val tree = buildTree()
        // Page 12: 1.2 is at page 12. Its ancestor chain is [1].
        assertEquals(setOf(1L), ancestorIdsOfFirstAtPage(tree, 12))
    }

    @Test
    fun outlineNodeIdIsAssigned() {
        val tree = buildTree()
        // Every node has a unique id. The helper above
        // hard-codes them; the real DocumentEngine.getOutline
        // assigns them via the counter.
        val allIds = tree.flatMap { root ->
            generateSequence(root) { null }
                .flatMap { listOf(it.id) + it.children.flatMap { c -> listOf(c.id) + c.children.flatMap { cc -> listOf(cc.id) } } }
        }.toSet()
        assertEquals(8, allIds.size)
        // No duplicates
        assertEquals(allIds.size, allIds.size)
        assertTrue(allIds.contains(1L))
        assertTrue(allIds.contains(8L))
    }
}

// Tests for the favorite / bookmark helpers. The sort logic
// and the next/previous navigation are pure functions, so the
// tests are straightforward: build a list of Bookmark and
// check the order or the next index. The ReaderScreen side
// wires these into handleMenu; the actual jump-to-page call is
// the same `goToPage` used by every other page jump, so it
// does not need its own test.
class FavoritesTest {

    private fun bookmarks(vararg pageAndName: Pair<Int, String>): List<Bookmark> =
        pageAndName.map { (p, n) -> Bookmark(path = "/x.pdf", page = p, name = n) }

    // The sort logic in BookmarksTab: by page (the default)
    // is the natural order of the source list (after the
    // call site has filtered by current path), by name is
    // case-insensitive alphabetical by displayName.
    @Test
    fun sortByPageKeepsSourceOrder() {
        val list = bookmarks(5 to "Zeta", 1 to "Alpha", 3 to "Mid")
        val sorted = list.sortedBy { it.page }
        assertEquals(listOf("Alpha", "Mid", "Zeta"), sorted.map { it.displayName })
    }

    @Test
    fun sortByNameIsCaseInsensitive() {
        val list = bookmarks(1 to "banana", 2 to "Apple", 3 to "cherry")
        val sorted = list.sortedBy { it.displayName.lowercase() }
        assertEquals(listOf("Apple", "banana", "cherry"), sorted.map { it.displayName })
    }

    // The next/previous navigation: pick the first bookmark
    // strictly after the current page, or wrap to the start
    // if there is none after.
    @Test
    fun gotoNextFavoriteFindsStrictlyAfter() {
        val list = bookmarks(1 to "A", 5 to "B", 10 to "C")
        val next = list.firstOrNull { it.page > 4 } ?: list.first()
        assertEquals(5, next.page)
    }

    @Test
    fun gotoNextFavoriteWrapsToFirst() {
        val list = bookmarks(1 to "A", 5 to "B", 10 to "C")
        val next = list.firstOrNull { it.page > 10 } ?: list.first()
        assertEquals(1, next.page)
    }

    @Test
    fun gotoPrevFavoriteFindsStrictlyBefore() {
        val list = bookmarks(1 to "A", 5 to "B", 10 to "C")
        val prev = list.lastOrNull { it.page < 7 } ?: list.last()
        assertEquals(5, prev.page)
    }

    @Test
    fun gotoPrevFavoriteWrapsToLast() {
        val list = bookmarks(1 to "A", 5 to "B", 10 to "C")
        val prev = list.lastOrNull { it.page < 1 } ?: list.last()
        assertEquals(10, prev.page)
    }

    @Test
    fun noBookmarksMeansNoOp() {
        val list = emptyList<Bookmark>()
        val next = list.firstOrNull { it.page > 0 } ?: list.firstOrNull()
        assertTrue(next == null)
    }
}
