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
 * Return-to-position card shown after an internal-link jump. The "Back" body navigates back
 * to the origin; the ✕ dismisses without navigating.
 */
class ReturnToPositionCardTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun cardCarriesTestTag() = runComposeUiTest {
        setContent {
            ReturnToPositionCard(onReturn = {}, onDismiss = {})
        }
        onNodeWithTag(TestTags.READER_RETURN_CARD).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun backBodyFiresOnReturn() = runComposeUiTest {
        var returned = 0
        setContent {
            ReturnToPositionCard(onReturn = { returned++ }, onDismiss = {})
        }
        onNodeWithTag(TestTags.READER_RETURN_BACK).performClick()
        assertEquals(1, returned)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun dismissButtonFiresOnDismiss() = runComposeUiTest {
        var dismissed = 0
        setContent {
            ReturnToPositionCard(onReturn = {}, onDismiss = { dismissed++ })
        }
        onNodeWithTag(TestTags.READER_RETURN_DISMISS).performClick()
        assertEquals(1, dismissed)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun backAndDismissAreIndependent() = runComposeUiTest {
        // Regression pin: tapping ✕ must never trigger onReturn, and vice versa.
        var returned = 0
        var dismissed = 0
        setContent {
            ReturnToPositionCard(onReturn = { returned++ }, onDismiss = { dismissed++ })
        }
        onNodeWithTag(TestTags.READER_RETURN_DISMISS).performClick()
        assertEquals(0, returned)
        assertEquals(1, dismissed)

        onNodeWithTag(TestTags.READER_RETURN_BACK).performClick()
        assertEquals(1, returned)
        assertEquals(1, dismissed)
    }
}
