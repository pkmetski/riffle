package com.riffle.app.feature.reader

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
import androidx.test.platform.app.InstrumentationRegistry
import com.riffle.app.MainActivity
import com.riffle.app.harness.ReaderSemanticMatchers
import com.riffle.app.harness.StubAbsServer
import com.riffle.core.data.di.EpubCacheStore
import com.riffle.core.database.RiffleDatabase
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReaderOrientation
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end regression loop for noted-highlight glyphs. Unlike the renderer unit tests, this
 * opens the real EPUB in Readium and inspects the live chapter WebView after navigation/reflow.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NoteGlyphRenderHarnessTest {

    private companion object {
        const val NARROW_MARGINS = 0.2f
        const val GLYPH_VIEWPORT_INSET_PX = 12.0
    }

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var database: RiffleDatabase
    @EpubCacheStore @Inject lateinit var epubCacheStore: LocalStore
    @Inject lateinit var annotationStore: AnnotationStore
    @Inject lateinit var formattingPreferencesStore: FormattingPreferencesStore

    private val stubServer = StubAbsServer()
    private val targetPhrase = "Section 1.3: Development"

    @Before
    fun setUp() {
        stubServer.start()
        hiltRule.inject()
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
        composeTestRule.activityRule.scenario.close()
        Runtime.getRuntime().gc()
        Thread.sleep(400)
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

        val deadline = System.currentTimeMillis() + 20_000
        var lastDetails = "no WebView"
        val glyphSelector = if (orientation == ReaderOrientation.Continuous) {
            "[data-riffle-note-glyph]"
        } else {
            ".riffle-note-glyph-icon"
        }
        while (System.currentTimeMillis() < deadline) {
            val details = visibleWebViews().map { webView ->
                evalJs(
                    webView,
                    """
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
                    """.trimIndent(),
                ).trim('"')
            }
            lastDetails = details.joinToString()
            if (details.any { detail ->
                    detail != "missing" &&
                        evalGlyphVisible(detail)
                }
            ) return
            Thread.sleep(250)
        }
        assertTrue("$orientation noted highlight glyph never became visible: $lastDetails", false)
    }

    private fun evalGlyphVisible(detail: String): Boolean {
        val fields = detail.split('|').associate { field ->
            field.substringBefore('=') to field.substringAfter('=')
        }
        val left = fields["left"]?.toDoubleOrNull() ?: return false
        val top = fields["top"]?.toDoubleOrNull() ?: return false
        val right = fields["right"]?.toDoubleOrNull() ?: return false
        val bottom = fields["bottom"]?.toDoubleOrNull() ?: return false
        val width = fields["iw"]?.toDoubleOrNull() ?: return false
        val height = fields["ih"]?.toDoubleOrNull() ?: return false
        return left >= GLYPH_VIEWPORT_INSET_PX && right <= width &&
            bottom > 0 && top < height &&
            fields["mask"]?.let { it.isNotBlank() && it != "none" } == true &&
            fields["bg"] != "rgba(0, 0, 0, 0)" &&
            (fields["opacity"]?.toDoubleOrNull() ?: 0.0) > 0.0
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
