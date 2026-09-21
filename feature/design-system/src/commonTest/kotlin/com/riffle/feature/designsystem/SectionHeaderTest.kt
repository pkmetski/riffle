package com.riffle.feature.designsystem

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one [SectionHeader] that replaced five. Runs on the iOS code path — see the note on
 * [BookCoverTileTest] for why the JVM host-test task skips this class.
 */
@OptIn(ExperimentalTestApi::class)
class SectionHeaderTest {

    @Test
    fun rendersTheTitleAlone() = runComposeUiTest {
        setContent { SectionHeader(title = "Recently Added") }

        onNodeWithText("Recently Added").assertIsDisplayed()
    }

    @Test
    fun totalLabelRendersAsAMiddleDotSuffix() = runComposeUiTest {
        // Android's Downloads header has always read "Downloaded · 1.2 MB"; iOS built the same
        // string by hand at the call site with parentheses instead.
        setContent { SectionHeader(title = "Downloaded", totalLabel = "1.2 MB") }

        onNodeWithText("Downloaded · 1.2 MB").assertIsDisplayed()
    }

    @Test
    fun trailingActionIsRenderedAndClickable() = runComposeUiTest {
        var clicks = 0
        setContent {
            SectionHeader(title = "In Progress", actionLabel = "See all", onAction = { clicks++ })
        }

        onNodeWithText("See all").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun noActionIsRenderedWithoutACallback() = runComposeUiTest {
        setContent { SectionHeader(title = "Series", actionLabel = "See all") }

        onNodeWithText("See all").assertDoesNotExist()
    }

    @Test
    fun tagExposesALocaleIndependentIdentifier() = runComposeUiTest {
        // XCUITest reads a Compose testTag as the element's accessibilityIdentifier, so the
        // harness can find a section without matching its translated title.
        setContent { SectionHeader(title = "In Progress", tag = "section-header-IN_PROGRESS") }

        onNodeWithTag("section-header-IN_PROGRESS").assertIsDisplayed()
    }
}
