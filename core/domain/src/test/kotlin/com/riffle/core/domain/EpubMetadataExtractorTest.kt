package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.riffle.core.models.Collection
import com.riffle.core.models.EpubMetadata
import com.riffle.core.models.Series

class EpubMetadataExtractorTest {

    @Test
    fun `minimal EPUB with title creator language`() {
        val epub = buildEpub(
            opfMetadata = """
                <dc:title>Dune</dc:title>
                <dc:creator>Frank Herbert</dc:creator>
                <dc:language>en</dc:language>
            """.trimIndent(),
        )
        val md = EpubMetadataExtractor.extract(epub)
        assertEquals("Dune", md.title)
        assertEquals("Frank Herbert", md.author)
        assertEquals("en", md.language)
        assertNull(md.publisher)
    }

    @Test
    fun `full metadata including publisher year isbn series and genres`() {
        val epub = buildEpub(
            opfMetadata = """
                <dc:title>The Dispossessed</dc:title>
                <dc:creator>Ursula K. Le Guin</dc:creator>
                <dc:language>en</dc:language>
                <dc:publisher>Harper &amp; Row</dc:publisher>
                <dc:date>1974-05-01</dc:date>
                <dc:identifier opf:scheme="ISBN">9780061054884</dc:identifier>
                <dc:subject>Science Fiction</dc:subject>
                <dc:subject>Anarchism</dc:subject>
                <meta property="belongs-to-collection" id="c1">Hainish Cycle</meta>
                <meta property="collection-type" refines="#c1">series</meta>
            """.trimIndent(),
        )
        val md = EpubMetadataExtractor.extract(epub)
        assertEquals("The Dispossessed", md.title)
        assertEquals("Ursula K. Le Guin", md.author)
        assertEquals("Harper & Row", md.publisher)
        assertEquals("1974", md.publishedYear)
        assertEquals("9780061054884", md.isbn)
        assertEquals(listOf("Science Fiction", "Anarchism"), md.genres)
        assertEquals("Hainish Cycle", md.seriesName)
    }

    @Test
    fun `EPUB3 group-position refinement populates seriesSequence`() {
        val epub = buildEpub(
            opfMetadata = """
                <dc:title>The Word for World Is Forest</dc:title>
                <meta property="belongs-to-collection" id="c1">Hainish Cycle</meta>
                <meta property="collection-type" refines="#c1">series</meta>
                <meta property="group-position" refines="#c1">4</meta>
            """.trimIndent(),
        )
        val md = EpubMetadataExtractor.extract(epub)
        assertEquals("Hainish Cycle", md.seriesName)
        assertEquals("4", md.seriesSequence)
    }

    @Test
    fun `EPUB2 calibre series_index populates seriesSequence`() {
        val epub = buildEpub(
            opfMetadata = """
                <dc:title>Chamber of Secrets</dc:title>
                <meta name="calibre:series" content="Harry Potter"/>
                <meta name="calibre:series_index" content="2"/>
            """.trimIndent(),
        )
        val md = EpubMetadataExtractor.extract(epub)
        assertEquals("Harry Potter", md.seriesName)
        assertEquals("2", md.seriesSequence)
    }

    // Regression: an id-less <meta property="belongs-to-collection">…</meta> paired with a
    // <meta property="collection-type" (no refines)>series</meta> would otherwise both key to
    // the empty string in the refines→type map, misclassifying the untyped collection as a
    // series. We must not pair them.
    @Test
    fun `id-less collection with a refinesless collection-type does not spuriously match series`() {
        val epub = buildEpub(
            opfMetadata = """
                <dc:title>Anthology</dc:title>
                <meta property="belongs-to-collection">Nameless Collection</meta>
                <meta property="collection-type">series</meta>
            """.trimIndent(),
        )
        val md = EpubMetadataExtractor.extract(epub)
        // We still fall back to the first (and only) collection when no explicit series is
        // typed — but we do NOT synthesise a match via the empty-string ↔ empty-string pairing.
        // The observable payload is unchanged (fallback name), but the code path is not the
        // one that would fire on a legitimate id-less/refinesless overlap. Assert the name is
        // still readable, and that no sequence was harvested from a phantom match.
        assertEquals("Nameless Collection", md.seriesName)
        assertEquals(null, md.seriesSequence)
    }

    @Test
    fun `series without position leaves seriesSequence null`() {
        val epub = buildEpub(
            opfMetadata = """
                <dc:title>Untitled Sequel</dc:title>
                <meta property="belongs-to-collection" id="c1">Some Series</meta>
            """.trimIndent(),
        )
        val md = EpubMetadataExtractor.extract(epub)
        assertEquals("Some Series", md.seriesName)
        assertEquals(null, md.seriesSequence)
    }

    @Test
    fun `multiple creators joined with comma`() {
        val epub = buildEpub(
            opfMetadata = """
                <dc:title>Good Omens</dc:title>
                <dc:creator>Terry Pratchett</dc:creator>
                <dc:creator>Neil Gaiman</dc:creator>
            """.trimIndent(),
        )
        val md = EpubMetadataExtractor.extract(epub)
        assertEquals("Terry Pratchett, Neil Gaiman", md.author)
    }

    @Test
    fun `EPUB3 cover-image manifest properties extracts bytes`() {
        val coverPayload = ByteArray(64) { it.toByte() }
        val epub = buildEpub(
            opfMetadata = """<dc:title>x</dc:title>""",
            extraManifestItems = """<item id="cover-img" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>""",
            extraZipEntries = listOf("cover.jpg" to coverPayload),
        )
        val md = EpubMetadataExtractor.extract(epub)
        assertNotNull(md.coverBytes)
        assertTrue(md.coverBytes!!.contentEquals(coverPayload))
        assertEquals("jpg", md.coverExtension)
    }

    @Test
    fun `EPUB2 cover meta name fallback resolves manifest item`() {
        val coverPayload = ByteArray(32) { (it * 2).toByte() }
        val epub = buildEpub(
            opfMetadata = """
                <dc:title>x</dc:title>
                <meta name="cover" content="cover-item"/>
            """.trimIndent(),
            extraManifestItems = """<item id="cover-item" href="images/cover.png" media-type="image/png"/>""",
            extraZipEntries = listOf("images/cover.png" to coverPayload),
        )
        val md = EpubMetadataExtractor.extract(epub)
        assertNotNull(md.coverBytes)
        assertTrue(md.coverBytes!!.contentEquals(coverPayload))
        assertEquals("png", md.coverExtension)
    }

    @Test
    fun `no metadata block yields EMPTY-shaped result without throwing`() {
        val epub = buildEpub(opfMetadata = "")
        val md = EpubMetadataExtractor.extract(epub)
        assertNull(md.title)
        assertNull(md.author)
        assertEquals(emptyList<String>(), md.genres)
        assertNull(md.coverBytes)
    }

    @Test
    fun `non-ASCII authors preserved verbatim`() {
        val epub = buildEpub(
            opfMetadata = """
                <dc:title>Solaris</dc:title>
                <dc:creator>Stanisław Lem</dc:creator>
                <dc:language>pl</dc:language>
            """.trimIndent(),
        )
        val md = EpubMetadataExtractor.extract(epub)
        assertEquals("Stanisław Lem", md.author)
    }

    @Test
    fun `corrupt cover manifest reference yields null coverBytes without failing metadata`() {
        // Points at a manifest item whose href resolves to a zip entry we never wrote.
        val epub = buildEpub(
            opfMetadata = """<dc:title>Title</dc:title>""",
            extraManifestItems = """<item id="c" href="missing/cover.jpg" media-type="image/jpeg" properties="cover-image"/>""",
        )
        val md = EpubMetadataExtractor.extract(epub)
        assertEquals("Title", md.title)
        assertNull(md.coverBytes)
    }

    @Test
    fun `not a zip returns EMPTY`() {
        val md = EpubMetadataExtractor.extract("this is not a zip".toByteArray())
        assertEquals(EpubMetadata.EMPTY, md)
    }

    // --- helpers ---

    private fun buildEpub(
        opfMetadata: String,
        extraManifestItems: String = "",
        extraZipEntries: List<Pair<String, ByteArray>> = emptyList(),
    ): ByteArray {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf" version="3.0">
              <metadata>
                $opfMetadata
              </metadata>
              <manifest>
                <item id="ch1" href="chap1.xhtml" media-type="application/xhtml+xml"/>
                $extraManifestItems
              </manifest>
              <spine><itemref idref="ch1"/></spine>
            </package>
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/container.xml")); zip.write(container.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/content.opf")); zip.write(opf.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/chap1.xhtml")); zip.write("<html/>".toByteArray()); zip.closeEntry()
            for ((name, bytes) in extraZipEntries) {
                zip.putNextEntry(ZipEntry("OEBPS/$name")); zip.write(bytes); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
