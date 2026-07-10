package com.riffle.app.feature.reader

import com.riffle.core.domain.epubCfiToSpineIndex
import com.riffle.core.domain.normalizeEpubHref
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riffle.core.data.AnnotationStoreImpl
import com.riffle.core.database.RiffleDatabase
import com.riffle.core.database.SourceEntity
import com.riffle.core.domain.DeviceIdStore
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * End-to-end re-render-on-reopen for highlights against a real EPUB, without driving WebView text
 * selection. Exercises the load-bearing path (ADR 0024): a selection's start progression + text →
 * a persisted CFI **range**, then — as on reopen — that range re-anchored back to a within-chapter
 * progression and spine index. The Readium decoration itself is rendered by the same
 * DecorableNavigator mechanism the search/readaloud highlights already use.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AnnotationReopenInstrumentedTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val tmp = TemporaryFolder()

    @Inject lateinit var assetRetriever: AssetRetriever
    @Inject lateinit var publicationOpener: PublicationOpener

    private lateinit var db: RiffleDatabase
    private lateinit var store: AnnotationStoreImpl

    private class FixedDeviceIdStore : DeviceIdStore {
        override suspend fun getOrCreate(): String = "device-test"
    }

    @Before
    fun setUp() {
        hiltRule.inject()
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            RiffleDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = AnnotationStoreImpl(
            dao = db.annotationDao(),
            deviceIdStore = FixedDeviceIdStore(),
            clock = { 1_000L },
            idGenerator = { "uuid-1" },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun highlightPersistsAsRangeAndReanchorsOnReopen() = runTest {
        val epubFile = copyTestEpub()
        val pub = openTestEpub(epubFile)
        // annotations.sourceId is a FK → seed the ABS server.
        db.sourceDao().upsert(
            SourceEntity("abs1", "http://abs1", isActive = true, insecureConnectionAllowed = false, username = "u"),
        )

        // Chapter 0 of the test EPUB.
        val spineIndex = 0
        val link = pub.readingOrder[spineIndex]
        val html = readChapterHtml(epubFile, normalizeEpubHref(link.href.toString()))
        assertNotNull("test EPUB chapter 0 HTML should be readable", html)
        val spineStep = (spineIndex + 1) * 2

        // A selection a little way into the chapter.
        val startProgression = 0.1
        val selectedText = "the"
        val cfiRange = buildHighlightCfiRangeForSelection(spineStep, html!!, startProgression, selectedText)
        assertNotNull("a range CFI should be built from the selection", cfiRange)
        assertTrue("a highlight CFI must be a range (comma-separated)", cfiRange!!.contains(','))

        // Create (persist) the highlight, scoped to the ABS item.
        store.createHighlight(
            sourceId = "abs1",
            itemId = "item-1",
            cfi = cfiRange,
            textSnippet = selectedText,
            chapterHref = link.href.toString(),
            originFontFamily = "Georgia, serif",
        )

        // Reopen: read back what's stored and re-anchor it, exactly as the ViewModel does.
        val reopened = store.observeHighlights("abs1", "item-1").first()
        assertEquals(1, reopened.size)
        val stored = reopened[0]
        assertEquals(cfiRange, stored.cfi)
        assertEquals(selectedText, stored.textSnippet)

        assertEquals(
            "stored CFI must re-anchor to the original spine item on reopen",
            spineIndex,
            epubCfiToSpineIndex(stored.cfi),
        )
        val reanchored = highlightStartProgression(stored.cfi, html)
        assertNotNull("stored CFI must re-anchor to a within-chapter progression", reanchored)
        assertEquals(
            "re-anchored start progression must recover the original selection start",
            startProgression,
            reanchored!!,
            0.02,
        )
    }

    private fun copyTestEpub(): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        val epubBytes = context.assets.open("test.epub").use { it.readBytes() }
        return File(tmp.newFolder("epub"), "test.epub").also { it.writeBytes(epubBytes) }
    }

    private suspend fun openTestEpub(epubFile: File) = run {
        val url = AbsoluteUrl("file://${epubFile.absolutePath}")!!
        val asset = (assetRetriever.retrieve(url) as Try.Success).value
        (publicationOpener.open(asset, allowUserInteraction = false) as Try.Success).value
    }

    private fun readChapterHtml(epubFile: File, entryPath: String): String? =
        ZipFile(epubFile).use { zip ->
            zip.getEntry(entryPath)?.let { entry ->
                zip.getInputStream(entry).bufferedReader().readText()
            }
        }
}
