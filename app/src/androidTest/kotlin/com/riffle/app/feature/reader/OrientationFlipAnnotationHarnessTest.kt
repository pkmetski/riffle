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
import androidx.compose.ui.test.onNodeWithTag
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
 * Regression: an annotation created (or visible) in CONTINUOUS mode used to "disappear" when the
 * user switched to paginated or scroll mode — it stayed in the database (and re-appeared on book
 * reopen) but never repainted as a Readium decoration on the live page.
 *
 * Mechanism: the Readium fragment lives inside an AndroidView that is kept mounted across every
 * orientation (zero-height in continuous mode, full-size otherwise). That AndroidView's factory
 * only runs once, so `readiumPresenter?.attach(fragment)` only ever attaches the FIRST presenter.
 * Opening the book in continuous mode means that first presenter is null, and every later flip
 * out of continuous creates a fresh presenter that never sees the fragment — so its
 * `applyDecorations` calls fall through the `fragment as? DecorableNavigator ?: return` guard
 * and the highlight is never drawn.
 *
 * This test seeds a highlight, opens the reader in continuous mode, then flips to Horizontal
 * mid-session and asserts the Readium decoration (`.riffle-highlight-tint`) appears in the
 * fragment's WebView. Mirrors the structure of [ContinuousAnnotationRenderHarnessTest].
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OrientationFlipAnnotationHarnessTest {

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
        // Match ContinuousAnnotationRenderHarnessTest's @Before — start MainActivity in Horizontal,
        // not Continuous, so the Connect screen renders in the layout context the other tests use.
        // The @Test flips to Continuous BEFORE seeding so the reader opens in continuous mode.
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
        // Reset orientation to Horizontal so subsequent tests on the shared APK install start
        // in a sane mode (matching what ContinuousAnnotationRenderHarnessTest's tearDown does).
        runBlocking {
            val prefs = formattingPreferencesStore.preferences.first()
            formattingPreferencesStore.update(prefs.copy(orientation = ReaderOrientation.Horizontal))
        }
    }

    @Test
    fun flippingContinuousToHorizontal_repaintsTheHighlight() {
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
        // Let continuous render its `<mark data-riffle-ann>` so we know the seeded highlight is
        // alive before we flip — a bug in the seed would otherwise masquerade as a flip bug.
        Thread.sleep(6_000)
        val continuousMarkCount = totalMatches("[data-riffle-ann]")
        assertTrue(
            "preflight: continuous mode failed to render the seeded highlight (got 0 marks); " +
                "the flip test would be meaningless without a baseline. See " +
                "ContinuousAnnotationRenderHarnessTest if this fails on its own.",
            continuousMarkCount > 0,
        )

        // Flip mid-session via the in-reader Format panel — writing the global store from outside
        // doesn't propagate (FormattingSession.updateFormatting bypasses the combined-store flow
        // and writes _formattingPreferences directly, so only the UI path actually toggles the
        // live reader). Reveal the top bar first so the Format icon becomes hittable.
        composeTestRule.onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithContentDescription("Format").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("Format").performClick()
        // The settings sheet opens on the Formatting tab; orientation lives under Display.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Display").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Display").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithContentDescription("Paginated reading orientation")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("Paginated reading orientation").performClick()
        // Drive recompositions until the orientation flip lands. We can't Thread.sleep here because
        // the Compose test rule runs composition on the same thread as the test body — a blocking
        // sleep would freeze recomposition. waitUntil idles the runtime between checks and
        // dispatches state updates that fell out of updateFormatting -> _formattingPreferences ->
        // effectiveFormattingPreferences -> collectAsState.
        val tintCount = runCatching {
            composeTestRule.waitUntil(timeoutMillis = 15_000) {
                totalMatches(".riffle-highlight-tint") > 0
            }
            totalMatches(".riffle-highlight-tint")
        }.getOrDefault(0)
        val webViews = allWebViews()
        val perWebView = webViews.map { wv ->
            val cls = evalJs(wv, "document.querySelectorAll('.riffle-highlight-tint').length").trim('"')
            val href = evalJs(wv, "(window.location && window.location.pathname) || ''").trim('"').ifBlank { "<none>" }
            "[href=$href tint=$cls]"
        }
        assertTrue(
            "after flipping continuous → horizontal the Readium fragment must repaint the " +
                "seeded annotation as `.riffle-highlight-tint`. Got 0 tints across " +
                "${webViews.size} WebViews: $perWebView. The most likely cause is that the new " +
                "ReadiumPresenter was never attached to the fragment (factory runs only once).",
            tintCount > 0,
        )
    }

    private fun totalMatches(selector: String): Int {
        val webViews = allWebViews()
        return webViews.sumOf { wv ->
            evalJs(wv, "document.querySelectorAll('$selector').length")
                .trim('"').toIntOrNull() ?: 0
        }
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

    private fun seedDeepHighlight() = runBlocking {
        val server = database.serverDao().getActive()
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
            serverId = server.id,
            itemId = StubAbsServer.TEST_STANDALONE_ITEM_ID,
            cfi = cfi,
            textSnippet = targetPhrase,
            chapterHref = "chapter1.xhtml",
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
