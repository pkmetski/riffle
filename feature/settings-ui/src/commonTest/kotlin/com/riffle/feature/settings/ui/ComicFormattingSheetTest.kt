package com.riffle.feature.settings.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.domain.comic.BookComicFormattingOverrides
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.settings.ui.panels.ComicFormattingSheet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the shared [ComicFormattingSheet] on both platforms.
 *
 * Pins the delta-update contract: every control emits exactly one [BookComicFormattingOverrides]
 * with only its own field set, leaving all others null. This is what [CbzReaderViewModel]
 * relies on to merge book-level overrides on top of global defaults.
 *
 * Replaces the Android-only `comicDisplaySection_backgroundThemeIncludesAutoThemeChip` harness
 * test, which tested a now-deleted private [ComicDisplaySection] component. The equivalent
 * background-theme behavior is also covered by [ComicDisplaySettingsPanelTest].
 */
@OptIn(ExperimentalTestApi::class)
class ComicFormattingSheetTest {

    @Test
    fun sheetShowsAllSections() = runComposeUiTest {
        setContent {
            ComicFormattingSheet(
                formatting = ComicFormattingPreferences(),
                hasBookOverrides = false,
                onUpdate = {},
                onReset = {},
                onDismiss = {},
            )
        }
        onNodeWithText("Background").assertIsDisplayed()
        onNodeWithText("Panel View").assertIsDisplayed()
        onNodeWithText("Panel Overflow").assertIsDisplayed()
        onNodeWithText("On-screen info").assertIsDisplayed()
    }

    @Test
    fun panelViewSwitchEmitsOnlyPanelViewOn() = runComposeUiTest {
        val updates = mutableListOf<BookComicFormattingOverrides>()
        setContent {
            ComicFormattingSheet(
                formatting = ComicFormattingPreferences(panelViewOn = false),
                hasBookOverrides = false,
                onUpdate = { updates += it },
                onReset = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(TestTags.SETTINGS_COMIC_DISPLAY_PANEL_VIEW).performScrollTo().performClick()
        assertEquals(1, updates.size)
        assertTrue(updates[0].panelViewOn == true, "panelViewOn must be true")
        assertNull(updates[0].backgroundTheme, "backgroundTheme must not be set")
        assertNull(updates[0].panelOverflow, "panelOverflow must not be set")
        assertNull(updates[0].showChapterMap, "showChapterMap must not be set")
    }

    @Test
    fun readingProgressSwitchEmitsOnlyShowChapterMap() = runComposeUiTest {
        val updates = mutableListOf<BookComicFormattingOverrides>()
        setContent {
            ComicFormattingSheet(
                formatting = ComicFormattingPreferences(showChapterMap = false),
                hasBookOverrides = false,
                onUpdate = { updates += it },
                onReset = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(TestTags.SETTINGS_COMIC_DISPLAY_READING_PROGRESS).performScrollTo().performClick()
        assertEquals(1, updates.size)
        assertTrue(updates[0].showChapterMap == true, "showChapterMap must be true")
        assertNull(updates[0].panelViewOn, "panelViewOn must not be set")
    }

    @Test
    fun pageNumbersSwitchIsInertWhileReadingProgressIsOff() = runComposeUiTest {
        val updates = mutableListOf<BookComicFormattingOverrides>()
        setContent {
            ComicFormattingSheet(
                formatting = ComicFormattingPreferences(showChapterMap = false, showPageProgress = false),
                hasBookOverrides = false,
                onUpdate = { updates += it },
                onReset = {},
                onDismiss = {},
            )
        }
        onNodeWithTag(TestTags.SETTINGS_COMIC_DISPLAY_PAGE_NUMBERS).performScrollTo().assertIsNotEnabled()
        assertTrue(updates.isEmpty(), "no update must fire while switch is disabled")
    }

    @Test
    fun resetButtonIsInertWithoutBookOverrides() = runComposeUiTest {
        var resetCalled = false
        setContent {
            ComicFormattingSheet(
                formatting = ComicFormattingPreferences(),
                hasBookOverrides = false,
                onUpdate = {},
                onReset = { resetCalled = true },
                onDismiss = {},
            )
        }
        onNodeWithText("Reset to global defaults").performScrollTo().assertIsNotEnabled()
        assertFalse(resetCalled, "onReset must not fire when button is disabled")
    }

    @Test
    fun resetButtonIsEnabledWithBookOverrides() = runComposeUiTest {
        var resetCalled = false
        setContent {
            ComicFormattingSheet(
                formatting = ComicFormattingPreferences(),
                hasBookOverrides = true,
                onUpdate = {},
                onReset = { resetCalled = true },
                onDismiss = {},
            )
        }
        onNodeWithText("Reset to global defaults").performScrollTo().performClick()
        assertTrue(resetCalled, "onReset must fire when button is enabled and clicked")
    }
}
