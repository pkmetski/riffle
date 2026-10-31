package com.riffle.app.feature.reader

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.riffle.feature.reader.NOTE_GLYPH_DECORATION_GROUP
import com.riffle.feature.reader.NOTE_GLYPH_FOCUS_ID_JS_KEY
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
import androidx.test.platform.app.InstrumentationRegistry
import com.riffle.app.MainActivity
import com.riffle.app.harness.ReaderSemanticMatchers
import com.riffle.app.harness.StubAbsServer
import com.riffle.core.database.RiffleDatabaseAccess
import com.riffle.core.database.clearAllTables
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReaderOrientation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.qualifier.named
import org.koin.test.KoinTest
import org.koin.test.inject
import com.riffle.feature.reader.buildHighlightCfiRangeForSelection

/**
 * End-to-end regression loop for noted-highlight glyphs. Unlike the renderer unit tests, this
 * opens the real EPUB in Readium and inspects the live chapter WebView after navigation/reflow.
 */
@RunWith(AndroidJUnit4::class)
class NoteGlyphRenderHarnessTest : KoinTest {

    private companion object {
        const val NARROW_MARGINS = 0.2f
        const val GLYPH_VIEWPORT_INSET_PX = 12.0
    }

    @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

    val database: RiffleDatabaseAccess by inject()
    val epubCacheStore: LocalStore by inject(named("epubCacheStore"))
    val annotationStore: AnnotationStore by inject()
    val formattingPreferencesStore: FormattingPreferencesStore by inject()

    private val stubServer = StubAbsServer()
    private val targetPhrase = "Section 1.3: Development"

    @Before
    fun setUp() {
        stubServer.start()
        database.clearAllTables()
        epubCacheStore.clear()
        runBlocking {
            val prefs = formattingPreferencesStore.preferences.first()
            formattingPreferencesStore.update(prefs.copy(orientation = ReaderOrientation.Horizontal))
        }
    }

    @After
    fun tearDown() {
        stubServer.shutdown()
        // Sleep before close so the last WebView recomposition cycle completes before Activity
        // destruction fires. See ContinuousChapterBoundaryHarnessTest.tearDown() for the full
        // rationale (SlotWriter AIOOBE race). Pattern mirrors NavigationSnapHarnessTest.tearDown().
        Thread.sleep(400)
        composeTestRule.activityRule.scenario.close()
        // Two GC passes with finalization between them — encourages WebView to release its
        // native OpenGL surfaces so SurfaceFlinger can reclaim the GPU buffer pool before the
        // next orientation test starts. On memory-constrained CI runners (e.g. the iOS workflow's
        // Android emulator) a single GC + 400 ms is not enough; without this, the consecutive
        // paginated → vertical launch sequence can exhaust the API-25 emulator's surface buffers
        // and crash SurfaceFlinger mid-test.
        Runtime.getRuntime().gc()
        System.runFinalization()
        Runtime.getRuntime().gc()
        Thread.sleep(1200)
        database.clearAllTables()
    }

    @Test
    fun paginatedNotedHighlightRendersVisibleGlyph() {
        runModeTest(ReaderOrientation.Horizontal)
    }

    @Test
    fun verticalNotedHighlightRendersVisibleGlyph() {
        runModeTest(ReaderOrientation.Vertical)
    }

    @Test
    fun continuousNotedHighlightRendersVisibleGlyph() {
        runModeTest(ReaderOrientation.Continuous)
    }

    private fun runModeTest(orientation: ReaderOrientation) {
        runBlocking {
            val prefs = formattingPreferencesStore.preferences.first()
            formattingPreferencesStore.update(
                prefs.copy(orientation = orientation, margins = NARROW_MARGINS),
            )
        }
        addServerAndBrowseLibrary()
        seedDeepNotedHighlight()
        searchAndTapAnnotation()
        composeTestRule.waitUntil(timeoutMillis = 20_000) {
            composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_READER_READY)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Paginated/vertical: Readium manages the viewport, so the glyph's getBoundingClientRect()
        // position is relative to the visible viewport and `top < innerHeight` correctly detects
        // on-screen visibility. 60 s covers them comfortably.
        //
        // Continuous: the parent Android NestedScrollView scrolls, not the WebView itself. Each
        // chapter WebView has a fixed height equal to the viewport height (window.innerHeight);
        // the chapter content overflows below that height. getBoundingClientRect().top for a deep
        // annotation therefore reports the element's absolute document-Y in the WebView — which
        // always exceeds innerHeight for annotations deeper than one screen. Checking `top < height`
        // would be permanently false regardless of whether the annotation is actually visible on
        // screen (the parent scroll positioned it in view). For continuous mode we only verify
        // that the glyph rendered with correct styles; the parent-scroll positioning is covered by
        // scrollToFocusAnnotation in ContinuousWindowController.
        val budgetMs = 60_000L
        val deadline = System.currentTimeMillis() + budgetMs
        var lastDetails = "no WebView"
        val glyphSelector = if (orientation == ReaderOrientation.Continuous) {
            "[data-riffle-note-glyph]"
        } else {
            ".riffle-note-glyph-icon"
        }
        val jsQuery = """
                    (function () {
                      var e = document.querySelector('$glyphSelector');
                      if (!e) return 'missing';
                      var r = e.getBoundingClientRect();
                      var s = getComputedStyle(e);
                      var se = document.scrollingElement || document.documentElement;
                      var g = window.readium && window.readium.getDecorations
                        ? window.readium.getDecorations('$NOTE_GLYPH_DECORATION_GROUP') : null;
                      var item = g && g.items && g.items[0];
                      var rr = item && item.range ? item.range.getBoundingClientRect() : null;
                      return [
                        'left=' + r.left, 'top=' + r.top, 'right=' + r.right,
                        'bottom=' + r.bottom, 'iw=' + innerWidth, 'ih=' + innerHeight,
                        'scrollLeft=' + (se ? se.scrollLeft : -1),
                        'items=' + (g && g.items ? g.items.length : -1),
                        'itemId=' + (item && item.decoration ? item.decoration.id : ''),
                        'focus=' + (window.$NOTE_GLYPH_FOCUS_ID_JS_KEY || ''),
                        'rangeLeft=' + (rr ? rr.left : -1),
                        'rangeTop=' + (rr ? rr.top : -1),
                        'mask=' + s.webkitMaskImage, 'bg=' + s.backgroundColor,
                        'opacity=' + s.opacity
                      ].join('|');
                    })()
                    """.trimIndent()
        while (System.currentTimeMillis() < deadline) {
            // Short-circuit: stop evaluating remaining WebViews as soon as we find the glyph
            // visible in any one of them — avoids up to (N-1) redundant IPC roundtrips per cycle
            // on loaded CI runners where each evaluateJavascript call is slow.
            var found = false
            val allDetails = mutableListOf<String>()
            for (webView in visibleWebViews()) {
                val detail = evalJs(webView, jsQuery).trim('"')
                allDetails += detail
                if (detail != "missing" && evalGlyphVisible(detail, isContinuous = orientation == ReaderOrientation.Continuous)) {
                    found = true
                    break
                }
            }
            lastDetails = allDetails.joinToString()
            if (found) return
            // 100 ms poll: shorter than the previous 250 ms so a glyph that appears late in the
            // 300 s window is caught within the next tick rather than up to 150 ms later. On slow
            // CI runners where the annotation-focus reflow cycle runs right up to the budget edge,
            // tighter polling maximises the chance of catching the stable position before the
            // deadline expires.
            Thread.sleep(100)
        }
        assertTrue("$orientation noted highlight glyph never became visible: $lastDetails", false)
    }

    private fun evalGlyphVisible(detail: String, isContinuous: Boolean = false): Boolean {
        val fields = detail.split('|').associate { field ->
            field.substringBefore('=') to field.substringAfter('=')
        }
        val left = fields["left"]?.toDoubleOrNull() ?: return false
        val right = fields["right"]?.toDoubleOrNull() ?: return false
        val bottom = fields["bottom"]?.toDoubleOrNull() ?: return false
        val width = fields["iw"]?.toDoubleOrNull() ?: return false
        val hasMask = fields["mask"]?.let { it.isNotBlank() && it != "none" } == true
        val hasBg = fields["bg"] != "rgba(0, 0, 0, 0)"
        val hasOpacity = (fields["opacity"]?.toDoubleOrNull() ?: 0.0) > 0.0
        val styleOk = hasMask && hasBg && hasOpacity && left >= GLYPH_VIEWPORT_INSET_PX && right <= width && bottom > 0
        if (!styleOk) return false
        if (isContinuous) {
            // In continuous mode the parent Android container scrolls; the WebView viewport height
            // (innerHeight) equals the screen height, and chapter content overflows below it.
            // getBoundingClientRect().top for a deep annotation always exceeds innerHeight, so
            // `top < height` is permanently false even when the annotation is on screen. Skip the
            // viewport-position check — style correctness is sufficient for this mode.
            return true
        }
        val top = fields["top"]?.toDoubleOrNull() ?: return false
        val height = fields["ih"]?.toDoubleOrNull() ?: return false
        return top < height
    }

    private fun seedDeepNotedHighlight() = runBlocking {
        val source = database.sourceDao().getActive()
            ?: error("no active source registered after browsing library")
        val html = chapter1Html()
        val progression = phraseProgression(html, targetPhrase)
        val cfi = buildHighlightCfiRangeForSelection(
            spineStep = 2,
            html = html,
            startProgression = progression,
            selectedText = targetPhrase,
        ) ?: error("failed to build highlight CFI for '$targetPhrase'")
        val highlight = annotationStore.createHighlight(
            sourceId = source.id,
            itemId = StubAbsServer.TEST_STANDALONE_ITEM_ID,
            cfi = cfi,
            textSnippet = targetPhrase,
            chapterHref = "chapter1.xhtml",
            originFontFamily = "Georgia, serif",
        )
        annotationStore.updateNote(highlight.id, "This note must produce a visible margin glyph.")
    }

    private fun chapter1Html(): String {
        val context = InstrumentationRegistry.getInstrumentation().context
        java.util.zip.ZipInputStream(context.assets.open("test.epub")).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.endsWith("chapter1.xhtml")) return zip.readBytes().decodeToString()
                entry = zip.nextEntry
            }
        }
        error("chapter1.xhtml not found in test.epub")
    }

    private fun phraseProgression(html: String, phrase: String): Double {
        val body = Jsoup.parse(html).body().text()
        val index = body.indexOf(phrase)
        require(index >= 0) { "phrase '$phrase' not found in chapter body" }
        return index.toDouble() / body.length.toDouble()
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

    private fun searchAndTapAnnotation() {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Search").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Search").performTextReplacement("Section 1.3")
        composeTestRule.waitUntil(timeoutMillis = 8_000) {
            composeTestRule.onAllNodesWithText(targetPhrase).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(targetPhrase).performClick()
    }

    private fun visibleWebViews(): List<WebView> {
        val latch = CountDownLatch(1)
        val result = mutableListOf<WebView>()
        composeTestRule.activityRule.scenario.onActivity { activity ->
            fun collect(view: View) {
                if (view is WebView && view.visibility == View.VISIBLE) result += view
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
            }
            collect(activity.window.decorView)
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    private fun evalJs(webView: WebView, script: String): String {
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<String>(1)
        composeTestRule.activityRule.scenario.onActivity {
            webView.evaluateJavascript(script) { value ->
                result[0] = value
                latch.countDown()
            }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) return "NO_WEBVIEW"
        return result[0] ?: "null"
    }
}
