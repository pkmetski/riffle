package com.riffle.app.feature.reader.presenter

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [ReaderPresenter] contract via [FakeReaderPresenter]. Every future orchestrator
 * (PositionOrchestrator, ReadaloudSession, AnnotationSession, …) will lean on this double as
 * its renderer test seam — so anything they need to trust about the contract has to be proved
 * here.
 */
class FakeReaderPresenterTest {

    private val defaultPrefs = FormattingPreferences(
        theme = ReaderTheme.Light,
        fontFamily = ReaderFontFamily.Serif,
        fontSize = 1.0f,
        lineSpacing = 1.2f,
        margins = 1.0f,
        orientation = ReaderOrientation.Horizontal,
    )

    @Test
    fun `snapshotPosition returns null before any position event`() {
        val presenter = FakeReaderPresenter()
        assertNull(presenter.snapshotPosition())
    }

    @Test
    fun `snapshotPosition returns the most recent emitted position`() = runTest {
        val presenter = FakeReaderPresenter()
        presenter.emitPosition(position(href = "ch01.xhtml", progression = 0.2f))
        presenter.emitPosition(position(href = "ch02.xhtml", progression = 0.5f))
        assertEquals("ch02.xhtml", presenter.snapshotPosition()?.href)
        assertEquals(0.5f, presenter.snapshotPosition()?.progression)
    }

    @Test
    fun `positionEvents carries a monotonically increasing generation`() = runTest {
        val presenter = FakeReaderPresenter()
        val collected = collectN(presenter.positionEvents, n = 3) {
            presenter.emitPosition(position(progression = 0.1f))
            presenter.emitPosition(position(progression = 0.2f))
            presenter.emitPosition(position(progression = 0.3f))
        }
        assertEquals(listOf(1L, 2L, 3L), collected.map { it.generation })
        assertEquals(listOf(0.1f, 0.2f, 0.3f), collected.map { it.position.progression })
    }

    @Test
    fun `pageLoadEvents are observable`() = runTest {
        val presenter = FakeReaderPresenter()
        val collected = collectN(presenter.pageLoadEvents, n = 2) {
            presenter.emitPageLoad(1)
            presenter.emitPageLoad(2)
        }
        assertEquals(listOf(1, 2), collected.map { it.value })
    }

    @Test
    fun `navigateTo records the target verbatim`() = runTest {
        val presenter = FakeReaderPresenter()
        presenter.navigateTo(NavigationTarget.ToHref("ch03.xhtml", fragment = "sec2"))
        presenter.navigateTo(NavigationTarget.ToProgression("ch03.xhtml", 0.42f))
        presenter.navigateTo(NavigationTarget.ToLocatorJson("""{"href":"ch01.xhtml"}"""))

        assertEquals(3, presenter.recordedNavigations.size)
        assertEquals(NavigationTarget.ToHref("ch03.xhtml", fragment = "sec2"), presenter.recordedNavigations[0].first)
        assertEquals(NavigationTarget.ToProgression("ch03.xhtml", 0.42f), presenter.recordedNavigations[1].first)
        assertTrue(presenter.recordedNavigations[2].first is NavigationTarget.ToLocatorJson)
    }

    @Test
    fun `applyTypography records the prefs in order`() = runTest {
        val presenter = FakeReaderPresenter()
        presenter.applyTypography(defaultPrefs)
        presenter.applyTypography(defaultPrefs.copy(fontSize = 1.2f))
        assertEquals(listOf(1.0f, 1.2f), presenter.recordedTypography.map { it.fontSize })
    }

    @Test
    fun `navigateTo records the options alongside the target`() = runTest {
        val presenter = FakeReaderPresenter()
        presenter.navigateTo(NavigationTarget.ToHref("ch01.xhtml"))
        presenter.navigateTo(
            NavigationTarget.ToProgression("ch02.xhtml", 0.5f),
            NavigationOptions(snap = false, alignToTop = true),
        )

        assertEquals(2, presenter.recordedNavigations.size)
        assertEquals(NavigationOptions(), presenter.recordedNavigations[0].second)
        assertEquals(
            NavigationOptions(snap = false, alignToTop = true),
            presenter.recordedNavigations[1].second,
        )
    }

    @Test
    fun `followReadaloudSentence records the text and returns the configured outcome`() = runTest {
        val presenter = FakeReaderPresenter()
        // Default is Unavailable — matches vertical/continuous behaviour, the safe default for
        // orchestrators that haven't pinned a result.
        assertEquals(ReadaloudFollowResult.Unavailable, presenter.followReadaloudSentence("a"))
        presenter.followReadaloudResult = ReadaloudFollowResult.OffPage
        assertEquals(ReadaloudFollowResult.OffPage, presenter.followReadaloudSentence("b"))
        assertEquals(listOf("a", "b"), presenter.recordedFollowReadaloud)
    }

    @Test
    fun `measureReadaloudColumns records the text and returns the configured columns`() = runTest {
        val presenter = FakeReaderPresenter()
        assertTrue(presenter.measureReadaloudColumns("a").isEmpty())
        presenter.measureReadaloudColumnsResult = listOf(0.6, 1.0)
        assertEquals(listOf(0.6, 1.0), presenter.measureReadaloudColumns("b"))
        assertEquals(listOf("a", "b"), presenter.recordedMeasureReadaloud)
    }

    @Test
    fun `snapReadaloudColumn records text-and-column pairs in order`() = runTest {
        val presenter = FakeReaderPresenter()
        presenter.snapReadaloudColumn("one", 0)
        presenter.snapReadaloudColumn("two", 2)
        assertEquals(listOf("one" to 0, "two" to 2), presenter.recordedSnapReadaloud)
    }

    @Test
    fun `scrollBoundary returns the configured snapshot`() = runTest {
        val presenter = FakeReaderPresenter()
        // Default — matches paginated mode and the safe default for tests.
        assertEquals(ScrollBoundary.None, presenter.scrollBoundary())
        presenter.scrollBoundaryResult = ScrollBoundary(atForwardBoundary = true, atBackwardBoundary = false)
        assertEquals(
            ScrollBoundary(atForwardBoundary = true, atBackwardBoundary = false),
            presenter.scrollBoundary(),
        )
    }

    @Test
    fun `pageBy records the direction`() = runTest {
        val presenter = FakeReaderPresenter()
        presenter.pageBy(PageDirection.Forward)
        presenter.pageBy(PageDirection.Forward)
        presenter.pageBy(PageDirection.Backward)
        assertEquals(
            listOf(PageDirection.Forward, PageDirection.Forward, PageDirection.Backward),
            presenter.recordedPagesBy,
        )
    }

    @Test
    fun `tap events flow`() = runTest {
        val presenter = FakeReaderPresenter()
        val taps = collectN(presenter.tapEvents, n = 1) { presenter.emitTap() }
        assertEquals(listOf(TapEvent.Body), taps)
    }

    @Test
    fun `link events carry their variant payload`() = runTest {
        val presenter = FakeReaderPresenter()
        val links = collectN(presenter.linkEvents, n = 3) {
            presenter.emitLink(LinkEvent.InternalLink("ch04.xhtml", originLocatorJson = "{}"))
            presenter.emitLink(LinkEvent.ExternalLink("https://example.org"))
            presenter.emitLink(LinkEvent.Footnote("<p>note</p>"))
        }
        assertEquals(3, links.size)
        assertTrue(links[0] is LinkEvent.InternalLink)
        assertTrue(links[1] is LinkEvent.ExternalLink)
        assertTrue(links[2] is LinkEvent.Footnote)
        assertEquals("ch04.xhtml", (links[0] as LinkEvent.InternalLink).href)
        assertEquals("https://example.org", (links[1] as LinkEvent.ExternalLink).url)
        assertEquals("<p>note</p>", (links[2] as LinkEvent.Footnote).contentHtml)
    }

    @Test
    fun `selection events flow`() = runTest {
        val presenter = FakeReaderPresenter()
        val selections = collectN(presenter.selectionEvents, n = 2) {
            presenter.emitSelection(
                SelectionEvent.HighlightRequest(
                    href = "ch01.xhtml",
                    text = "hello",
                    progression = 0.1f,
                    before = null,
                    after = null,
                ),
            )
            presenter.emitSelection(SelectionEvent.PlayFromHereRequest("ch01.xhtml", "hello"))
        }
        assertEquals(2, selections.size)
        assertTrue(selections[0] is SelectionEvent.HighlightRequest)
        assertTrue(selections[1] is SelectionEvent.PlayFromHereRequest)
    }

    @Test
    fun `annotation tap events distinguish highlight and note glyph`() = runTest {
        val presenter = FakeReaderPresenter()
        val taps = collectN(presenter.annotationTapEvents, n = 2) {
            presenter.emitAnnotationTap(AnnotationTapEvent.Highlight("ch01.xhtml", "annot-1"))
            presenter.emitAnnotationTap(AnnotationTapEvent.NoteGlyph("ch01.xhtml", "annot-1"))
        }
        assertEquals(
            listOf<AnnotationTapEvent>(
                AnnotationTapEvent.Highlight("ch01.xhtml", "annot-1"),
                AnnotationTapEvent.NoteGlyph("ch01.xhtml", "annot-1"),
            ),
            taps,
        )
    }

    // ----- helpers -----

    private fun position(
        href: String = "ch01.xhtml",
        progression: Float = 0.0f,
        totalProgression: Float? = null,
        locatorJson: String = "",
    ) = ReaderPosition(href, progression, totalProgression, locatorJson)

    /**
     * Subscribes to [flow] before invoking [produce], then waits for exactly [n] emissions.
     * Uses UNDISPATCHED start so the collector wins the race against the producer on shared
     * flows that have no replay buffer.
     */
    private suspend fun <T> TestScope.collectN(
        flow: Flow<T>,
        n: Int,
        produce: suspend () -> Unit,
    ): List<T> {
        val deferred = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            flow.take(n).toList()
        }
        yield()
        produce()
        return deferred.await()
    }
}
