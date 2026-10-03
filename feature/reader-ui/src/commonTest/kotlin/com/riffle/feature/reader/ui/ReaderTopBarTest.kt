package com.riffle.feature.reader.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.feature.designsystem.TestTags
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Shared reader top bar chrome. The back button, search, TOC, annotations, and format actions
 * are all available when the navigator is ready. In immersive mode (visible = false) the bar
 * slides away; core actions are absent from the tree.
 */
class ReaderTopBarTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun backButtonFiresCallback() = runComposeUiTest {
        var taps = 0
        setContent {
            ReaderTopBar(
                visible = true,
                title = "Chapter 1",
                isReady = true,
                onBack = { taps++ },
                onSearch = {},
                onToc = {},
                onAnnotations = {},
                onFormat = {},
            )
        }
        onNodeWithTag(TestTags.READER_BACK).performClick()
        assertEquals(1, taps)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun coreActionsVisibleWhenReady() = runComposeUiTest {
        setContent {
            ReaderTopBar(
                visible = true,
                title = "Chapter 1",
                isReady = true,
                onBack = {},
                onSearch = {},
                onToc = {},
                onAnnotations = {},
                onFormat = {},
            )
        }
        onNodeWithTag(TestTags.READER_SEARCH).assertIsDisplayed()
        onNodeWithTag(TestTags.READER_TOC).assertIsDisplayed()
        onNodeWithTag(TestTags.READER_ANNOTATIONS).assertIsDisplayed()
        onNodeWithTag(TestTags.READER_SETTINGS).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun coreActionsHiddenWhenNotReady() = runComposeUiTest {
        setContent {
            ReaderTopBar(
                visible = true,
                title = "Loading…",
                isReady = false,
                onBack = {},
                onSearch = {},
                onToc = {},
                onAnnotations = {},
                onFormat = {},
            )
        }
        onNodeWithTag(TestTags.READER_SEARCH).assertDoesNotExist()
        onNodeWithTag(TestTags.READER_TOC).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun formatButtonFiresCallback() = runComposeUiTest {
        var taps = 0
        setContent {
            ReaderTopBar(
                visible = true,
                title = "Chapter 1",
                isReady = true,
                onBack = {},
                onSearch = {},
                onToc = {},
                onAnnotations = {},
                onFormat = { taps++ },
            )
        }
        onNodeWithTag(TestTags.READER_SETTINGS).performClick()
        assertEquals(1, taps)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun annotationsButtonFiresCallback() = runComposeUiTest {
        var taps = 0
        setContent {
            ReaderTopBar(
                visible = true,
                title = "Chapter 1",
                isReady = true,
                onBack = {},
                onSearch = {},
                onToc = {},
                onAnnotations = { taps++ },
                onFormat = {},
            )
        }
        onNodeWithTag(TestTags.READER_ANNOTATIONS).performClick()
        assertEquals(1, taps)
    }
}
