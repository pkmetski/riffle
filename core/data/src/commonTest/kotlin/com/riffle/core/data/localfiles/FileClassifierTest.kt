package com.riffle.core.data.localfiles

import kotlin.test.Test
import kotlin.test.assertEquals

class FileClassifierTest {

    private val zipMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val pdfMagic = "%PDF-".encodeToByteArray()

    @Test
    fun `epub with zip magic classifies as EPUB`() {
        assertEquals(FileClassifier.Kind.EPUB, FileClassifier.classify("book.epub", zipMagic))
    }

    @Test
    fun `epub without zip magic classifies as UNKNOWN`() {
        assertEquals(FileClassifier.Kind.UNKNOWN, FileClassifier.classify("book.epub", byteArrayOf(0, 0, 0, 0)))
    }

    @Test
    fun `pdf with pdf magic classifies as PDF`() {
        assertEquals(FileClassifier.Kind.PDF, FileClassifier.classify("doc.pdf", pdfMagic))
    }

    @Test
    fun `cbz with zip magic classifies as CBZ`() {
        assertEquals(FileClassifier.Kind.CBZ, FileClassifier.classify("comic.cbz", zipMagic))
    }

    @Test
    fun `unknown extension classifies as UNKNOWN`() {
        assertEquals(FileClassifier.Kind.UNKNOWN, FileClassifier.classify("file.txt", zipMagic))
    }
}
