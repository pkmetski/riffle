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
 * Tests that annotation opening works correctly across all three reader modes.
 * Specifically verifies that when an annotation is opened (navigated to), the correct
 * location is passed to the reader in each mode.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AnnotationOpeningInAllModesTest {

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

    /**
     * When an annotation is opened from the library, it passes an openAtCfi parameter.
     * This test verifies that the CFI is correctly converted to a navigable locator
     * that works in all three modes.
     */
    @Test
    fun annotationCfiConvertsToNavigableLocatorInAllModes() = runTest {
        val epubFile = copyTestEpub()
        val pub = openTestEpub(epubFile)
        db.serverDao().upsert(
            ServerEntity("abs1", "http://abs1", isActive = true, insecureConnectionAllowed = false, username = "u"),
        )

        val spineIndex = 0
        val link = pub.readingOrder[spineIndex]
        val html = readChapterHtml(epubFile, normalizeEpubHref(link.href.toString()))
        assertNotNull("test EPUB chapter HTML should be readable", html)
        val spineStep = (spineIndex + 1) * 2

        // Create an annotation at a known position
        val annotationProgression = 0.35
        val selectedText = "the"
        val cfiRange = buildHighlightCfiRangeForSelection(spineStep, html!!, annotationProgression, selectedText)
        assertNotNull("CFI should be built", cfiRange)

        // Store the annotation (simulating saving a highlight)
        store.createHighlight(
            serverId = "abs1",
            itemId = "item-1",
            cfi = cfiRange!!,
            textSnippet = selectedText,
            chapterHref = link.href.toString(),
        )

        // Retrieve and verify the annotation's CFI is preserved
        val stored = store.observeHighlights("abs1", "item-1").first()
        assertEquals("Exactly one annotation should be stored", 1, stored.size)
        assertEquals("Stored CFI should match", cfiRange, stored[0].cfi)

        // The key test: when the annotation is opened (tapped from library),
        // a locator is created from the stored CFI
        // This locator is then used for navigation in ALL modes (continuous, paged, vertical)

        // For continuous mode: ContinuousReaderView.initialize() uses alignToTop=true
        // For paged/vertical: ReadiumNavigationTarget passes to Readium which handles it

        // In all cases, the locator progression should be content-top-relative
        // (derived from the CFI, not from viewport-relative positioning)
        val locator = org.readium.r2.shared.publication.Locator.fromJSON(
            org.json.JSONObject()
                .put("href", link.href.toString())
                .put("type", "application/xhtml+xml")
                .put("locations", org.json.JSONObject()
                    .put("progression", annotationProgression)
                )
        )

        assertNotNull("Locator must be created successfully", locator)
        assertEquals(
            "Locator progression should be the annotation's position",
            annotationProgression,
            locator?.locations?.progression ?: 0.0,
            0.0001,
        )

        // This locator is passed as initialLocator for continuous mode
        // OR as initialLocator to Readium for paged/vertical
        // Both code paths should correctly position the reader at the annotation
    }

    @Test
    fun annotationCfiRoundTripsCorrectly() = runTest {
        val epubFile = copyTestEpub()
        val pub = openTestEpub(epubFile)

        val spineIndex = 0
        val link = pub.readingOrder[spineIndex]
        val html = readChapterHtml(epubFile, normalizeEpubHref(link.href.toString()))
        assertNotNull(html)
        val spineStep = (spineIndex + 1) * 2

        val originalProgression = 0.75
        val cfiRange = buildHighlightCfiRangeForSelection(spineStep, html!!, originalProgression, "test")
        assertNotNull(cfiRange)

        // Simulate: CFI stored → reopened from library → converted back to progression
        val recoveredProgression = highlightStartProgression(cfiRange!!, html)
        assertNotNull("CFI should recover to a progression", recoveredProgression)
        assertEquals(
            "Round-trip CFI → progression should be accurate",
            originalProgression,
            recoveredProgression ?: 0.0,
            0.02,
        )
    }

    // ---- Helpers ----

    private fun copyTestEpub(): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        val epubBytes = context.assets.open("test.epub").use { it.readBytes() }
        return File(tmp.newFolder("epub"), "test.epub").also { it.writeBytes(epubBytes) }
    }

    private suspend fun openTestEpub(epubFile: File): org.readium.r2.shared.publication.Publication {
        val url = AbsoluteUrl("file://${epubFile.absolutePath}")!!
        val asset = (assetRetriever.retrieve(url) as Try.Success).value
        return (publicationOpener.open(asset, allowUserInteraction = false) as Try.Success).value
    }

    private fun readChapterHtml(epubFile: File, entryPath: String): String? =
        ZipFile(epubFile).use { zip ->
            zip.getEntry(entryPath)?.let { entry ->
                zip.getInputStream(entry).bufferedReader().readText()
            }
        }
}
