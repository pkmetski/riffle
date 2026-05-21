package com.riffle.app.feature.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riffle.app.di.AppModule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.streamer.PublicationOpener
import java.io.File
import javax.inject.Inject

/**
 * Integration test: extract TOC from the bundled test EPUB and verify active entry detection.
 *
 * Opens the real test.epub via Readium's PublicationOpener, extracts its tableOfContents,
 * maps to TocEntry, and verifies that findActiveEntry resolves the correct entry for known hrefs.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TocIntegrationTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val tmp = TemporaryFolder()

    @Inject lateinit var assetRetriever: AssetRetriever
    @Inject lateinit var publicationOpener: PublicationOpener

    @Before
    fun setUp() { hiltRule.inject() }

    @Test
    fun testEpubHasThreeTopLevelChapters() = runTest {
        val pub = openTestEpub()

        val entries = pub.tableOfContents.toTocEntries()

        assertEquals(3, entries.size)
        assertTrue("Expected chapter 1 title", entries[0].title.contains("1", ignoreCase = true))
        assertTrue("Expected chapter 2 title", entries[1].title.contains("2", ignoreCase = true))
        assertTrue("Expected chapter 3 title", entries[2].title.contains("3", ignoreCase = true))
    }

    @Test
    fun chapter1HasSubsections() = runTest {
        val pub = openTestEpub()

        val entries = pub.tableOfContents.toTocEntries()

        assertTrue("Chapter 1 should have children", entries[0].children.isNotEmpty())
        assertEquals(3, entries[0].children.size)
    }

    @Test
    fun findActiveEntryResolvesChapter3FromHref() = runTest {
        val pub = openTestEpub()
        val entries = pub.tableOfContents.toTocEntries()

        val chapter3HrefFragment = "chapter3"
        val chapter3Entry = entries.find { it.href.contains(chapter3HrefFragment) }
        assertNotNull("Expected a chapter 3 entry in TOC", chapter3Entry)

        val active = findActiveEntry(entries, chapter3Entry!!.href)

        assertNotNull("findActiveEntry should find chapter 3", active)
        assertTrue("Active entry href should contain 'chapter3'", active!!.href.contains("chapter3"))
    }

    @Test
    fun findActiveEntryResolvesSubsectionFromHref() = runTest {
        val pub = openTestEpub()
        val entries = pub.tableOfContents.toTocEntries()
        val chapter1 = entries.first()
        val section11 = chapter1.children.firstOrNull { it.href.contains("#") }
        assertNotNull("Expected a subsection entry under chapter 1", section11)

        val active = findActiveEntry(entries, section11!!.href)

        assertNotNull("findActiveEntry should find the subsection", active)
        assertEquals(section11.href, active!!.href)
    }

    @Test
    fun chapter2HasThreeSubsections() = runTest {
        val pub = openTestEpub()
        val entries = pub.tableOfContents.toTocEntries()
        val chapter2 = entries.find { it.href.contains("chapter2") }
        assertNotNull("Expected a chapter 2 entry", chapter2)
        assertEquals("Chapter 2 should have 3 subsections", 3, chapter2!!.children.size)
    }

    @Test
    fun railSegmentsForChapter2SubsectionHref() = runTest {
        val pub = openTestEpub()
        val entries = pub.tableOfContents.toTocEntries()

        val segments = buildRailSegments(entries, "chapter2.xhtml#s3")
        assertEquals(3, segments.size)

        val activeIndex = findActiveSegmentIndex(segments, "chapter2.xhtml#s3")
        assertEquals("Segment 3 (index 2) should be active for chapter2.xhtml#s3", 2, activeIndex)
    }

    private suspend fun openTestEpub() = run {
        val context = InstrumentationRegistry.getInstrumentation().context
        val epubBytes = context.assets.open("test.epub").use { it.readBytes() }
        val epubFile = File(tmp.newFolder("epub"), "test.epub").also { it.writeBytes(epubBytes) }
        val url = AbsoluteUrl("file://${epubFile.absolutePath}")!!
        val asset = (assetRetriever.retrieve(url) as Try.Success).value
        (publicationOpener.open(asset, allowUserInteraction = false) as Try.Success).value
    }
}
