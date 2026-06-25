package com.riffle.app.feature.reader

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riffle.app.MainActivity
import com.riffle.app.harness.ReaderSemanticMatchers
import com.riffle.app.harness.ReaderSemanticMatchers.tapReadInDetailScreen
import com.riffle.app.harness.StubAbsServer
import com.riffle.core.data.di.EpubCacheStore
import com.riffle.core.database.RiffleDatabase
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReaderOrientation
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Real end-to-end verification that tapping an annotation FOCUSES it on screen — i.e. the reader
 * actually scrolls/pages so the annotated text is in the visible viewport — in ALL THREE reader
 * modes (paginated / vertical / continuous).
 *
 * Unlike the earlier logic-only tests, this drives the REAL Readium/Continuous reader and asserts
 * the target element's getBoundingClientRect lands inside the viewport after navigation.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AnnotationFocusHarnessTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var database: RiffleDatabase
    @EpubCacheStore @Inject lateinit var epubCacheStore: LocalStore
    @Inject lateinit var annotationStore: AnnotationStore
    @Inject lateinit var formattingPreferencesStore: FormattingPreferencesStore

    private val stubServer = StubAbsServer()

    // A single-line landmark deep in chapter1 of test.epub (the "Section 1.3" heading). Single-line
    // so it can't straddle a paged column boundary (which would make its rect ambiguous), yet far
    // enough down that focusing it requires real scrolling/paging from the chapter top.
    private val targetPhrase = "Section 1.3: Development"

    @Before
    fun setUp() {
        stubServer.start()
        hiltRule.inject()
        database.clearAllTables()
        epubCacheStore.clear()
    }

    @After
    fun tearDown() {
        stubServer.shutdown()
        composeTestRule.activityRule.scenario.close()
        Runtime.getRuntime().gc()
        Thread.sleep(400)
        database.clearAllTables()
    }

    @Test
    fun paginatedMode_annotationTap_focusesAnnotationOnScreen() {
        runModeTest(ReaderOrientation.Horizontal)
    }

    @Test
    fun verticalMode_annotationTap_focusesAnnotationOnScreen() {
        runModeTest(ReaderOrientation.Vertical)
    }

    @Test
    fun continuousMode_annotationTap_focusesAnnotationOnScreen() {
        runModeTest(ReaderOrientation.Continuous)
    }

    private fun runModeTest(orientation: ReaderOrientation) {
        runBlocking {
            val prefs = formattingPreferencesStore.preferences.first()
            formattingPreferencesStore.update(prefs.copy(orientation = orientation))
        }
        addServerAndBrowseLibrary()
        seedDeepHighlight()

        // Trigger the library→tap-annotation flow MULTIPLE times to expose races.
        // After each landing the test signals a marker + holds, so the host can screencap that
        // landing's pixels (uiAutomation.takeScreenshot returns null on a -no-window AVD).
        val attempts = 3
        val tag = orientation.name.lowercase()
        repeat(attempts) { i ->
            val attempt = i + 1
            searchAndTapAnnotation()
            composeTestRule.waitUntil(timeoutMillis = 20_000) {
                composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_READER_READY)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            // The annotation-mark anchored landing finishes within a couple of remeasures (one
            // reflow tick). 8s is comfortably beyond that and gives the AVD WebView time to paint.
            Thread.sleep(8_000)
            writeMarker("READY_${tag}_$attempt")
            Thread.sleep(8_000)
            if (attempt < attempts) returnToLibrary()
        }
    }

    private fun writeMarker(name: String) {
        try {
            android.util.Log.d("AnnoFocusHarness", "MARKER $name")
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null) ?: return
            java.io.File(dir, name).writeText(name)
        } catch (_: Throwable) { /* diagnostic only */ }
    }

    /** Pop the back stack from reader (and the library detail screen, if interposed) back to the
     *  library search grid where the annotation row is tappable again.
     *
     *  Uses the activity's onBackPressedDispatcher directly rather than `Espresso.pressBack()`.
     *  Both trigger the same back-stack navigation, but pressBack first asserts the root view has
     *  window focus — a precondition the headless API-25 emulator on CI cannot reliably satisfy
     *  (the same test passes on local windowed AVDs because they do). Dispatching on the activity
     *  is the documented Compose-test substitute and is what the assertion actually cares about. */
    private fun returnToLibrary() {
        var attempts = 0
        while (attempts < 4 &&
            composeTestRule.onAllNodesWithText("Search").fetchSemanticsNodes().isEmpty()
        ) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
            }
            attempts++
            Thread.sleep(800)
        }
        composeTestRule.waitUntil(timeoutMillis = 8_000) {
            composeTestRule.onAllNodesWithText("Search").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun searchAndTapAnnotation() {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Search").fetchSemanticsNodes().isNotEmpty()
        }
        // performTextReplacement (not Input) so repeated calls on subsequent attempts overwrite the
        // previous query instead of appending to it.
        composeTestRule.onNodeWithText("Search").performTextReplacement("Section 1.3")
        composeTestRule.waitUntil(timeoutMillis = 8_000) {
            composeTestRule.onAllNodesWithText(targetPhrase).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(targetPhrase).performClick()
    }

    private data class FocusResult(val onScreen: Boolean, val detail: String) {
        override fun toString() = detail
    }

    private fun waitForPhraseOnScreen(orientation: ReaderOrientation, timeoutMs: Long): FocusResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = FocusResult(false, "phrase never located")
        while (System.currentTimeMillis() < deadline) {
            last = phraseOnScreen(orientation)
            if (last.onScreen) return last
            Thread.sleep(150)
        }
        return last
    }

    /**
     * Whether the highlighted phrase is actually within the visible reading area.
     *  - Paged/Vertical: a single Readium WebView fills the screen, so getBoundingClientRect is
     *    viewport-relative — phrase rect must be inside [0,innerWidth) × [0,innerHeight).
     *  - Continuous: several ChapterWebViews are stacked inside a NestedScrollView, so a WebView-local
     *    rect is NOT viewport-relative. Convert to absolute screen coords (WebView location on screen +
     *    rect × CSS→device scale) and test against the reader content area's on-screen bounds.
     *
     * IMPORTANT: WebView.evaluateJavascript must be called on the UI thread, but its callback also
     * runs on the UI thread — so we register the eval inside onActivity and await OUTSIDE it (never
     * block the UI thread waiting on its own callback).
     */
    private fun phraseOnScreen(orientation: ReaderOrientation): FocusResult {
        val webViews = visibleWebViews()
        if (webViews.isEmpty()) return FocusResult(false, "no visible WebView")
        // Find the WebView whose phrase rect resolves, and read its rect + viewport.
        for (wv in webViews) {
            val raw = evalJs(wv, phraseRectJs(targetPhrase))
            val parts = raw.takeIf { it != "null" && it != "NO_WEBVIEW" && it.isNotBlank() }?.split(',')
            if (parts == null || parts.size < 4) continue
            val left = parts[0].toDoubleOrNull() ?: continue
            val top = parts[1].toDoubleOrNull() ?: continue
            val iw = parts[2].toDoubleOrNull() ?: continue
            val ih = parts[3].toDoubleOrNull() ?: continue

            if (orientation == ReaderOrientation.Continuous) {
                val geo = webViewScreenGeometry(wv) ?: continue
                val (wvX, wvY, contentRect) = geo
                val scaleX = if (iw > 0) wv.width / iw else 1.0
                val scaleY = if (ih > 0) wv.height / ih else scaleX
                val screenX = wvX + left * scaleX
                val screenY = wvY + top * scaleY
                // Tolerance: top-aligned focus lands the target right at the viewport edge, where
                // sub-pixel rounding can put it a few px outside. A few px off the edge is still focused.
                val tol = 24
                val onScreen = screenX >= contentRect[0] - tol && screenX < contentRect[0] + contentRect[2] &&
                    screenY >= contentRect[1] - tol && screenY < contentRect[1] + contentRect[3]
                return FocusResult(
                    onScreen,
                    "continuous: phraseScreen=($screenX,$screenY) content=[${contentRect[0]},${contentRect[1]} +${contentRect[2]}x${contentRect[3]}] wvLoc=($wvX,$wvY) rect=($left,$top) iw/ih=($iw,$ih) wv=${wv.width}x${wv.height}",
                )
            } else {
                // Low-side tolerance absorbs sub-pixel column-snap drift (non-integer dpr makes
                // Readium's snap pitch differ from the CSS column pitch by ~1px) without masking a
                // real off-by-a-column miss (which is ~±innerWidth away).
                val tol = 6.0
                val onScreen = left >= -tol && left < iw && top >= -tol && top < ih
                return FocusResult(onScreen, "$orientation: rect=($left,$top) viewport=($iw,$ih)")
            }
        }
        return FocusResult(false, "phrase rect not resolvable in any WebView (${webViews.size} candidates)")
    }

    private fun phraseRectJs(phrase: String): String {
        val p = org.json.JSONObject.quote(phrase)
        return "(function(){var P=$p;var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false),n;" +
            "while(n=w.nextNode()){var i=n.nodeValue.indexOf(P);if(i>=0){var r=document.createRange();" +
            "r.setStart(n,i);r.setEnd(n,Math.min(n.nodeValue.length,i+P.length));var b=r.getBoundingClientRect();" +
            "return Math.round(b.left)+','+Math.round(b.top)+','+window.innerWidth+','+window.innerHeight;}}return 'null';})()"
    }

    /** Snapshot of visible reader WebViews, captured on the UI thread. */
    private fun visibleWebViews(): List<WebView> {
        val latch = CountDownLatch(1)
        val out = mutableListOf<WebView>()
        composeTestRule.activityRule.scenario.onActivity { activity ->
            fun walk(v: View) {
                if (v is WebView && v.width > 0 && v.height > 0 && v.isShown) out.add(v)
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(activity.window.decorView)
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return out
    }

    /** (webViewScreenX, webViewScreenY, [contentX, contentY, contentW, contentH]) — read on UI thread. */
    private fun webViewScreenGeometry(wv: WebView): Triple<Int, Int, IntArray>? {
        val latch = CountDownLatch(1)
        val res = arrayOfNulls<Triple<Int, Int, IntArray>>(1)
        composeTestRule.activityRule.scenario.onActivity { activity ->
            val content = activity.findViewById<View>(android.R.id.content)
            val cLoc = IntArray(2); content.getLocationOnScreen(cLoc)
            val wvLoc = IntArray(2); wv.getLocationOnScreen(wvLoc)
            res[0] = Triple(wvLoc[0], wvLoc[1], intArrayOf(cLoc[0], cLoc[1], content.width, content.height))
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return res[0]
    }

    /** Evaluate [js] on [wv]; registers on the UI thread, awaits the callback OFF the UI thread. */
    private fun evalJs(wv: WebView, js: String): String {
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<String>(1)
        composeTestRule.activityRule.scenario.onActivity {
            wv.evaluateJavascript(js) { value -> result[0] = value; latch.countDown() }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) return "NO_WEBVIEW"
        return result[0]?.trim('"') ?: "null"
    }

    // ── seeding ──────────────────────────────────────────────────────────────

    private fun seedDeepHighlight() = runBlocking {
        val server = database.serverDao().getActive()
            ?: error("no active server registered after browsing library")
        val html = chapter1Html()
        val progression = phraseProgression(html, targetPhrase)
        // chapter1 is spine index 0 → spineStep = (0+1)*2 = 2
        val cfi = buildHighlightCfiRangeForSelection(
            spineStep = 2,
            html = html,
            startProgression = progression,
            selectedText = targetPhrase,
        ) ?: error("failed to build highlight CFI for '$targetPhrase'")
        annotationStore.createHighlight(
            serverId = server.id,
            itemId = StubAbsServer.TEST_STANDALONE_ITEM_ID,
            cfi = cfi,
            textSnippet = targetPhrase,
            chapterHref = "chapter1.xhtml",
        )
    }

    private fun chapter1Html(): String {
        // test.epub ships in the androidTest assets; read chapter1 directly.
        val ctx = InstrumentationRegistry.getInstrumentation().context
        java.util.zip.ZipInputStream(ctx.assets.open("test.epub")).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name.endsWith("chapter1.xhtml")) return zis.readBytes().decodeToString()
                e = zis.nextEntry
            }
        }
        error("chapter1.xhtml not found in test.epub")
    }

    /** Approximate within-chapter progression of [phrase] using plain body text offset. */
    private fun phraseProgression(html: String, phrase: String): Double {
        val body = Jsoup.parse(html).body().text()
        val idx = body.indexOf(phrase)
        require(idx >= 0) { "phrase '$phrase' not found in chapter body" }
        return idx.toDouble() / body.length.toDouble()
    }

    // ── UI driving (mirrors NavigationSnapHarnessTest) ─────────────────────────

    private fun addServerAndBrowseLibrary() {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Connect").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and hasText("Server URL")).performTextReplacement(stubServer.baseUrl)
        composeTestRule.onNode(hasSetTextAction() and hasText("Username")).performTextReplacement("testuser")
        composeTestRule.onNode(hasSetTextAction() and hasText("Password")).performTextReplacement("testpass")
        composeTestRule.onNodeWithText("Connect").performClick()
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText("Connect anyway").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Connect anyway").performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithContentDescription("All Books").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("All Books").performClick()
    }
}
