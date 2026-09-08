package com.riffle.core.catalog.oreilly.epub

import com.riffle.core.catalog.oreilly.OReillyCatalog
import com.riffle.core.catalog.oreilly.OReillyApi
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.junit.Test

/**
 * Verifies the hand-rolled [EpubZipWriter] / [EpubAssembler] produce a spec-valid archive that the
 * JVM's own ZIP reader accepts — this is the regression pin for the synthesis path that does NOT
 * require any network access. If CRC-32, header offsets, or the STORED-mimetype rule regress, the
 * JVM ZipInputStream throws or the assertions below flip.
 */
class EpubAssemblerTest {

    private fun sampleBook() = SynthesizedBook(
        identifier = "urn:oreilly:9781098100000",
        title = "Deep & <Wide> \"Kotlin\"",
        authors = listOf("Ada Lovelace", "Alan Turing"),
        language = "en",
        publisher = "O'Reilly Media",
        chapters = listOf(
            EpubChapter("ch1", "chapter1.xhtml", "Intro", "<html><body><p>One</p></body></html>"),
            EpubChapter("ch2", "chapter2.xhtml", "Deep & Wide", "<html><body><p>Two</p></body></html>"),
        ),
        resources = listOf(
            EpubResource("style.css", "body{margin:0}".encodeToByteArray(), "text/css"),
            EpubResource("images/cover.jpg", byteArrayOf(1, 2, 3, 4, 5), "image/jpeg"),
        ),
        coverPath = "images/cover.jpg",
    )

    private fun readEntries(bytes: ByteArray): List<Pair<ZipEntry, ByteArray>> {
        val result = mutableListOf<Pair<ZipEntry, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                result += entry to zis.readBytes()
                entry = zis.nextEntry
            }
        }
        return result
    }

    @Test
    fun `produces a JVM-readable archive with mimetype first and stored`() {
        val entries = readEntries(EpubAssembler.assemble(sampleBook()))

        val first = entries.first()
        assertEquals("mimetype", first.first.name)
        assertEquals("mimetype must be STORED", ZipEntry.STORED.toLong(), first.first.method.toLong())
        assertEquals("application/epub+zip", first.second.decodeToString())
    }

    @Test
    fun `contains container, opf, nav, chapters and resources with intact bytes`() {
        val entries = readEntries(EpubAssembler.assemble(sampleBook())).associate { it.first.name to it.second }

        assertNotNull(entries["META-INF/container.xml"])
        assertTrue(entries["META-INF/container.xml"]!!.decodeToString().contains("OEBPS/content.opf"))

        val opf = entries["OEBPS/content.opf"]!!.decodeToString()
        // XML-escaped title survives.
        assertTrue(opf.contains("Deep &amp; &lt;Wide&gt; &quot;Kotlin&quot;"))
        assertTrue(opf.contains("Ada Lovelace"))
        assertTrue(opf.contains("""properties="cover-image""""))
        assertTrue(opf.contains("""<itemref idref="ch1"/>"""))

        assertNotNull(entries["OEBPS/nav.xhtml"])
        assertEquals(
            "<html><body><p>One</p></body></html>",
            entries["OEBPS/chapter1.xhtml"]!!.decodeToString(),
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), entries["OEBPS/images/cover.jpg"])
        assertEquals("body{margin:0}", entries["OEBPS/style.css"]!!.decodeToString())
    }

    @Test
    fun `crc32 matches the JVM implementation`() {
        val data = "The quick brown fox jumps over the lazy dog".encodeToByteArray()
        val expected = java.util.zip.CRC32().apply { update(data) }.value.toInt()
        assertEquals(expected, EpubZipWriter.crc32(data))
    }

    @Test
    fun `assembly is deterministic`() {
        assertArrayEquals(EpubAssembler.assemble(sampleBook()), EpubAssembler.assemble(sampleBook()))
    }

    @Test
    fun `EpubZipStreamWriter produces a JVM-readable archive matching the batch writer`() {
        val book = sampleBook()
        val batchBytes = EpubAssembler.assemble(book)
        val batchEntries = readEntries(batchBytes).associate { it.first.name to it.second }

        // Feed the same entries through the streaming writer one at a time.
        val writer = EpubZipWriter.streamWriter()
        val out = java.io.ByteArrayOutputStream()
        val allEntries = listOf(
            EpubZipEntry("mimetype", "application/epub+zip".encodeToByteArray()),
            EpubZipEntry("META-INF/container.xml", EpubAssembler.containerXml().encodeToByteArray()),
            EpubZipEntry("OEBPS/content.opf", EpubAssembler.contentOpf(book).encodeToByteArray()),
            EpubZipEntry("OEBPS/nav.xhtml", EpubAssembler.navXhtml(book).encodeToByteArray()),
            EpubZipEntry("OEBPS/chapter1.xhtml", "<html><body><p>One</p></body></html>".encodeToByteArray()),
            EpubZipEntry("OEBPS/chapter2.xhtml", "<html><body><p>Two</p></body></html>".encodeToByteArray()),
            EpubZipEntry("OEBPS/style.css", "body{margin:0}".encodeToByteArray()),
            EpubZipEntry("OEBPS/images/cover.jpg", byteArrayOf(1, 2, 3, 4, 5)),
        )
        for (entry in allEntries) {
            out.write(writer.writeEntry(entry))
        }
        out.write(writer.close())
        val streamBytes = out.toByteArray()

        val streamEntries = readEntries(streamBytes).associate { it.first.name to it.second }

        // Both must be readable ZIPs with the same entry names and byte content.
        assertEquals(batchEntries.keys, streamEntries.keys)
        for (name in batchEntries.keys) {
            assertArrayEquals("entry $name differs", batchEntries[name], streamEntries[name])
        }
    }

    @Test
    fun `EpubZipStreamWriter first entry (mimetype) must be STORED and JVM-readable`() {
        val writer = EpubZipWriter.streamWriter()
        val out = java.io.ByteArrayOutputStream()
        out.write(writer.writeEntry(EpubZipEntry("mimetype", "application/epub+zip".encodeToByteArray())))
        out.write(writer.close())

        val entries = readEntries(out.toByteArray())
        assertEquals(1, entries.size)
        assertEquals("mimetype", entries[0].first.name)
        assertEquals(ZipEntry.STORED.toLong(), entries[0].first.method.toLong())
        assertEquals("application/epub+zip", entries[0].second.decodeToString())
    }

    @Test
    fun `withFileStream reports positive contentLength before any chapter is fetched`() = runBlocking {
        // The streaming synthesis estimates the ZIP size from declared file sizes; it must be > 0
        // so the consumer has a denominator for progress reporting.
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            val itemId = "9781098100000"
            val urn = "urn:orm:book:$itemId"
            val chapterBody = "<div id=\"sbo-rt-content\"><p>Hello world</p></div>"

            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(req: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    val path = req.path.orEmpty()
                    val json = when {
                        path.contains("/spine/") -> """{"count":1,"next":null,"results":[{"reference_id":"$itemId-/xhtml/ch01.xhtml","title":"Ch 1"}]}"""
                        path.contains("/files/") && !path.contains("?download=false") -> """{"count":1,"next":null,"results":[{"full_path":"xhtml/ch01.xhtml","media_type":"application/xhtml+xml","kind":"chapter","file_size":${chapterBody.length}}]}"""
                        path.contains("?download=false") -> chapterBody
                        else -> """{"identifier":"$itemId","title":"Test","language":"en"}"""
                    }
                    return okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(json)
                }
            }

            val client = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp)
            val api = OReillyApi(client, cookieHeader = "orm-jwt=x", baseUrl = server.url("/").toString().trimEnd('/'))
            val catalog = OReillyCatalog(api = api, bytesClient = client, cookieHeader = "orm-jwt=x")

            var observedContentLength = -1L
            catalog.withFileStream(itemId, com.riffle.core.catalog.BookFormat.Epub, null) { stream ->
                observedContentLength = stream.contentLength
                stream.channel.readRemaining() // consume all bytes
            }

            assertTrue("contentLength must be > 0 for progress reporting", observedContentLength > 0L)
            client.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `withFileStream with multiple concurrent chapters produces a valid readable ZIP`() = runBlocking {
        // Regression for the writeMutex gap: pipe.writeFully was outside the lock, allowing
        // concurrent chapter jobs to interleave their bytes. The ZIP must be parseable and
        // every expected entry must be present at the offsets the central directory records.
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            val itemId = "9781098100001"
            val chapters = listOf("xhtml/ch01.xhtml", "xhtml/ch02.xhtml", "xhtml/ch03.xhtml")
            val bodies = chapters.mapIndexed { i, _ -> "<div id=\"sbo-rt-content\"><p>Chapter ${i + 1} content</p></div>" }
            val spineJson = chapters.mapIndexed { i, p ->
                """{"reference_id":"$itemId-/$p","title":"Chapter ${i + 1}"}"""
            }.joinToString(",")
            val filesJson = chapters.mapIndexed { i, p ->
                """{"full_path":"$p","media_type":"application/xhtml+xml","kind":"chapter","file_size":${bodies[i].length}}"""
            }.joinToString(",")

            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(req: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    val path = req.path.orEmpty()
                    val json = when {
                        path.contains("/spine/") -> """{"count":${chapters.size},"next":null,"results":[$spineJson]}"""
                        path.contains("/files/") && path.contains("?download=false") -> {
                            val idx = chapters.indexOfFirst { path.contains(it) }
                            if (idx >= 0) bodies[idx] else ""
                        }
                        path.contains("/files/") -> """{"count":${chapters.size},"next":null,"results":[$filesJson]}"""
                        else -> """{"identifier":"$itemId","title":"Multi","language":"en"}"""
                    }
                    return okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(json)
                }
            }

            val client = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp)
            val api = OReillyApi(client, cookieHeader = "orm-jwt=x", baseUrl = server.url("/").toString().trimEnd('/'))
            val catalog = OReillyCatalog(
                api = api, bytesClient = client, cookieHeader = "orm-jwt=x",
                minRequestIntervalMs = 0L, maxRequestIntervalMs = 0L,
            )

            val zipBytes = catalog.withFileStream(itemId, com.riffle.core.catalog.BookFormat.Epub, null) { stream ->
                stream.channel.readRemaining().readBytes()
            }

            // Parse with ZipInputStream — if bytes were interleaved the stream throws or misses entries.
            val entryNames = mutableListOf<String>()
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    entryNames += entry.name
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            assertTrue("ZIP must contain mimetype entry", "mimetype" in entryNames)
            assertTrue("ZIP must contain content.opf", entryNames.any { it.endsWith("content.opf") })
            assertTrue("ZIP must contain all 3 chapters",
                chapters.all { path -> entryNames.any { it.endsWith(path) } })

            client.close()
        } finally {
            server.shutdown()
        }
    }
}
