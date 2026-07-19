package com.riffle.app.feature.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that [BookSectionGrid] applies the two-row dynamic preview rule:
 * show cols×2−1 cover tiles then a SeeMore tile that fills the last slot of
 * row 2 (never orphaned on its own row).
 */
@RunWith(AndroidJUnit4::class)
class BookSectionGridTest {

    @get:Rule
    val rule = createComposeRule()

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun fakeItems(n: Int) = List(n) { i ->
        LibraryItem(
            id = "$i",
            libraryId = "lib",
            title = "Book $i",
            author = "Author",
            coverUrl = null,
            readingProgress = 0f,
            isCached = false,
            isDownloaded = false,
            ebookFormat = EbookFormat.Epub,
        )
    }

    private fun setGridContent(itemCount: Int, onSeeMore: (() -> Unit)?) {
        rule.setContent {
            CompositionLocalProvider(LocalCoverGridScale provides 1f) {
                BookSectionGrid(
                    items = fakeItems(itemCount),
                    token = "",
                    onItemSelected = {},
                    onSeeMore = onSeeMore,
                )
            }
        }
    }

    // ── presence / absence of the SeeMore tile ────────────────────────────────

    @Test
    fun seeMoreAppearsWhenItemsExceedTwoRowCapacity() {
        // 20 items far exceeds any 2-row limit (max preview ≈ 15 at 0.7× tablet)
        setGridContent(itemCount = 20, onSeeMore = {})
        rule.onNode(hasText("more", substring = true)).assertIsDisplayed()
    }

    @Test
    fun seeMoreAbsentWhenItemsCountIsOne() {
        setGridContent(itemCount = 1, onSeeMore = {})
        assertTrue(
            "SeeMore should not appear for a single item",
            rule.onAllNodes(hasText("more", substring = true)).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun seeMoreAbsentAndAllItemsShownWhenCallbackIsNull() {
        // null onSeeMore: no tile, and all items must be displayed (no truncation)
        setGridContent(itemCount = 20, onSeeMore = null)
        assertTrue(
            "SeeMore should not appear when onSeeMore is null",
            rule.onAllNodes(hasText("more", substring = true)).fetchSemanticsNodes().isEmpty(),
        )
        val coverNodes = rule
            .onAllNodesWithContentDescription("Book", substring = true)
            .fetchSemanticsNodes()
        assertTrue(
            "All 20 items should be visible when onSeeMore is null, got ${coverNodes.size}",
            coverNodes.size == 20,
        )
    }

    // ── no-orphan: SeeMore tile shares its row with ≥ 1 cover tile ───────────

    @Test
    fun seeMoreTileSharesRowWithAtLeastOneCoverTile() {
        setGridContent(itemCount = 20, onSeeMore = {})

        val seeMoreNode = rule.onNode(hasText("more", substring = true)).fetchSemanticsNode()
        val seeMoreTop = seeMoreNode.boundsInRoot.top

        // BookCoverTile uses contentDescription = item.title → "Book 0", "Book 1", …
        val coverNodes = rule
            .onAllNodesWithContentDescription("Book", substring = true)
            .fetchSemanticsNodes()

        val coversSameRow = coverNodes.count { it.boundsInRoot.top == seeMoreTop }
        assertTrue(
            "SeeMore should share its row with ≥ 1 cover tile (was alone), " +
                "seeMoreTop=$seeMoreTop, coverTops=${coverNodes.map { it.boundsInRoot.top }}",
            coversSameRow >= 1,
        )
    }

    // ── overflow count in the tile label ─────────────────────────────────────

    @Test
    fun seeMoreDisplaysCorrectOverflowCount() {
        // Render with 20 items. Count how many cover tiles are actually visible, then
        // derive the expected overflow and check the tile's text.
        setGridContent(itemCount = 20, onSeeMore = {})

        val coverNodes = rule
            .onAllNodesWithContentDescription("Book", substring = true)
            .fetchSemanticsNodes()
        val shown = coverNodes.size
        val expectedOverflow = 20 - shown

        rule.onNode(hasText("+$expectedOverflow", substring = true)).assertIsDisplayed()
    }
}
