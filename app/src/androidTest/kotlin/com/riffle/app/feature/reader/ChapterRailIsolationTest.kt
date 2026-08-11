package com.riffle.app.feature.reader

import android.graphics.Bitmap
import android.os.Handler
import android.view.PixelCopy
import android.view.Surface
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.domain.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// Regression: cursor-position changes must recompose only the rail overlay, not sibling EpubNavigatorView.
@RunWith(AndroidJUnit4::class)
class ChapterRailIsolationTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun groupedRailRendersFourDpProgressWithParentChapterColors() {
        composeTestRule.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White),
            ) {
                ChapterNavigationRail(
                    segments = listOf(
                        RailSegment("One A", "one.xhtml#a", groupIndex = 0),
                        RailSegment("One B", "one.xhtml#b", groupIndex = 0),
                        RailSegment("Two A", "two.xhtml#a", groupIndex = 1),
                        RailSegment("Two B", "two.xhtml#b", groupIndex = 1),
                    ),
                    activeIndex = 0,
                    cursorPosition = 0.5f,
                    readerTheme = ReaderTheme.Light,
                    onSegmentClick = {},
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        composeTestRule.waitForIdle()

        val railHeight = composeTestRule
            .onNodeWithTag("chapter_navigation_rail")
            .fetchSemanticsNode()
            .boundsInRoot
            .height
        val expectedHeight = with(composeTestRule.density) { 4.dp.toPx() }
        assertEquals(expectedHeight, railHeight, 0.5f)

        val screen = captureWindowToBitmap(composeTestRule.activity.window)
        var vividOrangePixels = 0
        var mutedUnreadBluePixels = 0
        for (y in 0 until screen.height) {
            for (x in 0 until screen.width) {
                val pixel = screen.getPixel(x, y)
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                if (red in 225..235 && green in 90..105 && blue in 0..8) {
                    vividOrangePixels++
                }
                if (isMutedUnreadBluePixel(red, green, blue)) {
                    mutedUnreadBluePixels++
                }
            }
        }
        assertTrue(
            "Expected sibling sections from chapter one to render in vivid orange",
            vividOrangePixels >= 100,
        )
        assertTrue(
            "Expected unread sibling sections from chapter two to remain blue but visibly muted",
            mutedUnreadBluePixels >= 100,
        )
    }

    @Test
    fun groupedRailRendersNeutralProgressWhenColorsAreDisabled() {
        composeTestRule.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White),
            ) {
                ChapterNavigationRail(
                    segments = listOf(
                        RailSegment("One", "one.xhtml", groupIndex = 0),
                        RailSegment("Two", "two.xhtml", groupIndex = 1),
                    ),
                    activeIndex = 0,
                    cursorPosition = 0.5f,
                    readerTheme = ReaderTheme.Light,
                    onSegmentClick = {},
                    coloredChapterMap = false,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        composeTestRule.waitForIdle()

        val screen = captureWindowToBitmap(composeTestRule.activity.window)
        var palettePixels = 0
        for (y in 0 until screen.height) {
            for (x in 0 until screen.width) {
                val pixel = screen.getPixel(x, y)
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                val vividOrange = red in 225..235 && green in 90..105 && blue in 0..8
                val mutedBlue = isMutedUnreadBluePixel(red, green, blue)
                if (vividOrange || mutedBlue) palettePixels++
            }
        }

        assertTrue(
            "Disabling Colored chapter map must remove parent-chapter palette colors",
            palettePixels < 10,
        )
    }

    @Test
    fun cursorPositionChangeRecomposesOnlyRailNotSibling() {
        val cursorFlow = MutableStateFlow(0f)
        var siblingRecomposeCount = 0
        var railRecomposeCount = 0

        composeTestRule.setContent {
            Box(Modifier.fillMaxSize()) {
                // Simulates EpubNavigatorView — must NOT recompose when cursor position changes.
                Box(Modifier.fillMaxSize()) {
                    SideEffect { siblingRecomposeCount++ }
                }
                // Rail overlay with cursor state isolated inside.
                IsolatedRailForTest(
                    cursorFlow = cursorFlow,
                    onRecompose = { railRecomposeCount++ },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        composeTestRule.waitForIdle()
        val siblingCountAfterInit = siblingRecomposeCount
        val railCountAfterInit = railRecomposeCount

        repeat(5) { i ->
            composeTestRule.runOnIdle { cursorFlow.value = 0.1f * (i + 1) }
        }
        composeTestRule.waitForIdle()

        // Rail must have recomposed for each cursor change.
        assertEquals(railCountAfterInit + 5, railRecomposeCount)
        // Sibling must NOT have recomposed at all after initial render.
        assertEquals(siblingCountAfterInit, siblingRecomposeCount)
    }

    @Test
    fun siblingDoesNotRecomposeWhenRailCursorUpdatesRepeatedly() {
        val cursorFlow = MutableStateFlow(0f)
        var siblingRecomposeCount = 0

        composeTestRule.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    SideEffect { siblingRecomposeCount++ }
                }
                IsolatedRailForTest(
                    cursorFlow = cursorFlow,
                    onRecompose = {},
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        composeTestRule.waitForIdle()
        val countAfterInit = siblingRecomposeCount

        // Simulate 20 scroll events at varying positions.
        repeat(20) { i ->
            composeTestRule.runOnIdle { cursorFlow.value = (i + 1) / 20f }
        }
        composeTestRule.waitForIdle()

        assertEquals(
            "Sibling recomposed ${siblingRecomposeCount - countAfterInit} extra time(s) due to rail cursor updates",
            countAfterInit,
            siblingRecomposeCount,
        )
    }

    // Unread siblings render the parent-chapter hue at CHAPTER_RAIL_UNREAD_ALPHA composited
    // over the white test background. Derived from the production constants so a deliberate
    // alpha change updates this expectation in lockstep (the JVM test pins the alpha value).
    private fun isMutedUnreadBluePixel(red: Int, green: Int, blue: Int): Boolean {
        val expected = chapterRailUnreadGroupColor(groupIndex = 1).compositeOver(Color.White)
        val er = (expected.red * 255).roundToInt()
        val eg = (expected.green * 255).roundToInt()
        val eb = (expected.blue * 255).roundToInt()
        return red in (er - 6)..(er + 6) && green in (eg - 7)..(eg + 7) && blue in (eb - 7)..(eb + 7)
    }

    private fun captureWindowToBitmap(window: Window): Bitmap {
        val decor = window.decorView
        val viewRoot = decor.parent
        val surfaceField = viewRoot.javaClass.getDeclaredField("mSurface").apply { isAccessible = true }
        val surface = surfaceField.get(viewRoot) as Surface
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        val result = intArrayOf(-1)
        val handlerThread = android.os.HandlerThread("ChapterRailPixelCopy").apply { start() }
        try {
            PixelCopy.request(
                surface,
                bitmap,
                { code ->
                    result[0] = code
                    latch.countDown()
                },
                Handler(handlerThread.looper),
            )
            assertTrue("PixelCopy timed out", latch.await(5, TimeUnit.SECONDS))
            assertEquals("PixelCopy result was not SUCCESS", PixelCopy.SUCCESS, result[0])
        } finally {
            handlerThread.quitSafely()
        }
        return bitmap
    }
}

@Composable
private fun IsolatedRailForTest(
    cursorFlow: StateFlow<Float>,
    onRecompose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cursor by cursorFlow.collectAsState()
    SideEffect { onRecompose() }
    ChapterNavigationRail(
        segments = listOf(RailSegment(title = "Chapter 1", href = "ch1.xhtml")),
        activeIndex = 0,
        cursorPosition = cursor,
        readerTheme = ReaderTheme.Light,
        onSegmentClick = {},
        modifier = modifier,
    )
}
