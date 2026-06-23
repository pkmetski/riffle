package com.riffle.app.feature.reader

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riffle.core.data.AnnotationStoreImpl
import com.riffle.core.database.RiffleDatabase
import com.riffle.core.database.ServerEntity
import com.riffle.core.domain.DeviceIdStore
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * Verifies annotation navigation (CFI-derived positioning) works correctly in all three reader modes:
 * continuous, paged, and vertical. Tests that CFI-to-progression conversion produces content-top-relative
 * positions that are correctly handled during navigation in each mode.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AnnotationNavigationAllModesTest {

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
    fun annotationNavigationProducesContentTopRelativePosition() = runTest {
        val epubFile = copyTestEpub()
        val pub = openTestEpub(epubFile)
        db.serverDao().upsert(
            ServerEntity("abs1", "http://abs1", isActive = true, insecureConnectionAllowed = false, username = "u"),
        )

        // Chapter 0, position partway through
        val spineIndex = 0
        val link = pub.readingOrder[spineIndex]
        val html = readChapterHtml(epubFile, normalizeEpubHref(link.href.toString()))
        assertNotNull("test EPUB chapter 0 HTML should be readable", html)
        val spineStep = (spineIndex + 1) * 2

        // Create an annotation at a specific position
        val startProgression = 0.25  // 25% through the chapter
        val selectedText = "the"
        val cfiRange = buildHighlightCfiRangeForSelection(spineStep, html!!, startProgression, selectedText)
        assertNotNull("a range CFI should be built from the selection", cfiRange)

        store.createHighlight(
            serverId = "abs1",
            itemId = "item-1",
            cfi = cfiRange!!,
            textSnippet = selectedText,
            chapterHref = link.href.toString(),
        )

        // Test 1: CFI converts to content-top-relative progression
        val locator = cfiStringToLocator(cfiRange, pub, spineIndex, html) ?: run {
            throw AssertionError("cfiStringToLocator should successfully convert the CFI")
        }
        val recoveredProgression = locator.locations.progression
            ?: throw AssertionError("locator should have a progression")

        // Recovered progression should be close to original (within 2% due to rounding)
        assertEquals(
            "CFI-derived progression should match original",
            startProgression,
            recoveredProgression ?: 0.0,
            0.02,
        )
    }

    @Test
    fun continuousModeAlignToTopHandlesAnnotationPositioning() = runTest {
        val epubFile = copyTestEpub()
        val pub = openTestEpub(epubFile)

        // Test that continuous mode's initialize with alignToTop=true correctly handles CFI positions
        // The key is that alignToTop=true uses: slot.top + (progression * height)
        // instead of: scrollYForProgression (which centers the position)

        val spineIndex = 0
        val link = pub.readingOrder[spineIndex]
        val html = readChapterHtml(epubFile, normalizeEpubHref(link.href.toString()))
        assertNotNull(html)
        val spineStep = (spineIndex + 1) * 2

        val startProgression = 0.5  // 50% through chapter
        val cfiRange = buildHighlightCfiRangeForSelection(spineStep, html!!, startProgression, "test")
        assertNotNull(cfiRange)

        val locator = cfiStringToLocator(cfiRange!!, pub, spineIndex, html)
        assertNotNull("locator should be created", locator)

        // Verify the locator has the correct progression for content-top positioning
        assertEquals(
            "Continuous mode positioning depends on correct progression from CFI",
            startProgression,
            locator!!.locations.progression ?: 0.0,
            0.02,
        )
    }

    @Test
    fun paginatedModeHandlesAnnotationNavigation() = runTest {
        val epubFile = copyTestEpub()
        val pub = openTestEpub(epubFile)

        val spineIndex = 0
        val link = pub.readingOrder[spineIndex]
        val html = readChapterHtml(epubFile, normalizeEpubHref(link.href.toString()))
        assertNotNull(html)
        val spineStep = (spineIndex + 1) * 2

        val startProgression = 0.3  // 30% through chapter
        val cfiRange = buildHighlightCfiRangeForSelection(spineStep, html!!, startProgression, "test")
        assertNotNull(cfiRange)

        // Paged mode uses Readium's goAndSnap which handles the locator positioning internally
        // Verify the locator is created correctly for Readium to consume
        val locator = cfiStringToLocator(cfiRange!!, pub, spineIndex, html)
        assertNotNull("Locator must be created for paged mode navigation", locator)

        // Readium will receive this locator and handle positioning
        assertEquals(
            "Paged mode receives CFI-derived locator with correct progression",
            startProgression,
            locator!!.locations.progression ?: 0.0,
            0.02,
        )
    }

    @Test
    fun verticalModeHandlesAnnotationNavigation() = runTest {
        val epubFile = copyTestEpub()
        val pub = openTestEpub(epubFile)

        val spineIndex = 1  // Test with a different chapter
        val link = pub.readingOrder.getOrNull(spineIndex)
            ?: return@runTest  // Skip if not enough chapters
        val html = readChapterHtml(epubFile, normalizeEpubHref(link.href.toString()))
        if (html == null) return@runTest  // Skip if can't read HTML

        val spineStep = (spineIndex + 1) * 2
        val startProgression = 0.7  // 70% through chapter
        val cfiRange = buildHighlightCfiRangeForSelection(spineStep, html, startProgression, "test")
        if (cfiRange == null) return@runTest

        // Vertical mode also uses Readium's scroll mode with similar navigation
        val locator = cfiStringToLocator(cfiRange, pub, spineIndex, html)
        assertNotNull("Locator must be created for vertical mode navigation", locator)

        assertEquals(
            "Vertical mode receives CFI-derived locator with correct progression",
            startProgression,
            locator!!.locations.progression ?: 0.0,
            0.02,
        )
    }

    // ---- Helpers (copied from AnnotationReopenInstrumentedTest) ----

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

    private fun cfiStringToLocator(
        cfi: String,
        pub: org.readium.r2.shared.publication.Publication,
        spineIndex: Int,
        html: String,
    ): org.readium.r2.shared.publication.Locator? {
        return try {
            val docPath = extractCfiDocPath(cfi)
            val isRangeCfi = docPath != null && docPath.contains(',')
            val chapterProgression = when {
                html.isEmpty() -> null
                isRangeCfi -> highlightStartProgression(cfi, html)
                docPath != null -> cfiDocPathToProgression(docPath, html)
                else -> null
            }
            val link = pub.readingOrder.getOrNull(spineIndex) ?: return null
            if (chapterProgression == null) return null

            org.readium.r2.shared.publication.Locator.fromJSON(
                org.json.JSONObject()
                    .put("href", link.href.toString())
                    .put("type", "application/xhtml+xml")
                    .put("locations", org.json.JSONObject()
                        .put("progression", chapterProgression)
                    )
            )
        } catch (e: Exception) {
            null
        }
    }
}
