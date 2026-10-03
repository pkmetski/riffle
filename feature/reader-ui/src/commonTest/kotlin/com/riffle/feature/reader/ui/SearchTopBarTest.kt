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
 * Shared search top bar. Query field, result count ("n of N" / "No results" / blank), and
 * prev/next/close navigation buttons.
 */
class SearchTopBarTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun closeButtonFiresOnClose() = runComposeUiTest {
        var closed = 0
        setContent {
            SharedSearchTopBar(
                query = "epubcfi",
                resultCount = 3,
                currentIndex = 0,
                onQueryChange = {},
                onPrev = {},
                onNext = {},
                onClose = { closed++ },
                onNavigateBack = {},
            )
        }
        onNodeWithTag(TestTags.READER_SEARCH_CLOSE).performClick()
        assertEquals(1, closed)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun nextButtonFiresOnNext() = runComposeUiTest {
        var nexts = 0
        setContent {
            SharedSearchTopBar(
                query = "chapter",
                resultCount = 5,
                currentIndex = 0,
                onQueryChange = {},
                onPrev = {},
                onNext = { nexts++ },
                onClose = {},
                onNavigateBack = {},
            )
        }
        onNodeWithTag(TestTags.READER_SEARCH_NEXT).performClick()
        assertEquals(1, nexts)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun prevButtonFiresOnPrev() = runComposeUiTest {
        var prevs = 0
        setContent {
            SharedSearchTopBar(
                query = "chapter",
                resultCount = 5,
                currentIndex = 2,
                onQueryChange = {},
                onPrev = { prevs++ },
                onNext = {},
                onClose = {},
                onNavigateBack = {},
            )
        }
        onNodeWithTag(TestTags.READER_SEARCH_PREV).performClick()
        assertEquals(1, prevs)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun backButtonFiresOnNavigateBack() = runComposeUiTest {
        var backs = 0
        setContent {
            SharedSearchTopBar(
                query = "",
                resultCount = 0,
                currentIndex = 0,
                onQueryChange = {},
                onPrev = {},
                onNext = {},
                onClose = {},
                onNavigateBack = { backs++ },
            )
        }
        onNodeWithTag(TestTags.READER_BACK).performClick()
        assertEquals(1, backs)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun searchFieldIsAlwaysPresent() = runComposeUiTest {
        setContent {
            SharedSearchTopBar(
                query = "",
                resultCount = 0,
                currentIndex = 0,
                onQueryChange = {},
                onPrev = {},
                onNext = {},
                onClose = {},
                onNavigateBack = {},
            )
        }
        onNodeWithTag(TestTags.READER_SEARCH_FIELD).assertIsDisplayed()
    }
}
