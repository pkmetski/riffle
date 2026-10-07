package com.riffle.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.settings.ui.panels.ComicDisplaySettingsPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives the shared ComicDisplaySettingsPanel composable on both platforms.
 *
 * Prior to this change the Android actual was a blank TODO and the iOS actual was missing the
 * Background-theme chips and Panel-animation-speed slider. These tests pin the behaviour that was
 * broken: every control must appear and must write the correct preference field when toggled.
 *
 * Runs as part of `:feature:settings-ui:iosSimulatorArm64Test` (iOS) and the JVM host-test task
 * is excluded since the Compose UI harness requires a real device runtime.
 */
@OptIn(ExperimentalTestApi::class)
class ComicDisplaySettingsPanelTest {

    @Test
    fun panelShowsAllSections() = runComposeUiTest {
        setContent {
            Column { ComicDisplaySettingsPanel(ComicFormattingPreferences(), onPrefsChange = {}, onDismiss = {}) }
        }
        onNodeWithText("Background").assertIsDisplayed()
        onNodeWithText("Panel View").assertIsDisplayed()
        onNodeWithText("Panel Overflow").assertIsDisplayed()
        onNodeWithText("Animation Speed").assertIsDisplayed()
        onNodeWithText("On-screen info").assertIsDisplayed()
    }

    @Test
    fun panelViewSwitchWritesPanelViewOn() = runComposeUiTest {
        val writes = mutableListOf<ComicFormattingPreferences>()
        setContent {
            Column {
                ComicDisplaySettingsPanel(
                    ComicFormattingPreferences(panelViewOn = false),
                    onPrefsChange = { writes += it },
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(TestTags.SETTINGS_COMIC_DISPLAY_PANEL_VIEW).performScrollTo().performClick()
        assertEquals(1, writes.size)
        assertTrue(writes[0].panelViewOn, "panel view switch must set panelViewOn = true")
    }

    @Test
    fun readingProgressSwitchWritesShowChapterMap() = runComposeUiTest {
        val writes = mutableListOf<ComicFormattingPreferences>()
        setContent {
            Column {
                ComicDisplaySettingsPanel(
                    ComicFormattingPreferences(showChapterMap = false),
                    onPrefsChange = { writes += it },
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(TestTags.SETTINGS_COMIC_DISPLAY_READING_PROGRESS).performScrollTo().performClick()
        assertEquals(1, writes.size)
        assertTrue(writes[0].showChapterMap, "reading progress switch must set showChapterMap = true")
    }

    @Test
    fun pageNumbersSwitchWritesShowPageProgress() = runComposeUiTest {
        val writes = mutableListOf<ComicFormattingPreferences>()
        setContent {
            Column {
                ComicDisplaySettingsPanel(
                    ComicFormattingPreferences(showChapterMap = true, showPageProgress = false),
                    onPrefsChange = { writes += it },
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(TestTags.SETTINGS_COMIC_DISPLAY_PAGE_NUMBERS).performScrollTo().performClick()
        assertEquals(1, writes.size)
        assertTrue(writes[0].showPageProgress, "page numbers switch must set showPageProgress = true")
    }

    @Test
    fun pageNumbersSwitchIsInertWhileReadingProgressIsOff() = runComposeUiTest {
        val writes = mutableListOf<ComicFormattingPreferences>()
        setContent {
            Column {
                ComicDisplaySettingsPanel(
                    ComicFormattingPreferences(showChapterMap = false, showPageProgress = false),
                    onPrefsChange = { writes += it },
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(TestTags.SETTINGS_COMIC_DISPLAY_PAGE_NUMBERS).performScrollTo().assertIsNotEnabled()
        assertEquals(emptyList(), writes)
    }

    @Test
    fun eachSwitchWritesOnlyItsOwnField() = runComposeUiTest {
        val writes = mutableListOf<ComicFormattingPreferences>()
        setContent {
            Column {
                ComicDisplaySettingsPanel(
                    ComicFormattingPreferences(
                        panelViewOn = false,
                        showChapterMap = false,
                    ),
                    onPrefsChange = { writes += it },
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(TestTags.SETTINGS_COMIC_DISPLAY_PANEL_VIEW).performScrollTo().performClick()
        onNodeWithTag(TestTags.SETTINGS_COMIC_DISPLAY_READING_PROGRESS).performScrollTo().performClick()

        assertEquals(2, writes.size)
        assertTrue(writes[0].panelViewOn, "first write must be panelViewOn")
        assertFalse(writes[0].showChapterMap, "first write must not change showChapterMap")
        assertTrue(writes[1].showChapterMap, "second write must be showChapterMap")
        assertFalse(writes[1].panelViewOn, "second write must not change panelViewOn")
    }

    @Test
    fun panelOverflowOptionsAreDisplayed() = runComposeUiTest {
        setContent {
            Column {
                ComicDisplaySettingsPanel(
                    ComicFormattingPreferences(panelViewOn = true),
                    onPrefsChange = {},
                    onDismiss = {},
                )
            }
        }
        onNodeWithText("No split").assertIsDisplayed()
        onNodeWithText("Split").assertIsDisplayed()
        onNodeWithText("Smart split").assertIsDisplayed()
    }
}
