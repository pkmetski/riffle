package com.riffle.app.feature.reader

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.SentenceQuote
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator

/**
 * Guards a regression where annotation highlights created in continuous mode disappeared after
 * switching to paginated/vertical (and vice versa). The persisted-highlight LaunchedEffect in
 * EpubReaderScreen is keyed on a set of values that don't necessarily change on an orientation
 * flip; the [HighlightRenderer] instance itself, however, is recreated on every flip
 * (Readium ↔ Continuous). Keying the effect on the renderer makes the fresh instance receive
 * the existing render list immediately, instead of waiting for the next pageLoad / reflow tick.
 */
@RunWith(AndroidJUnit4::class)
class HighlightRendererSwapReapplyTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun swappingRendererReappliesAnnotationsImmediately() {
        val first = CountingRenderer()
        val second = CountingRenderer()
        var active by mutableStateOf<HighlightRenderer>(first)
        val renders = emptyList<EpubReaderViewModel.HighlightRender>()

        rule.setContent {
            // Mirrors the production LaunchedEffect at EpubReaderScreen.kt:1676 — keying on the
            // renderer is what re-fires the effect when the renderer is recreated mid-session.
            LaunchedEffect(active, renders) {
                active.applyAnnotations(renders)
            }
        }
        rule.waitUntil(timeoutMillis = 2_000) { first.annotationApplies == 1 }
        assertEquals("initial composition applies once", 1, first.annotationApplies)
        assertEquals("the inactive renderer is not invoked", 0, second.annotationApplies)

        active = second

        rule.waitUntil(timeoutMillis = 2_000) { second.annotationApplies == 1 }
        assertEquals(
            "swapping the renderer must invoke applyAnnotations on the new instance — " +
                "without this the previously-applied highlights stay invisible until reopen",
            1,
            second.annotationApplies,
        )
        assertEquals("the old renderer is not invoked again on swap", 1, first.annotationApplies)
    }

    private class CountingRenderer : HighlightRenderer {
        var annotationApplies: Int = 0
            private set

        override suspend fun applySentenceHighlight(
            fragmentRef: String?,
            quotes: Map<String, SentenceQuote>,
            color: HighlightColor,
        ) = Unit

        override suspend fun applyAnnotations(
            renders: List<EpubReaderViewModel.HighlightRender>,
        ) {
            annotationApplies += 1
        }

        override suspend fun applyNoteGlyphs(renders: List<EpubReaderViewModel.HighlightRender>) =
            Unit

        override suspend fun applySearch(results: List<Locator>, activeIndex: Int) = Unit

        override fun highlightSearchMatch(href: String, text: String) = Unit
    }
}
