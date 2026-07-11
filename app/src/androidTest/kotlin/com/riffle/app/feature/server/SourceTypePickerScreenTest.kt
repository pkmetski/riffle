package com.riffle.app.feature.server

import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
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
 * Instrumentation coverage for #435's SourceTypePickerScreen. Locks the "both cards enabled and
 * routable" contract now that #438 has landed the LocalFiles wiring; JVM-side data-model pinning
 * lives in [SourceTypePickerTest].
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
class SourceTypePickerScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun allCards_areDisplayed() {
        setContent()
        composeRule.onNodeWithText("Audiobookshelf").assertIsDisplayed()
        composeRule.onNodeWithText("Local files").assertIsDisplayed()
        composeRule.onNodeWithText("Chitanka").assertIsDisplayed()
        composeRule.onNodeWithText("Project Gutenberg").assertIsDisplayed()
    }

    @Test
    fun gutenbergCard_click_invokesCallback() {
        var gCount = 0
        setContent(onPickGutenberg = { gCount++ })
        composeRule.onNodeWithTag("SourceTypeCard.Gutenberg").assertHasClickAction().performClick()
        assertEquals(1, gCount)
    }

    @Test
    fun audiobookshelfCard_click_invokesCallback() {
        var absCount = 0
        setContent(onPickAbs = { absCount++ })
        composeRule.onNodeWithTag("SourceTypeCard.Audiobookshelf").assertHasClickAction().performClick()
        assertEquals(1, absCount)
    }

    @Test
    fun localFilesCard_click_invokesCallback() {
        var lfCount = 0
        setContent(onPickLocal = { lfCount++ })
        composeRule.onNodeWithTag("SourceTypeCard.LocalFiles").assertHasClickAction().performClick()
        assertEquals(1, lfCount)
    }

    @Test
    fun chitankaCard_click_invokesCallback() {
        var chCount = 0
        setContent(onPickChitanka = { chCount++ })
        composeRule.onNodeWithTag("SourceTypeCard.Chitanka").assertHasClickAction().performClick()
        assertEquals(1, chCount)
    }

    private fun setContent(
        onPickAbs: () -> Unit = {},
        onPickLocal: () -> Unit = {},
        onPickChitanka: () -> Unit = {},
        onPickGutenberg: () -> Unit = {},
    ) {
        composeRule.setContent {
            val wsc = calculateWindowSizeClass(composeRule.activity)
            SourceTypePickerScreen(
                windowSizeClass = wsc,
                onNavigateBack = {},
                onPickAudiobookshelf = onPickAbs,
                onPickLocalFiles = onPickLocal,
                onPickChitanka = onPickChitanka,
                onPickGutenberg = onPickGutenberg,
            )
        }
    }
}
