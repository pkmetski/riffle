package com.riffle.app.harness

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSemanticMatchersTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun noErrorStateMatchesNodeWithErrorTestTag() {
        composeTestRule.setContent {
            Column {
                Text("content", modifier = Modifier.testTag("reader_content"))
                Text("Something went wrong", modifier = Modifier.testTag(ReaderSemanticMatchers.TAG_ERROR_STATE))
            }
        }
        composeTestRule
            .onNodeWithTag(ReaderSemanticMatchers.TAG_ERROR_STATE)
            .assertExists()
    }

    @Test
    fun noErrorStateFindsNoErrorNodeWhenAbsent() {
        composeTestRule.setContent {
            Text("content", modifier = Modifier.testTag("reader_content"))
        }
        val errorNodes = composeTestRule
            .onAllNodesWithTag(ReaderSemanticMatchers.TAG_ERROR_STATE)
            .fetchSemanticsNodes()
        assert(errorNodes.isEmpty())
    }

    @Test
    fun readerReadyTagIsAccessibleWhenPresent() {
        composeTestRule.setContent {
            Text("reader view", modifier = Modifier.testTag(ReaderSemanticMatchers.TAG_READER_READY))
        }
        composeTestRule
            .onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY)
            .assertExists()
    }

    @Test
    fun textVisibleFindsNodeContainingText() {
        composeTestRule.setContent {
            Text("Test EPUB")
        }
        composeTestRule
            .onNodeWithText("Test EPUB")
            .assertExists()
    }
}
