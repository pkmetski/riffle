package com.riffle.feature.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.common.TimeRemaining
import com.riffle.core.domain.ReaderTheme
import com.riffle.feature.reader.RailSegment
import kotlin.test.Test

/**
 * The five On-Screen Info switches, driven through the real composable.
 *
 * This suite runs on `iosSimulatorArm64` as well as the JVM, so it is the coverage for what the
 * iOS reader actually draws — `IosEpubReaderScreen` mounts this exact composable. Before the
 * overlay was shared, iOS rendered none of it and the five Settings toggles were hidden.
 */
class ChapterMapOverlayRenderTest {

    private val segments = listOf(
        RailSegment("Prologue", "p.xhtml", weight = 10f),
        RailSegment("Chapter One", "one.xhtml", weight = 10f),
    )

    @OptIn(ExperimentalTestApi::class)
    private fun overlay(
        showRail: Boolean = false,
        showCurrentChapterLabel: Boolean = false,
        showProgressLabels: Boolean = false,
        showReadingTimeEstimate: Boolean = false,
        coloredChapterMap: Boolean = true,
        chapterTimeRemaining: TimeRemaining? = null,
        bookTimeRemaining: TimeRemaining? = null,
    ): @Composable () -> Unit = {
        ChapterMapOverlay(
            segments = segments,
            activeIndex = 1,
            cursorPosition = 0.75f,
            totalProgress = 0.75f,
            readerTheme = ReaderTheme.Light,
            showRail = showRail,
            coloredChapterMap = coloredChapterMap,
            showCurrentChapterLabel = showCurrentChapterLabel,
            showProgressLabels = showProgressLabels,
            showReadingTimeEstimate = showReadingTimeEstimate,
            templates = ChapterMapProgressLabelTemplates.English,
            chapterTimeRemaining = chapterTimeRemaining,
            bookTimeRemaining = bookTimeRemaining,
            onSegmentClick = {},
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun railRendersWhenTheChapterMapIsOn() = runComposeUiTest {
        setContent(overlay(showRail = true))
        onNodeWithTag("chapter_navigation_rail").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun nothingRendersWhenEveryOnScreenInfoSwitchIsOff() = runComposeUiTest {
        setContent(overlay())
        onNodeWithTag("chapter_navigation_rail").assertDoesNotExist()
        onNodeWithTag("reading_progress_labels").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun currentChapterLabelRendersTheActiveSegmentTitle() = runComposeUiTest {
        setContent(overlay(showCurrentChapterLabel = true))
        onNodeWithTag("reading_progress_chapter_name").assertIsDisplayed()
        onNodeWithText("Chapter One").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun progressLabelsRenderTheChapterCountAndPercentage() = runComposeUiTest {
        setContent(overlay(showProgressLabels = true))
        onNodeWithText("Chapter 2 of 2").assertIsDisplayed()
        onNodeWithText("75.0%").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun timeRemainingRendersBothReadoutsOnlyWhenItsSwitchIsOn() = runComposeUiTest {
        setContent(
            overlay(
                showReadingTimeEstimate = true,
                chapterTimeRemaining = TimeRemaining.Estimated(3_900),
                bookTimeRemaining = TimeRemaining.Estimated(7_500),
            ),
        )
        onNodeWithText("~1h 5min chapter").assertIsDisplayed()
        onNodeWithText("~2h 5min total").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun timeRemainingStaysHiddenWhileItsSwitchIsOffEvenWithEstimatesAvailable() = runComposeUiTest {
        setContent(
            overlay(
                showProgressLabels = true,
                chapterTimeRemaining = TimeRemaining.Estimated(3_900),
                bookTimeRemaining = TimeRemaining.Estimated(7_500),
            ),
        )
        onNodeWithTag("reading_progress_chapter_time").assertDoesNotExist()
        onNodeWithTag("reading_progress_book_time").assertDoesNotExist()
    }

    /**
     * The labels and the rail are independent: turning the chapter map off must not take the
     * readouts with it. This is the combination the shared `chapterMapVisible` gate exists for.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun labelsRenderWithTheRailTurnedOff() = runComposeUiTest {
        setContent(overlay(showRail = false, showProgressLabels = true))
        onNodeWithTag("reading_progress_labels").assertIsDisplayed()
        onNodeWithTag("chapter_navigation_rail").assertDoesNotExist()
    }
}
