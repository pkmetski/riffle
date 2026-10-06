package com.riffle.feature.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.comic.panel.PagePanels
import com.riffle.core.domain.comic.panel.PanelRegion
import com.riffle.core.domain.comic.panel.PanelSource
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.reader.VolumeNavEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Shared tests for [CbzPanelViewer] — runs on `iosSimulatorArm64` and the Android JVM test host.
 *
 * The component renders via [TestTags.CBZ_PANEL_VIEWER]. When `pagePanels` is `null` (detector
 * still in flight) it shows a spinner and withholds the `pageContent` slot. When panels are
 * available it renders the slot and omits the spinner.
 */
class CbzPanelViewerTest {

    private fun singlePanelPage() = PagePanels(
        pageIndex = 0,
        imageWidth = 800,
        imageHeight = 1200,
        panels = listOf(PanelRegion(x = 0, y = 0, width = 800, height = 600)),
        source = PanelSource.Auto,
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun panelViewerTagIsPresentWhenPanelsAreKnown() = runComposeUiTest {
        setContent {
            Box(Modifier.size(400.dp)) {
                CbzPanelViewer(
                    currentPage = 0,
                    pagePanels = singlePanelPage(),
                    panelIndex = 0,
                    panelAnimationSpeedMs = 0,
                    onNextPanel = {},
                    onPrevPanel = {},
                    onSkipGuidedPage = {},
                    onToggleImmersive = {},
                    volumeNavEvents = MutableSharedFlow(),
                ) { modifier, _ ->
                    Box(modifier.fillMaxSize())
                }
            }
        }
        onNodeWithTag(TestTags.CBZ_PANEL_VIEWER).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun panelViewerTagIsPresentDuringDetection() = runComposeUiTest {
        // pagePanels == null → detector still in flight → spinner rendered, no peek overlay.
        setContent {
            Box(Modifier.size(400.dp)) {
                CbzPanelViewer(
                    currentPage = 0,
                    pagePanels = null,
                    panelIndex = 0,
                    panelAnimationSpeedMs = 0,
                    onNextPanel = {},
                    onPrevPanel = {},
                    onSkipGuidedPage = {},
                    onToggleImmersive = {},
                    volumeNavEvents = MutableSharedFlow(),
                ) { modifier, _ ->
                    Box(modifier.fillMaxSize())
                }
            }
        }
        onNodeWithTag(TestTags.CBZ_PANEL_VIEWER).assertIsDisplayed()
        // Peek overlay must not be present in the loading/detection state.
        onNodeWithTag(TestTags.CBZ_PANEL_PEEK).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun panelViewerFallbackPageRendersContentSlotWithoutPeek() = runComposeUiTest {
        // A Fallback PagePanels (single whole-page region, isFallback = true) must render
        // the content slot at Identity transform and must not show a peek overlay.
        val fallback = PagePanels(
            pageIndex = 0,
            imageWidth = 800,
            imageHeight = 1200,
            panels = listOf(PanelRegion(x = 0, y = 0, width = 800, height = 1200)),
            source = PanelSource.Fallback,
        )
        var contentSlotRendered = false
        setContent {
            Box(Modifier.size(400.dp)) {
                CbzPanelViewer(
                    currentPage = 0,
                    pagePanels = fallback,
                    panelIndex = 0,
                    panelAnimationSpeedMs = 0,
                    onNextPanel = {},
                    onPrevPanel = {},
                    onSkipGuidedPage = {},
                    onToggleImmersive = {},
                    volumeNavEvents = MutableSharedFlow(),
                ) { modifier, _ ->
                    contentSlotRendered = true
                    Box(modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()
        assertTrue(contentSlotRendered, "content slot must be rendered for a fallback page")
        onNodeWithTag(TestTags.CBZ_PANEL_PEEK).assertDoesNotExist()
    }
}
