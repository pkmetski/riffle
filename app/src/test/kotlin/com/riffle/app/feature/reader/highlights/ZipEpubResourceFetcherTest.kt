package com.riffle.app.feature.reader.highlights

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Sanity coverage for [ZipEpubResourceFetcher]'s href-normalization and entry lookup — the two
 * failure modes that would silently regress the elided-view image rendering. Uses a temp
 * EPUB-shaped zip so the fetcher is exercised the same way the ViewModel wires it in
 * Highlights mode (downloaded copy → open by ZipFile → fetch by href).
 */
class ZipEpubResourceFetcherTest {

    @get:Rule val temp = TemporaryFolder()

    private fun tempEpub(entries: Map<String, ByteArray>): File {
        val f = temp.newFile("book.epub")
        ZipOutputStream(f.outputStream()).use { zos ->
            for ((path, bytes) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `fetch strips readium_package scheme and finds the entry`() {
        // The href stored on live annotations is the resolved absolute URL Chromium sees:
        // `https://readium_package/OEBPS/…`. The fetcher must strip that prefix before probing
        // the zip. Reverting the prefix-strip flips this red — every figure fetch would miss.
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val fetcher = ZipEpubResourceFetcher.open(tempEpub(mapOf("OEBPS/image.jpg" to bytes)))!!
        try {
            assertArrayEquals(bytes, fetcher.fetch("https://readium_package/OEBPS/image.jpg"))
        } finally {
            fetcher.close()
        }
    }

    @Test
    fun `fetch handles readium_package colon-slash variant`() {
        val bytes = byteArrayOf(9, 8, 7)
        val fetcher = ZipEpubResourceFetcher.open(tempEpub(mapOf("OEBPS/a.png" to bytes)))!!
        try {
            assertArrayEquals(bytes, fetcher.fetch("readium_package://OEBPS/a.png"))
        } finally {
            fetcher.close()
        }
    }

    @Test
    fun `fetch strips leading slash on relative paths`() {
        val bytes = byteArrayOf(4, 2)
        val fetcher = ZipEpubResourceFetcher.open(tempEpub(mapOf("OEBPS/b.png" to bytes)))!!
        try {
            assertArrayEquals(bytes, fetcher.fetch("/OEBPS/b.png"))
        } finally {
            fetcher.close()
        }
    }

    @Test
    fun `fetch drops query and fragment before probing zip`() {
        val bytes = byteArrayOf(0xAA.toByte())
        val fetcher = ZipEpubResourceFetcher.open(tempEpub(mapOf("OEBPS/c.png" to bytes)))!!
        try {
            assertArrayEquals(bytes, fetcher.fetch("https://readium_package/OEBPS/c.png?v=42#foo"))
        } finally {
            fetcher.close()
        }
    }

    @Test
    fun `fetch returns null when entry missing`() {
        val fetcher = ZipEpubResourceFetcher.open(tempEpub(mapOf("OEBPS/a.png" to byteArrayOf(1))))!!
        try {
            assertNull(fetcher.fetch("https://readium_package/OEBPS/does-not-exist.png"))
        } finally {
            fetcher.close()
        }
    }

    @Test
    fun `open returns null for a non-zip file`() {
        val bogus = temp.newFile("not-a-zip.epub").also { it.writeText("just plain text") }
        assertNull(ZipEpubResourceFetcher.open(bogus))
    }
}
