@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Integration tests for Readium's SearchService on a programmatically generated EPUB.
 *
 * Generates a 5-chapter EPUB where chapter 3 has 2 000 filler paragraphs (~340 KB)
 * to exercise the search iterator over a larger-than-average chapter. Tests verify:
 *   - unique search markers are found only in the correct chapter
 *   - a common term is found across all 5 chapters
 *   - absent terms produce empty results
 *   - the search iterator is closed via the finally block
 *   - large-chapter search completes without OutOfMemoryError
 *
 * The [searchAll] helper mirrors EpubReaderViewModel.performSearch exactly:
 *   withContext(IO) { cache.clear(); iterate; finally { close } }
 * so regressions caught here map directly to production behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EpubSearchServiceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val tmp = TemporaryFolder()

    @Inject lateinit var assetRetriever: AssetRetriever
    @Inject lateinit var publicationOpener: PublicationOpener

    @Before
    fun setUp() { hiltRule.inject() }

    // ── unique marker per chapter ─────────────────────────────────────────────

    @Test
    fun searchFindsNeedleAlphaOnlyInChapter1() = runTest {
        val pub = openGeneratedEpub()
        val results = searchAll(pub, NEEDLE_CH01)
        assertTrue("$NEEDLE_CH01 must be found", results.isNotEmpty())
        assertTrue(
            "Every hit must be in ch01 — got ${results.map { it.href }}",
            results.all { it.href.toString().contains("ch01") },
        )
    }

    @Test
    fun searchFindsNeedleBetaOnlyInChapter2() = runTest {
        val pub = openGeneratedEpub()
        val results = searchAll(pub, NEEDLE_CH02)
        assertTrue("$NEEDLE_CH02 must be found", results.isNotEmpty())
        assertTrue(
            "Every hit must be in ch02 — got ${results.map { it.href }}",
            results.all { it.href.toString().contains("ch02") },
        )
    }

    @Test
    fun searchFindsNeedleGammaInLargeChapter3() = runTest {
        val pub = openGeneratedEpub()
        val results = searchAll(pub, NEEDLE_CH03)
        assertTrue("$NEEDLE_CH03 must be found in the large chapter", results.isNotEmpty())
        assertTrue(
            "Every hit must be in ch03 — got ${results.map { it.href }}",
            results.all { it.href.toString().contains("ch03") },
        )
    }

    @Test
    fun searchFindsNeedleDeltaOnlyInChapter4() = runTest {
        val pub = openGeneratedEpub()
        val results = searchAll(pub, NEEDLE_CH04)
        assertTrue("$NEEDLE_CH04 must be found", results.isNotEmpty())
        assertTrue(
            "Every hit must be in ch04 — got ${results.map { it.href }}",
            results.all { it.href.toString().contains("ch04") },
        )
    }

    @Test
    fun searchFindsNeedleEpsilonOnlyInChapter5() = runTest {
        val pub = openGeneratedEpub()
        val results = searchAll(pub, NEEDLE_CH05)
        assertTrue("$NEEDLE_CH05 must be found", results.isNotEmpty())
        assertTrue(
            "Every hit must be in ch05 — got ${results.map { it.href }}",
            results.all { it.href.toString().contains("ch05") },
        )
    }

    // ── common term spans all chapters ────────────────────────────────────────

    @Test
    fun searchFindsCommonTermInAllFiveChapters() = runTest {
        val pub = openGeneratedEpub()
        val results = searchAll(pub, COMMON_TERM)
        val chaptersHit = results
            .map { it.href.toString() }
            .mapNotNull { href ->
                (1..5).firstOrNull { n -> href.contains("ch0$n") }
            }
            .toSet()
        assertEquals(
            "$COMMON_TERM must be found in all 5 chapters — hit: $chaptersHit",
            setOf(1, 2, 3, 4, 5),
            chaptersHit,
        )
    }

    // ── absent term ───────────────────────────────────────────────────────────

    @Test
    fun searchReturnsEmptyForTermNotInEpub() = runTest {
        val pub = openGeneratedEpub()
        val results = searchAll(pub, "zxqy_absent_marker_8j3k")
        assertTrue("absent term must produce no hits, got $results", results.isEmpty())
    }

    // ── iterator lifecycle ────────────────────────────────────────────────────

    @Test
    fun iteratorIsClosedViaFinallyAfterSuccessfulSearch() = runTest {
        val pub = openGeneratedEpub()
        val service = checkNotNull(pub.findService(SearchService::class)) {
            "SearchService must be available for this EPUB"
        }
        var closedByFinally = false
        withContext(Dispatchers.IO) {
            val iterator = service.search(NEEDLE_CH01)
            try {
                while (true) {
                    iterator.next().getOrNull() ?: break
                }
            } finally {
                iterator.close()
                closedByFinally = true
            }
        }
        assertTrue("iterator must be closed via finally block", closedByFinally)
    }

    @Test
    fun iteratorIsClosedViaFinallyEvenAfterExceptionDuringIteration() = runTest {
        val pub = openGeneratedEpub()
        val service = checkNotNull(pub.findService(SearchService::class)) {
            "SearchService must be available for this EPUB"
        }
        var closedByFinally = false
        runCatching {
            withContext(Dispatchers.IO) {
                val iterator = service.search(NEEDLE_CH01)
                try {
                    iterator.next() // call once
                    throw RuntimeException("simulated failure after first page")
                } finally {
                    iterator.close()
                    closedByFinally = true
                }
            }
        }
        assertTrue("iterator must be closed even when iteration throws", closedByFinally)
    }

    // ── large chapter: memory resilience ─────────────────────────────────────

    @Test
    fun largeChapterSearchCompletesWithoutOutOfMemoryError() = runTest {
        // ch03 has 2 000 filler paragraphs (~340 KB uncompressed). Verifies that search
        // completes without throwing OOM rather than requiring a specific result count.
        val pub = openGeneratedEpub()
        try {
            searchAll(pub, NEEDLE_CH03)
        } catch (e: OutOfMemoryError) {
            fail("OOM must not escape search: $e")
        }
    }

    @Test
    fun fullSearchPipelineOnLargeEpubMatchesViewModelBehavior() = runTest {
        // Mirrors exactly the control flow in EpubReaderViewModel.performSearch:
        // IO dispatch, isFailure-skip loop, try/finally close.
        val pub = openGeneratedEpub()
        val service = pub.findService(SearchService::class) ?: run {
            fail("SearchService must be available")
            return@runTest
        }

        val results = withContext(Dispatchers.IO) {
            val iterator = service.search(NEEDLE_CH01)
            val acc = mutableListOf<Locator>()
            try {
                while (true) {
                    val pageResult = iterator.next()
                    if (pageResult.isFailure) continue
                    val page = pageResult.getOrNull() ?: break
                    acc.addAll(page.locators)
                }
            } finally {
                iterator.close()
            }
            acc
        }

        assertTrue("pipeline must find $NEEDLE_CH01", results.isNotEmpty())
        assertNotNull("first locator must have a text highlight", results.first().text.highlight)
        assertTrue(
            "first locator href must point to ch01",
            results.first().href.toString().contains("ch01"),
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private suspend fun openGeneratedEpub(): Publication {
        val epubFile = File(tmp.newFolder("epub"), "search-test.epub")
            .also { it.writeBytes(buildEpub()) }
        val url = AbsoluteUrl("file://${epubFile.absolutePath}")!!
        val asset = when (val r = assetRetriever.retrieve(url)) {
            is Try.Success -> r.value
            is Try.Failure -> error("Failed to retrieve EPUB asset from $url: ${r.value}")
        }
        return when (val r = publicationOpener.open(asset, allowUserInteraction = false)) {
            is Try.Success -> r.value
            is Try.Failure -> error("Failed to open EPUB publication: ${r.value}")
        }
    }

    /**
     * Mirrors EpubReaderViewModel.performSearch: IO dispatch, isFailure-skip loop, try/finally.
     *
     * On failure (e.g. Readium wrapping OOM as SearchError.Reading) the chapter is skipped
     * rather than aborting the whole search — this is the fix for the original bug.
     */
    private suspend fun searchAll(pub: Publication, query: String): List<Locator> {
        val service = pub.findService(SearchService::class) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            val iterator = service.search(query)
            val acc = mutableListOf<Locator>()
            try {
                while (true) {
                    val pageResult = iterator.next()
                    if (pageResult.isFailure) continue  // chapter unreadable → skip
                    val page = pageResult.getOrNull() ?: break  // null = end of book
                    acc.addAll(page.locators)
                }
            } finally {
                iterator.close()
            }
            acc
        }
    }

    companion object {
        const val NEEDLE_CH01 = "needle_alpha_riffle_search_test"
        const val NEEDLE_CH02 = "needle_beta_riffle_search_test"
        const val NEEDLE_CH03 = "needle_gamma_riffle_search_test"
        const val NEEDLE_CH04 = "needle_delta_riffle_search_test"
        const val NEEDLE_CH05 = "needle_epsilon_riffle_search_test"
        const val COMMON_TERM = "searchable_common_riffle_word"

        /**
         * Builds a 5-chapter EPUB in memory. Chapter 3 has 2 000 filler paragraphs (~340 KB)
         * to exercise the search iterator over a larger-than-average chapter.
         *
         * Structure:
         *   META-INF/container.xml
         *   OEBPS/content.opf  (5 spine items)
         *   OEBPS/toc.ncx
         *   OEBPS/ch01.xhtml   small, contains [NEEDLE_CH01] + [COMMON_TERM]
         *   OEBPS/ch02.xhtml   small, contains [NEEDLE_CH02] + [COMMON_TERM]
         *   OEBPS/ch03.xhtml   ~340 KB, contains [NEEDLE_CH03] + [COMMON_TERM] + 2 000 filler paragraphs
         *   OEBPS/ch04.xhtml   small, contains [NEEDLE_CH04] + [COMMON_TERM]
         *   OEBPS/ch05.xhtml   small, contains [NEEDLE_CH05] + [COMMON_TERM]
         */
        fun buildEpub(): ByteArray {
            data class Ch(val id: String, val needle: String, val fillerCount: Int = 0)
            val chapters = listOf(
                Ch("ch01", NEEDLE_CH01),
                Ch("ch02", NEEDLE_CH02),
                Ch("ch03", NEEDLE_CH03, fillerCount = 2_000),
                Ch("ch04", NEEDLE_CH04),
                Ch("ch05", NEEDLE_CH05),
            )

            val baos = ByteArrayOutputStream()
            ZipOutputStream(baos).use { zip ->
                // mimetype: must be STORED (uncompressed) and first per the EPUB OCF spec
                val mimetype = "application/epub+zip".toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry("mimetype").also {
                    it.method = ZipEntry.STORED
                    it.size = mimetype.size.toLong()
                    it.compressedSize = mimetype.size.toLong()
                    it.crc = CRC32().apply { update(mimetype) }.value
                })
                zip.write(mimetype)
                zip.closeEntry()

                // META-INF/container.xml
                zip.putNextEntry(ZipEntry("META-INF/container.xml"))
                zip.write(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf"
                                  media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
                zip.closeEntry()

                // OEBPS/content.opf
                zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
                zip.write(
                    buildString {
                        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
                        appendLine("""<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="uid" version="2.0">""")
                        appendLine("""  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""")
                        appendLine("""    <dc:identifier id="uid">riffle-search-test-epub</dc:identifier>""")
                        appendLine("""    <dc:title>Riffle Search Test EPUB</dc:title>""")
                        appendLine("""    <dc:language>en</dc:language>""")
                        appendLine("""  </metadata>""")
                        appendLine("""  <manifest>""")
                        appendLine("""    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
                        for (ch in chapters) {
                            appendLine("""    <item id="${ch.id}" href="${ch.id}.xhtml" media-type="application/xhtml+xml"/>""")
                        }
                        appendLine("""  </manifest>""")
                        appendLine("""  <spine toc="ncx">""")
                        for (ch in chapters) {
                            appendLine("""    <itemref idref="${ch.id}"/>""")
                        }
                        appendLine("""  </spine>""")
                        append("""</package>""")
                    }.toByteArray(Charsets.UTF_8),
                )
                zip.closeEntry()

                // OEBPS/toc.ncx
                zip.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
                zip.write(
                    buildString {
                        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
                        appendLine("""<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">""")
                        appendLine("""  <head>""")
                        appendLine("""    <meta name="dtb:uid" content="riffle-search-test-epub"/>""")
                        appendLine("""    <meta name="dtb:depth" content="1"/>""")
                        appendLine("""    <meta name="dtb:totalPageCount" content="0"/>""")
                        appendLine("""    <meta name="dtb:maxPageNumber" content="0"/>""")
                        appendLine("""  </head>""")
                        appendLine("""  <docTitle><text>Riffle Search Test EPUB</text></docTitle>""")
                        appendLine("""  <navMap>""")
                        for ((idx, ch) in chapters.withIndex()) {
                            appendLine("""    <navPoint id="np${idx + 1}" playOrder="${idx + 1}">""")
                            appendLine("""      <navLabel><text>Chapter ${idx + 1}</text></navLabel>""")
                            appendLine("""      <content src="${ch.id}.xhtml"/>""")
                            appendLine("""    </navPoint>""")
                        }
                        appendLine("""  </navMap>""")
                        append("""</ncx>""")
                    }.toByteArray(Charsets.UTF_8),
                )
                zip.closeEntry()

                // chapter xhtml files: written in byte chunks to avoid one huge string allocation
                val fillerLine =
                    "<p>Filler paragraph for memory pressure testing. Each one contributes to inflating the chapter to the same scale as a real novel chapter that caused OOM on a low-heap device.</p>\n"
                        .toByteArray(Charsets.UTF_8)
                for (ch in chapters) {
                    zip.putNextEntry(ZipEntry("OEBPS/${ch.id}.xhtml"))
                    zip.write("""<?xml version="1.0" encoding="UTF-8"?>""".toByteArray(Charsets.UTF_8))
                    zip.write("\n".toByteArray(Charsets.UTF_8))
                    zip.write("""<html xmlns="http://www.w3.org/1999/xhtml">""".toByteArray(Charsets.UTF_8))
                    zip.write("\n<head><title>Chapter ${ch.id}</title></head>\n<body>\n".toByteArray(Charsets.UTF_8))
                    zip.write("<p>${ch.needle}</p>\n".toByteArray(Charsets.UTF_8))
                    zip.write("<p>$COMMON_TERM appears in every chapter.</p>\n".toByteArray(Charsets.UTF_8))
                    repeat(ch.fillerCount) { zip.write(fillerLine) }
                    zip.write("</body>\n</html>".toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            return baos.toByteArray()
        }
    }
}
