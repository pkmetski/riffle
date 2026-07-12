package com.riffle.app.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.feature.settings.sections.SingletonWebSourceRow
import com.riffle.core.domain.ChitankaWebSourceDescriptor
import com.riffle.core.domain.Library
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceType
import com.riffle.core.domain.SourceUrl
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the singleton Chitanka Settings row to swipe-to-delete parity with every other
 * configured-source row: end-to-start swipe invokes onRemove, and no trailing "Remove" button
 * exists. If the row is un-wrapped from SwipeToDeleteRow or a Remove button is re-added, this
 * test fails. Post-ADR-0044 the row is the generic [SingletonWebSourceRow] parameterised by the
 * source's [com.riffle.core.domain.WebSourceDescriptor].
 */
@RunWith(AndroidJUnit4::class)
class ChitankaSourceRowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val fakeChitankaSource = Source(
        id = "chi:test",
        url = SourceUrl.parse("https://chitanka.info")!!,
        isActive = false,
        insecureConnectionAllowed = false,
        username = "",
        type = SourceType.CHITANKA,
        serverType = ServerType.AUDIOBOOKSHELF,
    )

    @Test
    fun endToStartSwipe_invokesOnRemove() {
        var removed = false
        composeTestRule.setContent {
            SingletonWebSourceRow(
                source = fakeChitankaSource,
                descriptor = ChitankaWebSourceDescriptor,
                libraryItems = emptyList(),
                isExpanded = false,
                onToggleExpanded = {},
                onSetLibraryVisible = { _, _ -> },
                onReorderLibraries = {},
                onRemove = { removed = true },
            )
        }

        composeTestRule.onNodeWithTag("CHITANKASourceRow").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        assertTrue("swipe end-to-start on Chitanka row must invoke onRemove", removed)
    }

    @Test
    fun expanded_libraryRows_doNotOverlap() {
        val books = LibraryUiItem(
            library = Library(id = "chi:books", name = "Books", mediaType = "book", isUnsupported = false),
            isVisible = true,
            switchEnabled = true,
        )
        val gramofonche = LibraryUiItem(
            library = Library(id = "chi:gramofonche", name = "Gramofonche", mediaType = "book", isUnsupported = false),
            isVisible = true,
            switchEnabled = true,
        )
        composeTestRule.setContent {
            SingletonWebSourceRow(
                source = fakeChitankaSource,
                descriptor = ChitankaWebSourceDescriptor,
                libraryItems = listOf(books, gramofonche),
                isExpanded = true,
                onToggleExpanded = {},
                onSetLibraryVisible = { _, _ -> },
                onReorderLibraries = {},
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("Books").assertIsDisplayed()
        composeTestRule.onNodeWithText("Gramofonche").assertIsDisplayed()

        val booksBounds = composeTestRule.onNodeWithText("Books").getUnclippedBoundsInRoot()
        val gramofoncheBounds = composeTestRule.onNodeWithText("Gramofonche").getUnclippedBoundsInRoot()
        // If the two library ListItems drew on top of each other (the AnimatedVisibility→Box→stack
        // bug), both labels would land at the same origin. Assert Gramofonche's top is strictly
        // below Chitanka's bottom — the guarantee the Column wrapper inside ExpandableSourceRow
        // must give every source's expanded body.
        assertTrue(
            "Gramofonche row must be laid out below the Books library row " +
                "(Books bottom=${booksBounds.bottom}, Gramofonche top=${gramofoncheBounds.top})",
            gramofoncheBounds.top >= booksBounds.bottom,
        )
    }

    @Test
    fun row_rendersSourceLogoAlongsideChevron() {
        composeTestRule.setContent {
            SingletonWebSourceRow(
                source = fakeChitankaSource,
                descriptor = ChitankaWebSourceDescriptor,
                libraryItems = emptyList(),
                isExpanded = false,
                onToggleExpanded = {},
                onSetLibraryVisible = { _, _ -> },
                onReorderLibraries = {},
                onRemove = {},
            )
        }

        // Chevron + source-logo icon must both be rendered inside the leading slot. Reverting the
        // required `leadingIcon` param on ExpandableSourceRow — or wiring an empty `{}` lambda at
        // a call site — drops this to one child and fails the assertion.
        composeTestRule.onNodeWithTag("ExpandableSourceRow.LeadingIcon")
            .onChildren()
            .assertCountEquals(2)
    }

    @Test
    fun row_hasNoTrailingRemoveButton() {
        composeTestRule.setContent {
            SingletonWebSourceRow(
                source = fakeChitankaSource,
                descriptor = ChitankaWebSourceDescriptor,
                libraryItems = emptyList(),
                isExpanded = false,
                onToggleExpanded = {},
                onSetLibraryVisible = { _, _ -> },
                onReorderLibraries = {},
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("Remove").assertDoesNotExist()
    }
}
