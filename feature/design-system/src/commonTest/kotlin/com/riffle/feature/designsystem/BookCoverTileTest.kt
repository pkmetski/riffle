package com.riffle.feature.designsystem

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The semantics contract of the one shared [BookCoverTile], driven on the iOS code path
 * (`runComposeUiTest` runs for real on `:feature:design-system:iosSimulatorArm64Test`; the JVM
 * host-test task excludes this class because Compose's UI-test harness needs a real Android
 * runtime and the repo has no Robolectric — see the module's build file).
 *
 * These matter because the iOS XCUITest harness locates every book by
 * `app.buttons["<exact title>"]`: Compose maps a merged node's `contentDescription` to the
 * element's accessibility label, so if the tile's caption or badges ever leaked into that merge
 * the label would become "Title, Author, Has readaloud …" and essentially the whole harness would
 * stop finding books.
 */
@OptIn(ExperimentalTestApi::class)
class BookCoverTileTest {

    // A real shelf cell. Without a width the tile fills the test window and its 2:3 cover runs
    // off the bottom of the screen, so assertIsDisplayed fails on everything below the fold.
    private val tileWidth = Modifier.width(120.dp)

    private fun item(
        title: String = "Tile Title",
        author: String = "Tile Author",
        downloaded: Boolean = false,
        cached: Boolean = false,
        coverUrl: String? = null,
    ) = LibraryItem(
        id = "item-1",
        libraryId = "lib-1",
        title = title,
        author = author,
        coverUrl = coverUrl,
        readingProgress = 0f,
        isCached = cached,
        isDownloaded = downloaded,
        ebookFormat = EbookFormat.Epub,
    )

    @Test
    fun tileIsOneClickableElementLabelledWithExactlyTheTitle() = runComposeUiTest {
        setContent { BookCoverTile(item = item(), token = "", onClick = {}, modifier = tileWidth) }

        // Exactly the title, not "Tile Title, Tile Author": the caption contributes to the node's
        // Text, and the cover image passes contentDescription = null.
        onNode(hasContentDescriptionExactly("Tile Title")).assertHasClickAction()
    }

    @Test
    fun coverArtworkDoesNotRepeatTheTitleInTheLabel() = runComposeUiTest {
        // With artwork present there are two candidates for the label: the merged parent's
        // contentDescription and the AsyncImage's own. ContentDescription merges by
        // CONCATENATION, so an image that also announces the title makes the tile's label
        // "Tile Title, Tile Title" and `app.buttons["Tile Title"]` stops matching.
        setContent {
            BookCoverTile(
                item = item(coverUrl = "https://example.invalid/cover.jpg"),
                token = "tok",
                onClick = {},
                modifier = tileWidth,
            )
        }

        onNode(hasContentDescriptionExactly("Tile Title")).assertHasClickAction()
    }

    @Test
    fun captionIsRenderedButContributesNoSemantics() = runComposeUiTest {
        setContent { BookCoverTile(item = item(), token = "", onClick = {}, modifier = tileWidth) }

        // iOS's tile had no title/author caption at all; Android's always had one. The caption is
        // deliberately invisible to the semantics tree (see the composable's KDoc), so assert it
        // by its layout: a 120dp-wide 2:3 cover is 180dp, and the tile is taller than that only
        // because the caption occupies space beneath it.
        val bounds = onNode(hasContentDescriptionExactly("Tile Title")).getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue(height > 180.dp, "expected a caption under the 180dp cover, tile was $height")
        // …and nothing the caption draws reaches the merged tree, which is what the platform
        // accessibility bridges read. (The nodes still exist in the UNmerged tree; that is what
        // clearAndSetSemantics means.)
        onNodeWithText("Tile Author").assertDoesNotExist()
        onNodeWithText("Tile Title").assertDoesNotExist()
    }

    @Test
    fun tapOpensTheItem() = runComposeUiTest {
        var taps = 0
        setContent { BookCoverTile(item = item(), token = "", onClick = { taps++ }, modifier = tileWidth) }

        onNode(hasContentDescriptionExactly("Tile Title")).performClick()
        assertEquals(1, taps)
    }

    @Test
    fun readaloudBadgeStaysAddressableOutsideTheTileLabel() = runComposeUiTest {
        setContent { BookCoverTile(item = item(), token = "", onClick = {}, modifier = tileWidth, hasReadaloudLink = true) }

        // Still exactly the title — the badge is a sibling of the merged node, not a descendant.
        onNode(hasContentDescriptionExactly("Tile Title")).assertHasClickAction()
        onNodeWithContentDescription("Has readaloud (synced narration)").assertIsDisplayed()
    }

    @Test
    fun downloadedBadgeIsLabelled() = runComposeUiTest {
        setContent { BookCoverTile(item = item(downloaded = true), token = "", onClick = {}, modifier = tileWidth) }

        // iOS drew this as a bare 8dp dot with no contentDescription, so VoiceOver skipped the
        // only offline signal on the screen.
        onNodeWithContentDescription("Downloaded").assertIsDisplayed()
    }

    @Test
    fun cachedBadgeIsLabelled() = runComposeUiTest {
        setContent { BookCoverTile(item = item(cached = true), token = "", onClick = {}, modifier = tileWidth) }

        onNodeWithContentDescription("Cached").assertIsDisplayed()
    }

    @Test
    fun seriesPositionBadgeRenders() = runComposeUiTest {
        setContent {
            BookCoverTile(item = item(), token = "", onClick = {}, modifier = tileWidth, seriesNameBadge = "#3")
        }

        onNodeWithText("#3", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun anUnplayableItemIsNotTappable() = runComposeUiTest {
        // ebookFormat Unsupported and no audio ⇒ nothing to open; Android dimmed and disabled the
        // tile, iOS happily let you tap through to an empty detail screen.
        val unplayable = item().copy(ebookFormat = EbookFormat.Unsupported)
        setContent { BookCoverTile(item = unplayable, token = "", onClick = {}, modifier = tileWidth) }

        onNode(hasContentDescriptionExactly("Tile Title")).assertIsNotEnabled()
    }
}
