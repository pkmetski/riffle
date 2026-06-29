package com.riffle.app.feature.reader.presenter

import com.riffle.app.feature.reader.ContinuousNavigationView
import com.riffle.core.domain.FormattingPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ContinuousPresenter]. The Android view is stubbed via the narrow
 * [ContinuousNavigationView] interface so these tests run without Robolectric.
 *
 * Coverage focuses on the [NavigationTarget.ToLocatorJson] branch — the case
 * [ContinuousPresenter] silently no-op'd until issue #320. The other two branches
 * ([NavigationTarget.ToHref], [NavigationTarget.ToProgression]) are exercised in instrumentation
 * via the existing TOC + chapter-map paths.
 */
class ContinuousPresenterTest {

    private class FakeView : ContinuousNavigationView {
        data class NavCall(val href: String, val progression: Float, val alignToTop: Boolean)
        val navCalls: MutableList<NavCall> = mutableListOf()
        val pageCalls: MutableList<Boolean> = mutableListOf()
        override fun navigateTo(href: String, progression: Float, alignToTop: Boolean) {
            navCalls += NavCall(href, progression, alignToTop)
        }
        override fun scrollByPage(forward: Boolean) {
            pageCalls += forward
        }
        override fun updatePreferences(prefs: FormattingPreferences) = Unit
    }

    @Test
    fun `navigateTo ToLocatorJson extracts href + progression and steers the view`() = runTest {
        val presenter = ContinuousPresenter()
        val view = FakeView()
        presenter.attach(view)

        presenter.navigateTo(
            NavigationTarget.ToLocatorJson(
                """{"href":"ch07.xhtml","locations":{"progression":0.42}}""",
            ),
        )

        assertEquals(1, view.navCalls.size)
        assertEquals("ch07.xhtml", view.navCalls[0].href)
        assertEquals(0.42f, view.navCalls[0].progression, 0.0001f)
        // Continuous-mode resume progressions come from locatorAt's midpoint inverse, so the
        // viewport-midpoint landing (alignToTop=false) is the right default.
        assertEquals(false, view.navCalls[0].alignToTop)
    }

    @Test
    fun `navigateTo ToLocatorJson appends a fragment anchor when present`() = runTest {
        val presenter = ContinuousPresenter()
        val view = FakeView()
        presenter.attach(view)

        presenter.navigateTo(
            NavigationTarget.ToLocatorJson(
                """{"href":"ch07.xhtml","locations":{"progression":0.42,"fragments":["sec3"]}}""",
            ),
        )

        assertEquals("ch07.xhtml#sec3", view.navCalls[0].href)
    }

    @Test
    fun `navigateTo ToLocatorJson tolerates missing progression`() = runTest {
        val presenter = ContinuousPresenter()
        val view = FakeView()
        presenter.attach(view)

        presenter.navigateTo(NavigationTarget.ToLocatorJson("""{"href":"ch07.xhtml"}"""))

        assertEquals(0f, view.navCalls[0].progression, 0.0f)
    }

    @Test
    fun `navigateTo ToLocatorJson is a no-op on malformed JSON`() = runTest {
        // Mirrors the openBook tolerance: a corrupt Locator JSON column (memory:
        // "Book progress erased on open") must never crash; we just skip the navigation.
        val presenter = ContinuousPresenter()
        val view = FakeView()
        presenter.attach(view)

        presenter.navigateTo(NavigationTarget.ToLocatorJson("{not-json"))
        presenter.navigateTo(NavigationTarget.ToLocatorJson(""))
        presenter.navigateTo(NavigationTarget.ToLocatorJson("""{"locations":{"progression":0.5}}"""))

        assertTrue("expected no view.navigateTo calls, got ${view.navCalls}", view.navCalls.isEmpty())
    }

    @Test
    fun `navigateTo is a no-op before attach`() = runTest {
        val presenter = ContinuousPresenter()
        // No exception, no observable effect.
        presenter.navigateTo(
            NavigationTarget.ToLocatorJson("""{"href":"ch01.xhtml","locations":{"progression":0.5}}"""),
        )
        // Detach is also safe to call before attach.
        presenter.detach()
        assertNull(presenter.snapshotPosition())
    }

    @Test
    fun `NavigationOptions defaults match the documented tap-from-TOC intent`() {
        // Guard against silent default drift: every screen call site explicitly overrides
        // landAtStartWhenNoTarget=false for resume/return/search/annotation nav, so a regression
        // that flips a default would change behaviour for any future caller that takes them.
        val defaults = NavigationOptions()
        assertEquals(true, defaults.snap)
        assertEquals(true, defaults.landAtStartWhenNoTarget)
        assertEquals(true, defaults.animated)
        assertEquals(false, defaults.alignToTop)
    }

    @Test
    fun `navigateTo ToLocatorJson honours alignToTop from options`() = runTest {
        val presenter = ContinuousPresenter()
        val view = FakeView()
        presenter.attach(view)

        presenter.navigateTo(
            NavigationTarget.ToLocatorJson("""{"href":"ch07.xhtml","locations":{"progression":0.5}}"""),
            NavigationOptions(alignToTop = true),
        )

        assertEquals(1, view.navCalls.size)
        assertEquals(true, view.navCalls[0].alignToTop)
    }

    @Test
    fun `pageBy forwards direction to scrollByPage`() = runTest {
        val presenter = ContinuousPresenter()
        val view = FakeView()
        presenter.attach(view)

        presenter.pageBy(PageDirection.Forward)
        presenter.pageBy(PageDirection.Backward)

        assertEquals(listOf(true, false), view.pageCalls)
    }

    @Test
    fun `navigateTo ToHref drives the view with chapter-top landing`() = runTest {
        val presenter = ContinuousPresenter()
        val view = FakeView()
        presenter.attach(view)

        presenter.navigateTo(NavigationTarget.ToHref("ch04.xhtml"))

        // ContinuousReaderView's `navigateTo(href, progression=0, alignToTop=true)` is the
        // chapter-top landing convention: TOC taps land at the heading, not the viewport midpoint.
        assertEquals(1, view.navCalls.size)
        assertEquals("ch04.xhtml", view.navCalls[0].href)
        assertEquals(0f, view.navCalls[0].progression, 0.0f)
        assertEquals(true, view.navCalls[0].alignToTop)
    }

    @Test
    fun `navigateTo ToProgression honours the explicit progression`() = runTest {
        val presenter = ContinuousPresenter()
        val view = FakeView()
        presenter.attach(view)

        presenter.navigateTo(NavigationTarget.ToProgression("ch04.xhtml", progression = 0.6f))

        assertEquals("ch04.xhtml", view.navCalls[0].href)
        assertEquals(0.6f, view.navCalls[0].progression, 0.0001f)
        assertEquals(false, view.navCalls[0].alignToTop)
    }

    @Test
    fun `navigateTo ToProgression honours alignToTop from options`() = runTest {
        // Bookmark progression carries a content-top reference; the screen passes alignToTop=true
        // in continuous mode to invert the locatorAt midpoint inverse the resume path uses.
        val presenter = ContinuousPresenter()
        val view = FakeView()
        presenter.attach(view)

        presenter.navigateTo(
            NavigationTarget.ToProgression("ch04.xhtml", progression = 0.6f),
            NavigationOptions(alignToTop = true),
        )

        assertEquals(true, view.navCalls[0].alignToTop)
    }

    @Test
    fun `followReadaloudSentence always reports Unavailable in continuous mode`() = runTest {
        // Continuous mode's readaloud highlight runs through ContinuousReaderView's own JS
        // injection pipeline, not ColumnSnap. The Unavailable result short-circuits the screen's
        // paginated-only snap effect — both with and without an attached view.
        val presenter = ContinuousPresenter()
        assertEquals(ReadaloudFollowResult.Unavailable, presenter.followReadaloudSentence("any"))
        presenter.attach(FakeView())
        assertEquals(ReadaloudFollowResult.Unavailable, presenter.followReadaloudSentence("any"))
    }

    @Test
    fun `measureReadaloudColumns is always empty in continuous mode`() = runTest {
        // Empty list means NarratedColumnProgression never advances — the correct behaviour for a
        // mode without a column grid.
        val presenter = ContinuousPresenter()
        assertTrue(presenter.measureReadaloudColumns("any").isEmpty())
        presenter.attach(FakeView())
        assertTrue(presenter.measureReadaloudColumns("any").isEmpty())
    }

    @Test
    fun `snapReadaloudColumn is a no-op in continuous mode`() = runTest {
        // No view interaction even after attach — continuous mode has no column to snap to.
        val presenter = ContinuousPresenter()
        val view = FakeView()
        presenter.attach(view)
        presenter.snapReadaloudColumn("any", columnIndex = 2)
        assertTrue("expected no view calls, got navCalls=${view.navCalls} pageCalls=${view.pageCalls}",
            view.navCalls.isEmpty() && view.pageCalls.isEmpty())
    }

    @Test
    fun `scrollBoundary returns None in continuous mode`() = runTest {
        // Continuous mode tracks chapter boundaries internally via window shifting, not via
        // ScrollBoundaryNavigationContainer, so the seam reports no boundary regardless of
        // attach state — keeping the screen's vertical-only poll loop harmless if it ever fires
        // outside its formal gate.
        val presenter = ContinuousPresenter()
        assertEquals(ScrollBoundary.None, presenter.scrollBoundary())
        presenter.attach(FakeView())
        assertEquals(ScrollBoundary.None, presenter.scrollBoundary())
        assertEquals(false, ScrollBoundary.None.atForwardBoundary)
        assertEquals(false, ScrollBoundary.None.atBackwardBoundary)
    }
}
