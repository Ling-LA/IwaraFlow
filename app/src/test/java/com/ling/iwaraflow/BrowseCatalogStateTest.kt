package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test

class BrowseCatalogStateTest {
    private fun video(id: String) = VideoItem(id, "Video $id", "Example", emptyList(), 2)

    @Test fun paginationPreservesOfficialOrderAndDeduplicatesOverlappingPages() {
        val state = BrowseCatalogState()
        assertTrue(state.accept(0, listOf(video("a"), video("b")), 2, 2))
        assertTrue(state.accept(1, listOf(video("b"), video("c")), 2, 2))
        assertEquals(listOf("a", "b", "c"), state.items.map { it.id })
        assertEquals(1, state.page)
        assertTrue(state.canLoadMore)
    }

    @Test fun onlySuccessfulSequentialPagesAdvanceAndEmptyLastPageStops() {
        val state = BrowseCatalogState()
        state.accept(0, listOf(video("a")), 1, 1)
        // A retry of page 1 remains page 1 until success; stale page 3 must not skip it.
        assertFalse(state.accept(3, listOf(video("wrong")), 1, 1))
        assertEquals(0, state.page)
        assertTrue(state.accept(1, emptyList(), 0, 1))
        assertFalse(state.canLoadMore)
        assertEquals(listOf("a"), state.items.map { it.id })
    }

    @Test fun refreshReplacesTheCategoryAndResetsSelection() {
        val state = BrowseCatalogState()
        state.accept(0, listOf(video("old")), 1, 1)
        state.selectedIndex = 9
        state.accept(0, listOf(video("new")), 1, 1)
        assertEquals(listOf("new"), state.items.map { it.id })
        assertEquals(0, state.selectedIndex)
    }

    @Test fun recommendationIsNotACatalogCategory() {
        assertEquals(setOf("date", "trending", "popularity"), BrowseCatalogState.MODES)
        assertFalse("recommend" in BrowseCatalogState.MODES)
    }

    @Test fun compactSnapshotReplaysItsLastSourcePageInsteadOfSkippingTheUnpersistedTail() {
        val state = BrowseCatalogState()
        repeat(4) { page -> state.accept(page, (page * 28 until (page + 1) * 28).map { video("v-$it") }, 28, 28) }
        val snapshot = state.snapshot(0)
        // Forty persisted cards end in API page 1, so the next request must repeat page 1.
        assertEquals(0, snapshot.currentPage)
        val restored = BrowseCatalogState()
        restored.restore(snapshot.copy(items = snapshot.items.take(40)))
        assertTrue(restored.accept(1, (28 until 56).map { video("v-$it") }, 28, 28))
        assertEquals((0 until 56).map { "v-$it" }, restored.items.map { it.id })
        assertEquals(3, state.snapshot(90).currentPage)
    }
}
