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
 * Regression: in continuous mode, persisted highlights must render as `<mark data-riffle-ann>`
 * elements in the chapter WebView's DOM. The user reported that they only appear in the
 * annotations list, never on the page.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ContinuousAnnotationRenderHarnessTest {

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
        // Reset orientation so tests don't leak each other's state.
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
        // Reset orientation so subsequent tests on the shared APK install start in Horizontal —
        // leaving it in Continuous breaks any test that asserts column-pagination scrollLeft.
        runBlocking {
            val prefs = formattingPreferencesStore.preferences.first()
            formattingPreferencesStore.update(prefs.copy(orientation = ReaderOrientation.Horizontal))
        }
    }

    @Test
    fun continuousMode_renderedHighlight_appearsAsMarkElement() {
        runBlocking {
            val prefs = formattingPreferencesStore.preferences.first()
            formattingPreferencesStore.update(prefs.copy(orientation = ReaderOrientation.Continuous))
        }
        addServerAndBrowseLibrary()
        seedDeepHighlight()
        searchAndTapAnnotation()
        composeTestRule.waitUntil(timeoutMillis = 20_000) {
            composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_READER_READY)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Continuous-mode decoration is applied asynchronously per-WebView as each chapter
        // loads; poll instead of a fixed sleep so a slow CI emulator doesn't flake.
        val pollDeadline = System.currentTimeMillis() + 20_000
        var webViews: List<WebView> = emptyList()
        var totalMarks = 0
        var perWebView: List<String> = emptyList()
        while (System.currentTimeMillis() < pollDeadline) {
            webViews = allWebViews()
            if (webViews.isNotEmpty()) {
                perWebView = webViews.map { wv ->
                    val count = evalJs(wv, "document.querySelectorAll('[data-riffle-ann]').length").trim('"')
                    val href = evalJs(wv, "(window.location && window.location.pathname) || ''").trim('"').ifBlank { "<none>" }
                    "[href=$href marks=$count]"
                }
                totalMarks = webViews.sumOf { wv ->
                    evalJs(wv, "document.querySelectorAll('[data-riffle-ann]').length").trim('"').toIntOrNull() ?: 0
                }
                if (totalMarks > 0) break
            }
            Thread.sleep(500)
        }
        assertTrue("expected at least one WebView in hierarchy", webViews.isNotEmpty())
        assertTrue(
            "expected at least one <mark data-riffle-ann> across ${webViews.size} WebViews; got: $perWebView",
            totalMarks > 0,
        )
    }

    private fun allWebViews(): List<WebView> {
        val latch = CountDownLatch(1)
        val out = mutableListOf<WebView>()
        composeTestRule.activityRule.scenario.onActivity { activity ->
            fun walk(v: View) {
                if (v is WebView) out.add(v)
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(activity.window.decorView)
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return out
    }

    // ── helpers (copied from AnnotationFocusHarnessTest) ──────────────────────────

    private fun seedDeepHighlight() = runBlocking {
        val server = database.sourceDao().getActive()
            ?: error("no active server registered after browsing library")
        val html = chapter1Html()
        val progression = phraseProgression(html, targetPhrase)
        val cfi = buildHighlightCfiRangeForSelection(
            spineStep = 2,
            html = html,
            startProgression = progression,
            selectedText = targetPhrase,
        ) ?: error("failed to build highlight CFI for '$targetPhrase'")
        annotationStore.createHighlight(
            sourceId = server.id,
            itemId = StubAbsServer.TEST_STANDALONE_ITEM_ID,
            cfi = cfi,
            textSnippet = targetPhrase,
            chapterHref = "chapter1.xhtml",
            originFontFamily = "Georgia, serif",
        )
    }

    private fun chapter1Html(): String {
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

    private fun phraseProgression(html: String, phrase: String): Double {
        val body = Jsoup.parse(html).body().text()
        val idx = body.indexOf(phrase)
        require(idx >= 0) { "phrase '$phrase' not found in chapter body" }
        return idx.toDouble() / body.length.toDouble()
    }

    private fun addServerAndBrowseLibrary() {
        // With no sources, HomeScreen navigates to the Source Type picker (#435).
        // Tap the Audiobookshelf card to advance to the connect form.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Audiobookshelf").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Audiobookshelf").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Connect").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and hasText("Source URL")).performTextReplacement(stubServer.baseUrl)
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

    private fun evalJs(wv: WebView, js: String): String {
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<String>(1)
        composeTestRule.activityRule.scenario.onActivity {
            wv.evaluateJavascript(js) { value -> result[0] = value; latch.countDown() }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) return "NO_WEBVIEW"
        return result[0] ?: "null"
    }
}
