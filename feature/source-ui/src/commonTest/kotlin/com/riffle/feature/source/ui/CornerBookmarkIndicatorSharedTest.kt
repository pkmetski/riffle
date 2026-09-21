package com.riffle.feature.source.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bookmark ribbon, driven on `iosSimulatorArm64` as well as the JVM.
 *
 * Android's `CornerBookmarkIndicatorTest` (an instrumented test in `app/src/androidTest`) covers
 * the same composable on a device. It stays: it is the only place the ribbon is checked against
 * a real `WebView` underneath. This is its iOS counterpart — iOS had no ribbon at all, and a
 * bookmarked page was marked only by a blue wash over the text.
 */
class CornerBookmarkIndicatorSharedTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theRibbonAnnouncesWhichWayTheTapWillGo() = runComposeUiTest {
        // The label is the whole affordance for VoiceOver: a ribbon that always announced
        // "Bookmark this page" would tell a blind reader nothing about the current state.
        setContent {
            CornerBookmarkIndicator(isBookmarked = true, isVisible = true, onToggle = {})
        }
        onNodeWithContentDescription("Remove bookmark").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anUnbookmarkedPageOffersToBookmarkIt() = runComposeUiTest {
        setContent {
            CornerBookmarkIndicator(isBookmarked = false, isVisible = true, onToggle = {})
        }
        onNodeWithContentDescription("Bookmark this page").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tappingTheRibbonToggles() = runComposeUiTest {
        var toggles = 0
        setContent {
            CornerBookmarkIndicator(isBookmarked = false, isVisible = true, onToggle = { toggles++ })
        }
        onNodeWithContentDescription("Bookmark this page").performClick()
        assertEquals(1, toggles)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anInvisibleRibbonIsNotComposedAtAll() = runComposeUiTest {
        // Not merely transparent: a source with no annotation support must not leave an
        // invisible tap target sitting over the top-right corner of the page.
        setContent {
            CornerBookmarkIndicator(isBookmarked = false, isVisible = false, onToggle = {})
        }
        onNodeWithContentDescription("Bookmark this page").assertDoesNotExist()
        onNodeWithContentDescription("Remove bookmark").assertDoesNotExist()
    }
}
