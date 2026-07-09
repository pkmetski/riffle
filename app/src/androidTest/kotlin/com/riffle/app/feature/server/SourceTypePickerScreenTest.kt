package com.riffle.app.feature.server

import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation coverage for #435's SourceTypePickerScreen. Locks the ABS-enabled +
 * LocalFiles-disabled contract at the composable level; JVM-side data-model pinning
 * lives in [SourceTypePickerTest].
 */
@RunWith(AndroidJUnit4::class)
class SourceTypePickerScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bothCards_areDisplayed() {
        setContent()
        composeRule.onNodeWithText("Audiobookshelf").assertIsDisplayed()
        composeRule.onNodeWithText("Local files").assertIsDisplayed()
    }

    @Test
    fun audiobookshelfCard_click_invokesCallback() {
        var pickCount = 0
        setContent(onPick = { pickCount++ })
        composeRule.onNodeWithTag("SourceTypeCard.Audiobookshelf").assertHasClickAction().performClick()
        assertEquals(1, pickCount)
    }

    @Test
    fun localFilesCard_isDisabled_showsComingSoon() {
        setContent()
        composeRule.onNodeWithText("Coming soon").assertIsDisplayed()
        composeRule.onNodeWithTag("SourceTypeCard.LocalFiles").assertHasNoClickAction()
    }

    private fun setContent(onPick: () -> Unit = {}) {
        composeRule.setContent {
            val wsc = calculateWindowSizeClass(composeRule.activity)
            SourceTypePickerScreen(
                windowSizeClass = wsc,
                onNavigateBack = {},
                onPickAudiobookshelf = onPick,
            )
        }
    }
}
