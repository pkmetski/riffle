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
}
