package com.riffle.app.feature.reader.readaloud

import com.riffle.app.feature.reader.session.ReadaloudQuoteBuilder
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.logging.RecordingLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [SidecarSentenceSource] is a pure wrap of [ReadaloudQuoteBuilder] behind [com.riffle.core.domain.sentence.SentenceSource]
 * (ADR 0039 task 4) — no logic moves here. These tests drive a real builder against a minimal EPUB
 * fixture (mirroring EpubContentExtractorTest) to pin: (1) loadAll()/chapterHrefs() trigger the
 * builder's build via ensureBuilt() and return its resulting maps, and (2) a source bound to a
 * builder that was never given a bundle returns empty maps rather than hanging or throwing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SidecarSentenceSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val unconfinedDispatchers = object : DispatcherProvider {
        private val d = Dispatchers.Unconfined
        override val main = d
        override val mainImmediate = d
        override val io = d
        override val default = d
    }

    /** Minimal but structurally-valid EPUB 3 fixture (single chapter, one sentence). */
    private fun buildEpubFile(): java.io.File {
        val container = """
            <?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <manifest>
                <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="c1"/>
              </spine>
            </package>
        """.trimIndent()
        val chapter1 =
            "<html><body><p><span id=\"c1-s0\">Hello there.</span> <span id=\"c1-s1\">Hi again.</span></p></body></html>"

        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
            }
            put("META-INF/container.xml", container)
            put("OEBPS/content.opf", opf)
            put("OEBPS/chapter1.xhtml", chapter1)
        }
        return tmp.newFile("fixture.epub").apply { writeBytes(bos.toByteArray()) }
    }

    @Test
    fun `loadAll and chapterHrefs trigger the builder and return its maps`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val builder = ReadaloudQuoteBuilder(scope, unconfinedDispatchers, RecordingLogger())
        builder.quoteBundle = buildEpubFile()
        val source = SidecarSentenceSource(builder)

        val quotes = source.loadAll()
        val chapters = source.chapterHrefs()

        assertTrue("expected sentences extracted from the fixture chapter", quotes.isNotEmpty())
        assertEquals(builder.sentenceQuotes.value, quotes)
        assertEquals(builder.sentenceChapters.value, chapters)
        assertTrue(chapters.values.all { it == "chapter1.xhtml" })
    }

    @Test
    fun `never-bound builder yields empty maps instead of hanging`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val builder = ReadaloudQuoteBuilder(scope, unconfinedDispatchers, RecordingLogger())
        val source = SidecarSentenceSource(builder)

        assertEquals(emptyMap<String, Any>(), source.loadAll())
        assertEquals(emptyMap<String, String>(), source.chapterHrefs())
    }
}
