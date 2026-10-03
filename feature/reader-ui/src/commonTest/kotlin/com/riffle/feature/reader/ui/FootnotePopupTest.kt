package com.riffle.feature.reader.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.reader.FootnoteContent
import com.riffle.feature.reader.FootnotePopupState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Shared footnote popup. Displays the note text and offers a close button.
 *
 * Both Android and iOS mount this exact composable — before the share, iOS had no footnote
 * surface at all.
 */
class FootnotePopupTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun footnoteTextIsDisplayed() = runComposeUiTest {
        setContent {
            FootnotePopup(
                state = FootnotePopupState(FootnoteContent("This is a footnote.")),
                onDismiss = {},
            )
        }
        onNodeWithText("This is a footnote.").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun popupSurfaceCarriesTestTag() = runComposeUiTest {
        setContent {
            FootnotePopup(
                state = FootnotePopupState(FootnoteContent("Footnote content")),
                onDismiss = {},
            )
        }
        onNodeWithTag(TestTags.READER_FOOTNOTE_POPUP).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun closeButtonFiresOnDismiss() = runComposeUiTest {
        var dismissed = 0
        setContent {
            FootnotePopup(
                state = FootnotePopupState(FootnoteContent("Footnote text")),
                onDismiss = { dismissed++ },
            )
        }
        onNodeWithTag(TestTags.READER_FOOTNOTE_CLOSE).performClick()
        assertEquals(1, dismissed)
    }
}
