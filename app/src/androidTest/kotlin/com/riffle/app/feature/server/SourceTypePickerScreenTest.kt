package com.riffle.app.feature.server

import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.models.SourceType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation coverage for the SourceTypePickerScreen. Post-ADR-0044 the picker delivers a
 * single `onPick(SourceType)` callback; card taps identify by [SourceType]. Locks the "cards are
 * displayed and clickable" contract.
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
    fun gutenbergCard_click_invokesCallbackWithGutenbergType() {
        val picked = mutableListOf<SourceType>()
        setContent(onPick = { picked += it })
        composeRule.onNodeWithTag("SourceTypeCard.GUTENBERG").assertHasClickAction().performClick()
        assertEquals(listOf(SourceType.GUTENBERG), picked)
    }

    @Test
    fun audiobookshelfCard_click_invokesCallbackWithAbsType() {
        val picked = mutableListOf<SourceType>()
        setContent(onPick = { picked += it })
        composeRule.onNodeWithTag("SourceTypeCard.ABS").assertHasClickAction().performClick()
        assertEquals(listOf(SourceType.ABS), picked)
    }

    @Test
    fun localFilesCard_click_invokesCallbackWithLocalFilesType() {
        val picked = mutableListOf<SourceType>()
        setContent(onPick = { picked += it })
        composeRule.onNodeWithTag("SourceTypeCard.LOCAL_FILES").assertHasClickAction().performClick()
        assertEquals(listOf(SourceType.LOCAL_FILES), picked)
    }

    @Test
    fun chitankaCard_click_invokesCallbackWithChitankaType() {
        val picked = mutableListOf<SourceType>()
        setContent(onPick = { picked += it })
        composeRule.onNodeWithTag("SourceTypeCard.CHITANKA").assertHasClickAction().performClick()
        assertEquals(listOf(SourceType.CHITANKA), picked)
    }

    private fun setContent(
        onPick: (SourceType) -> Unit = {},
        installedTypes: Set<SourceType> = emptySet(),
    ) {
        composeRule.setContent {
            val wsc = calculateWindowSizeClass(composeRule.activity)
            SourceTypePickerScreen(
                windowSizeClass = wsc,
                onNavigateBack = {},
                onPick = onPick,
                installedTypes = installedTypes,
            )
        }
    }
}
