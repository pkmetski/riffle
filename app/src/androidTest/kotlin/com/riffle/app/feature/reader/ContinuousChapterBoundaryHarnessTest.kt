package com.riffle.app.feature.reader

import android.graphics.Bitmap
import android.os.Handler
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Surface
import android.view.Window
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.MainActivity
import com.riffle.app.harness.ReaderSemanticMatchers
import com.riffle.app.harness.ReaderSemanticMatchers.assertNoErrorState
import com.riffle.app.harness.ReaderSemanticMatchers.tapReadInDetailScreen
import com.riffle.app.harness.StubAbsServer
import com.riffle.core.data.di.EpubCacheStore
import com.riffle.core.database.RiffleDatabase
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReaderOrientation
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Device-level regression for the shape observed in "Taking Charge of ADHD": a long chapter,
 * a tiny part-title resource, then another long chapter. Continuous mode used to oscillate the
 * sliding window at that short middle resource, making the adjacent chapters unreachable by
 * scrolling even though TOC navigation could open them directly.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ContinuousChapterBoundaryHarnessTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var database: RiffleDatabase
    @EpubCacheStore @Inject lateinit var epubCacheStore: LocalStore
    @Inject lateinit var formattingPreferencesStore: FormattingPreferencesStore

    private val stubServer = StubAbsServer(epubBytesProvider = ::boundaryEpub)

    @Before
    fun setUp() {
        stubServer.start()
        hiltRule.inject()
        database.clearAllTables()
        epubCacheStore.clear()
        runBlocking {
            val prefs = formattingPreferencesStore.preferences.first()
            formattingPreferencesStore.update(prefs.copy(orientation = ReaderOrientation.Continuous))
        }
    }

    @After
    fun tearDown() {
        stubServer.shutdown()
        composeTestRule.activityRule.scenario.close()
        Runtime.getRuntime().gc()
        Thread.sleep(400)
        database.clearAllTables()
        runBlocking {
            val prefs = formattingPreferencesStore.preferences.first()
            formattingPreferencesStore.update(prefs.copy(orientation = ReaderOrientation.Horizontal))
        }
    }

    /**
     * Regression for the "stuck title page" bug (book e866cd1d, "Free Excerpt" chapter).
     *
     * Chapters with large images get measured TWICE: once when the page loads (before the image
     * decodes), and again when the image loads and triggers a second layout pass. The second
     * measurement fires `reapplyLandingAfterFallback`, which queues a `port.post { postLandAt }`.
     * If the user has touched between the image load and when that queued message runs,
     * `onTouchDown` has already cleared `reapplyLandingAfterFallback` — but cannot cancel the
     * already-posted message. Without the fix, that queued `postLandAt` re-arms `landingHoldTargetY`
     * for 600 ms, and `tickLandingHold` reverts every scroll tick — the "stuck" symptom.
     *
     * The regression guard is `reapplyLandingSuperseded`: set on the first `onTouchDown` after
     * any `openWindowAt`, checked inside the `port.post { postLandAt }` body. This test pins both
     * transitions:
     *   - false initially (fresh window) → true after a touch → false after next navigation
     *
     * The specific assertion that would fail if the fix were reverted: `isReapplyLandingSuperseded`
     * stays false after a touch because the flag is never set in `onTouchDown`, so the re-apply
     * guard inside `postLandAt` never fires and a late-image height change re-arms the hold.
     */
    @Test
    fun reapplyLandingSupersededSetByTouchClearedByNavigation() {
        addServerAndBrowseLibrary()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).performClick()
        composeTestRule.tapReadInDetailScreen()
        composeTestRule.waitUntil(timeoutMillis = 45_000) {
            composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_READER_READY)
                .fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_ERROR_STATE)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.assertNoErrorState()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            findContinuousReader()?.isFirstLoadComplete?.value == true
        }
        val reader = requireNotNull(findContinuousReader()) { "continuous reader view was not mounted" }
        dismissUpdateDialogIfPresent()

        // 1. Fresh window: reapplyLandingSuperseded must start false so re-applies can fire to
        //    correct the scroll position while the user hasn't yet touched (e.g. late image decode).
        var superseded = true
        composeTestRule.activityRule.scenario.onActivity { superseded = reader.isReapplyLandingSuperseded }
        assertFalse("reapplyLandingSuperseded should be false on fresh window open", superseded)

        // 2. After a touch: must flip to true so any already-queued postLandAt (from a
        //    reapplyLandingAfterFallback triggered just before onTouchDown ran) bails out instead
        //    of re-arming landingHoldTargetY and locking the reader for 600 ms.
        dispatchSwipeUp(reader)
        composeTestRule.waitForIdle()
        composeTestRule.activityRule.scenario.onActivity { superseded = reader.isReapplyLandingSuperseded }
        assertTrue("reapplyLandingSuperseded should be true after onTouchDown", superseded)

        // 3. After cross-chapter navigation (openWindowAt): must reset to false so re-applies work
        //    for the new target position before the user interacts with the new window.
        composeTestRule.activityRule.scenario.onActivity {
            reader.navigateTo("OEBPS/ch07.html", progression = 0f, alignToTop = true)
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            isReaderInChapter(reader, "ch07.html")
        }
        composeTestRule.activityRule.scenario.onActivity { superseded = reader.isReapplyLandingSuperseded }
        assertFalse("reapplyLandingSuperseded should be false after openWindowAt for cross-chapter nav", superseded)
    }

    /**
     * Regression for 2026-08-06: [ContinuousWindowController.prependChapter] must arm
     * [ContinuousWindowController.boundaryDetentArmed] so the backward-prepend scroll floor stays
     * at the real chapter height even after the chapter measures (prependAwaitingMeasure=false).
     * Without the detent, the floor drops to 0 at measurement time: any in-flight gesture or
     * fling carries the user past the boundary and into the prepended chapter — field-reported as
     * "abrupt jump navigating between specific chapters" (book e866cd1d, 2026-08-06).
     *
     * The specific assertion that fails if `boundaryDetentArmed = true` is removed from
     * [ContinuousWindowController.prependChapter]: `isBoundaryDetentArmed` is `false` immediately
     * after the backward swipe, so the floor is 0 and any residual scroll carries the user past
     * the boundary.
     */
    @Test
    fun boundaryDetentArmedAfterBackwardPrepend() {
        addServerAndBrowseLibrary()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).performClick()
        composeTestRule.tapReadInDetailScreen()
        composeTestRule.waitUntil(timeoutMillis = 45_000) {
            composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_READER_READY)
                .fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_ERROR_STATE)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.assertNoErrorState()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            findContinuousReader()?.isFirstLoadComplete?.value == true
        }
        val reader = requireNotNull(findContinuousReader()) { "continuous reader view was not mounted" }
        dismissUpdateDialogIfPresent()

        // Navigate to start of ch07 — immediately past the pt02 short-title boundary.
        composeTestRule.activityRule.scenario.onActivity {
            reader.navigateTo("OEBPS/ch07.html", progression = 0f, alignToTop = true)
        }
        composeTestRule.waitUntil(timeoutMillis = 45_000) {
            isReaderInChapter(reader, "ch07.html")
        }
        composeTestRule.waitForIdle()
        Thread.sleep(5_000) // let fallback flush pendingInitialScroll

        // Dispatch a single backward swipe. The scroll crosses the ch07 top → triggers a backward
        // prepend (either pt02 or ch06). After the gesture completes, boundaryDetentArmed must be
        // true so the floor remains at the real chapter height until the next ACTION_DOWN.
        dispatchSwipe(reader, forward = false)
        composeTestRule.waitForIdle()
        Thread.sleep(500) // give onHeightMeasured time to run and set prependAwaitingMeasure=false

        var detentArmed = false
        composeTestRule.activityRule.scenario.onActivity { detentArmed = reader.isBoundaryDetentArmed }
        assertTrue(
            "isBoundaryDetentArmed must be true after a backward prepend so the scroll floor " +
                "remains at the real chapter height after measurement; without this the floor drops " +
                "to 0 and any in-flight gesture carries the user past the boundary (abrupt jump)",
            detentArmed,
        )
    }

    @Test
    fun scrollsAcrossBothLongChapterShortPartTitleBoundaries() {
        addServerAndBrowseLibrary()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(StubAbsServer.TEST_STANDALONE_ITEM_TITLE).performClick()
        composeTestRule.tapReadInDetailScreen()
        composeTestRule.waitUntil(timeoutMillis = 45_000) {
            composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_READER_READY)
                .fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_ERROR_STATE)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.assertNoErrorState()

        // The synthetic publication begins at ch05 so reaching ch07 first forces the sliding
        // window to evict ch06 and leave pt02 as the top buffer — the exact oscillation state.
        // TOC navigation is a separate path and would mask this regression.
        // At scrollY=0 NestedScrollView does not emit an onScrollChanged callback, so the public
        // locator semantics are intentionally still empty here; wait on the real view's initial
        // WebView measurement barrier instead.
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            findContinuousReader()?.isFirstLoadComplete?.value == true
        }
        val reader = requireNotNull(findContinuousReader()) {
            "continuous reader view was not mounted"
        }

        dismissUpdateDialogIfPresent()
        swipeFromNearEndInto(reader, fromHref = "OEBPS/ch06.html", toHref = "ch07.html")
        swipeBackwardFromStartInto(reader, fromHref = "OEBPS/ch07.html", toHref = "ch06.html")
        assertReaderViewportRendered(reader, "ch06.html")
        flingBackwardLandsAtBoundary(
            reader,
            fromHref = "OEBPS/ch11.html",
            prevHref = "ch10.html",
            boundaryHref = "pt03.html",
        )
        swipeFromNearEndInto(reader, fromHref = "OEBPS/ch10.html", toHref = "ch11.html")
        swipeBackwardFromStartInto(reader, fromHref = "OEBPS/ch11.html", toHref = "ch10.html")
        assertReaderViewportRendered(reader, "ch10.html")
        flingBackwardOverLoadedChapterStopsNearBoundary(reader)
    }

    /**
     * Scenario A of the 2026-08-05 field repro: the previous chapter is ALREADY in the window
     * (behind buffer / earlier crossing) so a backward fling triggers no prepend — none of the
     * prepend gates apply — and an unconstrained ballistic fling teleported the reader ~4
     * viewports deep into it. The backward-fling floor must cap the landing at one page past
     * the folded pt03 boundary.
     */
    private fun flingBackwardOverLoadedChapterStopsNearBoundary(reader: ContinuousReaderView) {
        composeTestRule.activityRule.scenario.onActivity {
            reader.navigateTo("OEBPS/ch11.html", progression = 0f, alignToTop = true)
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            isReaderInChapter(reader, "ch11.html")
        }
        // ch10 must already be loaded and measured — that is the premise of this scenario.
        var ch10Height = 0
        composeTestRule.activityRule.scenario.onActivity {
            ch10Height = loadedWebViews(reader)
                .firstOrNull { it.url?.endsWith("ch10.html") == true }?.height ?: 0
        }
        assertTrue("premise broken: ch10 not loaded/measured before the fling", ch10Height > 10_000)
        dispatchFlingSwipeBackward(reader)
        composeTestRule.waitForIdle()
        Thread.sleep(1_500)
        var boundaryTop = -1
        var scrollYNow = -1
        var viewportH = 0
        composeTestRule.activityRule.scenario.onActivity {
            boundaryTop = loadedWebViews(reader)
                .firstOrNull { it.url?.endsWith("pt03.html") == true }?.top ?: -1
            scrollYNow = reader.scrollY
            viewportH = reader.height
        }
        assertTrue("pt03 missing from window after fling", boundaryTop >= 0)
        val overshoot = boundaryTop - scrollYNow
        assertTrue(
            "no-prepend backward fling overshot the pt03 boundary by $overshoot px " +
                "(scrollY=$scrollYNow, boundaryTop=$boundaryTop, viewport=$viewportH) — " +
                "the backward-fling floor must cap crossing flings at one page past the boundary",
            overshoot <= viewportH,
        )
    }

    private fun findContinuousReader(): ContinuousReaderView? {
        var result: ContinuousReaderView? = null
        composeTestRule.activityRule.scenario.onActivity { activity ->
            fun walk(view: View) {
                if (view is ContinuousReaderView) result = view
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) walk(view.getChildAt(index))
                }
            }
            walk(activity.window.decorView)
        }
        return result
    }

    private fun loadedWebViews(reader: ContinuousReaderView): List<WebView> {
        val container = reader.getChildAt(0) as? ViewGroup ?: return emptyList()
        return (0 until container.childCount).mapNotNull { container.getChildAt(it) as? WebView }
    }

    private fun loadedChapterHrefs(reader: ContinuousReaderView): List<String> {
        var result = emptyList<String>()
        composeTestRule.activityRule.scenario.onActivity {
            result = loadedWebViews(reader).mapNotNull { view ->
                view.url?.substringAfter("https://readium_package/")
            }
        }
        return result
    }

    private fun swipeFromNearEndInto(
        reader: ContinuousReaderView,
        fromHref: String,
        toHref: String,
    ) {
        composeTestRule.activityRule.scenario.onActivity {
            reader.navigateTo(fromHref, progression = 0.9f, alignToTop = true)
        }
        composeTestRule.waitUntil(timeoutMillis = 45_000) {
            loadedChapterHrefs(reader).any { it.endsWith(fromHref) }
        }
        // Wait for the navigation itself to LAND before swiping: when the target chapter is
        // already in the window the posted landing can run seconds later (main thread busy with
        // measures), and if the reader incidentally already satisfies the destination check the
        // swipe loop exits instantly — the late nav landing then legitimately moves the reader
        // during the settle assertions and the leg fails as a phantom "yank".
        composeTestRule.waitUntil(timeoutMillis = 20_000) {
            isReaderInChapter(reader, fromHref)
        }
        for (attempt in 0 until 80) {
            if (isReaderInChapter(reader, toHref)) break
            dispatchSwipeUp(reader)
            composeTestRule.waitForIdle()
            Thread.sleep(100)
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            isReaderInChapter(reader, toHref)
        }
        repeat(8) {
            composeTestRule.waitForIdle()
            Thread.sleep(100)
        }
        if (!isReaderInChapter(reader, toHref)) {
            var diag = ""
            composeTestRule.activityRule.scenario.onActivity {
                diag = loadedWebViews(reader).joinToString(prefix = "[", postfix = "]") {
                    "${it.url?.substringAfterLast('/')}:h=${it.height}:top=${it.top}"
                } + " scrollY=${reader.scrollY} vh=${reader.height}"
            }
            org.junit.Assert.fail("expected $toHref to remain reachable; window=$diag")
        }
    }

    private fun swipeBackwardFromStartInto(
        reader: ContinuousReaderView,
        fromHref: String,
        toHref: String,
    ) {
        composeTestRule.activityRule.scenario.onActivity {
            reader.navigateTo(fromHref, progression = 0f, alignToTop = true)
        }
        composeTestRule.waitUntil(timeoutMillis = 45_000) {
            loadedChapterHrefs(reader).any { it.endsWith(fromHref) }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            isReaderInChapter(reader, fromHref)
        }
        for (attempt in 0 until 40) {
            if (isReaderInChapter(reader, toHref)) break
            dispatchSwipe(reader, forward = false)
            composeTestRule.waitForIdle()
            Thread.sleep(100)
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            isReaderInChapter(reader, toHref)
        }
        composeTestRule.waitForIdle()
        assertFalse(
            "one backward gesture must be consumed by one prepend; leaving it latched lets " +
                "scroll compensation prepend every earlier resource until the screen is blank",
            reader.hasPendingBackwardNavigationIntent,
        )
    }

    /**
     * Regression for the 2026-08-03 field repro (ch07 → ch06 in "Taking Charge of ADHD"): a
     * backward FLING — not a slow drag — from the top of a chapter must land adjacent to the
     * part-title boundary. On a device each MotionEvent is its own main-loop message, so the
     * backward prepend fires MID-drag; NestedScrollView then still starts a ballistic fling from
     * the release velocity at ACTION_UP, whose scroller deltas compose with the prepend's scroll
     * compensation and carry the reader thousands of px past the boundary into the freshly
     * revealed chapter. [ContinuousReaderView.fling] swallows that gesture's release fling.
     * The gesture must be dispatched event-by-event ([dispatchFlingSwipeBackward]) — an atomic
     * one-block dispatch defers the posted prepend until after ACTION_UP and never exercises the
     * mid-gesture interleaving the fix targets.
     */
    private fun flingBackwardLandsAtBoundary(
        reader: ContinuousReaderView,
        fromHref: String,
        prevHref: String,
        boundaryHref: String,
    ) {
        composeTestRule.activityRule.scenario.onActivity {
            reader.navigateTo(fromHref, progression = 0f, alignToTop = true)
        }
        composeTestRule.waitUntil(timeoutMillis = 45_000) {
            loadedChapterHrefs(reader).any { it.endsWith(fromHref) }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            isReaderInChapter(reader, fromHref)
        }
        // Let the rebuild's initial-scroll land fully: while pendingInitialScroll is set the
        // controller ignores every shift decision (including the one this gesture triggers), so
        // under machine load the prepend silently never fires and the leg flakes. The fallback
        // fires at 2.5 s; wait just past it.
        composeTestRule.waitForIdle()
        Thread.sleep(5_000)
        dispatchFlingSwipeBackward(reader)
        // The prepended previous chapter must load AND measure past its screen-sized placeholder
        // before the landing position is meaningful. Polled manually so a timeout can report the
        // full window state instead of an opaque ComposeTimeoutException.
        val measureDeadline = android.os.SystemClock.uptimeMillis() + 45_000
        var prevMeasured = false
        while (!prevMeasured && android.os.SystemClock.uptimeMillis() < measureDeadline) {
            composeTestRule.waitForIdle()
            composeTestRule.activityRule.scenario.onActivity {
                prevMeasured = loadedWebViews(reader).any { wv ->
                    wv.url?.endsWith(prevHref) == true && wv.height > reader.height * 3
                }
            }
            if (!prevMeasured) Thread.sleep(200)
        }
        if (!prevMeasured) {
            var diag = ""
            composeTestRule.activityRule.scenario.onActivity {
                diag = loadedWebViews(reader).joinToString(prefix = "[", postfix = "]") {
                    "${it.url?.substringAfterLast('/')}:h=${it.height}:top=${it.top}"
                } + " scrollY=${reader.scrollY} vh=${reader.height} " +
                    "intent=${reader.hasPendingBackwardNavigationIntent}"
            }
            org.junit.Assert.fail("prepended $prevHref never measured after backward fling; window=$diag")
        }
        composeTestRule.waitForIdle()
        Thread.sleep(1_000) // let any surviving scroller frames play out — that IS the regression
        var boundaryTop = -1
        var scrollYNow = -1
        var viewportH = 0
        composeTestRule.activityRule.scenario.onActivity {
            boundaryTop = loadedWebViews(reader)
                .firstOrNull { it.url?.endsWith(boundaryHref) == true }?.top ?: -1
            scrollYNow = reader.scrollY
            viewportH = reader.height
        }
        assertTrue("boundary resource $boundaryHref not in window after backward fling", boundaryTop >= 0)
        val overshoot = boundaryTop - scrollYNow
        assertTrue(
            "backward fling overshot the $boundaryHref boundary by $overshoot px " +
                "(scrollY=$scrollYNow, boundaryTop=$boundaryTop, viewport=$viewportH) — " +
                "a crossing fling must land at most one page past the boundary (swallowed " +
                "release fling after a mid-gesture prepend, or the backward-fling floor)",
            overshoot <= viewportH,
        )
    }

    private fun isReaderInChapter(reader: ContinuousReaderView, href: String): Boolean {
        var currentHref: String? = null
        composeTestRule.activityRule.scenario.onActivity {
            val midpoint = reader.scrollY + reader.height / 2
            currentHref = loadedWebViews(reader)
                .firstOrNull { midpoint >= it.top && midpoint < it.bottom }
                ?.url
                ?.substringAfter("https://readium_package/")
        }
        return currentHref?.endsWith(href) == true
    }

    private fun assertReaderViewportRendered(reader: ContinuousReaderView, href: String) {
        composeTestRule.waitForIdle()
        Thread.sleep(1_500)
        val screen = captureWindowToBitmap(composeTestRule.activity.window)
        val location = IntArray(2)
        composeTestRule.activityRule.scenario.onActivity { reader.getLocationOnScreen(location) }
        val xStart = location[0].coerceAtLeast(0)
        val xEnd = (location[0] + reader.width).coerceAtMost(screen.width)
        val yStart = (location[1] + reader.height * 0.1f).toInt().coerceAtLeast(0)
        val yEnd = (location[1] + reader.height * 0.85f).toInt().coerceAtMost(screen.height)
        var inkPixels = 0
        for (y in yStart until yEnd step 2) {
            for (x in xStart until xEnd step 2) {
                val pixel = screen.getPixel(x, y)
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                if (red + green + blue < 540) inkPixels++
            }
        }
        assertTrue(
            "expected rendered text after navigating backward into $href; " +
                "only $inkPixels sampled ink pixels were visible",
            inkPixels >= 500,
        )
    }

    private fun captureWindowToBitmap(window: Window): Bitmap {
        val decor = window.decorView
        val viewRoot = decor.parent
        val surfaceField = viewRoot.javaClass.getDeclaredField("mSurface").apply { isAccessible = true }
        val surface = surfaceField.get(viewRoot) as Surface
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        val result = intArrayOf(-1)
        val handlerThread = android.os.HandlerThread("ContinuousBoundaryPixelCopy").apply { start() }
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

    private fun dispatchSwipeUp(reader: ContinuousReaderView) =
        dispatchSwipe(reader, forward = true)

    private fun dispatchSwipe(reader: ContinuousReaderView, forward: Boolean) {
        composeTestRule.activityRule.scenario.onActivity {
            val downTime = android.os.SystemClock.uptimeMillis()
            val x = reader.width / 2f
            val startY = reader.height * if (forward) 0.8f else 0.2f
            // A slow pull is enough to reveal a previous chapter at the scrollY=0 clamp and
            // avoids turning the regression assertion into a high-velocity fling test.
            val endY = reader.height * if (forward) 0.2f else 0.4f
            val stepMs = if (forward) 16L else 50L
            fun dispatch(action: Int, y: Float, offsetMs: Long) {
                MotionEvent.obtain(downTime, downTime + offsetMs, action, x, y, 0).also { event ->
                    reader.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            dispatch(MotionEvent.ACTION_DOWN, startY, 0)
            repeat(8) { index ->
                val fraction = (index + 1) / 8f
                dispatch(
                    MotionEvent.ACTION_MOVE,
                    startY + (endY - startY) * fraction,
                    (index + 1) * stepMs,
                )
            }
            dispatch(MotionEvent.ACTION_UP, endY, 10 * stepMs)
        }
    }

    /**
     * Dispatch a fast backward swipe with each MotionEvent in its own main-thread message,
     * mirroring real device input: posted work (the window-shift decision) interleaves with the
     * gesture, so the backward prepend fires MID-drag and ACTION_UP's release fling exercises
     * [ContinuousReaderView.fling]'s swallow path. Timestamps advance 8 ms per move for a
     * high release velocity regardless of wall-clock sleeps.
     */
    private fun dispatchFlingSwipeBackward(reader: ContinuousReaderView) {
        var width = 0
        var height = 0
        composeTestRule.activityRule.scenario.onActivity {
            width = reader.width
            height = reader.height
        }
        val downTime = android.os.SystemClock.uptimeMillis()
        val x = width / 2f
        val startY = height * 0.2f
        val endY = height * 0.85f
        fun send(action: Int, y: Float, offsetMs: Long) {
            composeTestRule.activityRule.scenario.onActivity {
                MotionEvent.obtain(downTime, downTime + offsetMs, action, x, y, 0).also { event ->
                    reader.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
        }
        send(MotionEvent.ACTION_DOWN, startY, 0)
        repeat(8) { index ->
            Thread.sleep(8)
            send(MotionEvent.ACTION_MOVE, startY + (endY - startY) * (index + 1) / 8f, (index + 1) * 8L)
        }
        Thread.sleep(8)
        send(MotionEvent.ACTION_UP, endY, 9 * 8L)
    }

    private fun dismissUpdateDialogIfPresent() {
        val later = composeTestRule.onAllNodesWithText("Later")
        if (later.fetchSemanticsNodes().isNotEmpty()) {
            composeTestRule.onNodeWithText("Later").performClick()
            composeTestRule.waitForIdle()
        }
    }

    private fun addServerAndBrowseLibrary() {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Audiobookshelf").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Audiobookshelf").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Connect").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and hasText("Source URL"))
            .performTextReplacement(stubServer.baseUrl)
        composeTestRule.onNode(hasSetTextAction() and hasText("Username"))
            .performTextReplacement("testuser")
        composeTestRule.onNode(hasSetTextAction() and hasText("Password"))
            .performTextReplacement("testpass")
        composeTestRule.onNodeWithText("Connect").performClick()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText("Connect anyway").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Connect anyway").performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithContentDescription("All Books")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("All Books").performClick()
    }

    private fun boundaryEpub(): ByteArray {
        val spine = listOf(
            "ch05.html",
            "ch06.html",
            "pt02.html",
            "ch07.html",
            "ch08.html",
            "ch09.html",
            "ch10.html",
            "pt03.html",
            "ch11.html",
            "ch12.html",
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val mimeBytes = "application/epub+zip".toByteArray()
            val mimeCrc = CRC32().apply { update(mimeBytes) }
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimeBytes.size.toLong()
                    compressedSize = mimeBytes.size.toLong()
                    crc = mimeCrc.value
                },
            )
            zip.write(mimeBytes)
            zip.closeEntry()

            zip.writeEntry(
                "META-INF/container.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent(),
            )
            zip.writeEntry(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="uid">continuous-short-divider-regression</dc:identifier>
                    <dc:title>Continuous Boundary Regression</dc:title>
                    <dc:creator>Riffle Tests</dc:creator>
                    <dc:language>en</dc:language>
                    <meta property="dcterms:modified">2026-07-29T00:00:00Z</meta>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    ${spine.mapIndexed { index, href ->
                        """<item id="s$index" href="$href" media-type="application/xhtml+xml"/>"""
                    }.joinToString("\n    ")}
                  </manifest>
                  <spine>
                    ${spine.indices.joinToString("\n    ") { """<itemref idref="s$it"/>""" }}
                  </spine>
                </package>
                """.trimIndent(),
            )
            zip.writeEntry(
                "OEBPS/nav.xhtml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Contents</title></head>
                  <body><nav epub:type="toc" xmlns:epub="http://www.idpf.org/2007/ops">
                    <ol>
                      ${spine.joinToString("\n      ") { """<li><a href="$it">$it</a></li>""" }}
                    </ol>
                  </nav></body>
                </html>
                """.trimIndent(),
            )
            spine.forEach { href ->
                val isPartTitle = href.startsWith("pt")
                val number = href.substring(2, 4).toInt()
                val title = if (isPartTitle) "Part $number" else "Chapter $number"
                val body = if (isPartTitle) {
                    "<h1>$title</h1>"
                } else {
                    buildString {
                        append("<h1>$title</h1>")
                        repeat(180) { paragraph ->
                            append("<p>$title regression paragraph $paragraph. ")
                            append(
                                "Long chapter content keeps the neighbouring part title far " +
                                    "shorter than one viewport.</p>",
                            )
                        }
                    }
                }
                zip.writeEntry(
                    "OEBPS/$href",
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <head><title>$title</title></head>
                      <body>$body</body>
                    </html>
                    """.trimIndent(),
                )
            }
        }
        return output.toByteArray()
    }

    private fun ZipOutputStream.writeEntry(path: String, contents: String) {
        putNextEntry(ZipEntry(path))
        write(contents.trimStart().toByteArray())
        closeEntry()
    }
}
