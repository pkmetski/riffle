package com.riffle.feature.reader.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.feature.reader.FigureZoomState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Shared FigureZoomOverlay — gesture logic and content dispatch.
 *
 * The overlay routes image vs. SVG through platform-supplied lambdas; the shared part is the
 * dispatch decision and the AnimatedVisibility show/hide. Both Android and iOS mount the same
 * composable, injecting their own image/SVG rendering via the lambda slots.
 */
@OptIn(ExperimentalTestApi::class)
class FigureZoomOverlayTest {

    @Test
    fun overlayIsNotVisibleWhenStateIsNull() = runComposeUiTest {
        setContent {
            FigureZoomOverlay(
                state = null,
                onDismiss = {},
                imageContent = { _, _ -> Text("image") },
                svgContent = { _, _ -> Text("svg") },
            )
        }
        onNodeWithText("image").assertDoesNotExist()
        onNodeWithText("svg").assertDoesNotExist()
    }

    @Test
    fun imageContentIsInvokedForRasterState() = runComposeUiTest {
        var imageCallCount = 0
        setContent {
            FigureZoomOverlay(
                state = FigureZoomState(href = "cover.jpg", naturalWidth = 800, naturalHeight = 600),
                onDismiss = {},
                imageContent = { _, imgModifier ->
                    imageCallCount++
                    Text("image-content", modifier = imgModifier)
                },
                svgContent = { _, imgModifier ->
                    Text("svg-content", modifier = imgModifier)
                },
            )
        }
        onNodeWithText("image-content").assertIsDisplayed()
        assertEquals(true, imageCallCount > 0, "imageContent lambda must be invoked for raster state")
    }

    @Test
    fun svgContentIsInvokedForSvgState() = runComposeUiTest {
        var svgCallCount = 0
        setContent {
            FigureZoomOverlay(
                state = FigureZoomState(
                    href = "",
                    naturalWidth = 400,
                    naturalHeight = 300,
                    svgMarkup = "<svg><rect width='400' height='300'/></svg>",
                ),
                onDismiss = {},
                imageContent = { _, imgModifier ->
                    Text("image-content", modifier = imgModifier)
                },
                svgContent = { _, imgModifier ->
                    svgCallCount++
                    Text("svg-content", modifier = imgModifier)
                },
            )
        }
        onNodeWithText("svg-content").assertIsDisplayed()
        assertEquals(true, svgCallCount > 0, "svgContent lambda must be invoked for SVG state")
    }

    @Test
    fun overlayBecomesVisibleWhenStateTransitionsFromNull() = runComposeUiTest {
        var state by mutableStateOf<FigureZoomState?>(null)
        setContent {
            FigureZoomOverlay(
                state = state,
                onDismiss = {},
                imageContent = { _, _ -> Text("visible-image") },
                svgContent = { _, _ -> Text("visible-svg") },
            )
        }
        onNodeWithText("visible-image").assertDoesNotExist()
        state = FigureZoomState(href = "photo.png", naturalWidth = 600, naturalHeight = 400)
        waitForIdle()
        onNodeWithText("visible-image").assertIsDisplayed()
    }
}
