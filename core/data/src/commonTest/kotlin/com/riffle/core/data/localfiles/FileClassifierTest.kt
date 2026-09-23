package com.riffle.core.data.localfiles

import kotlin.test.Test
import kotlin.test.assertEquals

class FileClassifierTest {

    private val epubMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(200)
    private val pdfMagic = "%PDF-1.7".encodeToByteArray()
    private val zipMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(200)

    @Test
    fun `classifies epub extension with zip magic as EPUB`() {
        assertEquals(FileClassifier.Kind.EPUB, FileClassifier.classify("book.epub", epubMagic))
    }

    @Test
    fun `classifies EPUB extension case-insensitively`() {
        assertEquals(FileClassifier.Kind.EPUB, FileClassifier.classify("Book.EPUB", epubMagic))
    }

    @Test
    fun `classifies pdf extension with pdf magic as PDF`() {
        assertEquals(FileClassifier.Kind.PDF, FileClassifier.classify("book.pdf", pdfMagic))
    }

    @Test
    fun `unknown extension returns UNKNOWN even if bytes look valid`() {
        assertEquals(FileClassifier.Kind.UNKNOWN, FileClassifier.classify("book.txt", epubMagic))
        assertEquals(FileClassifier.Kind.UNKNOWN, FileClassifier.classify("book", pdfMagic))
    }

    @Test
    fun `epub extension with wrong magic returns UNKNOWN`() {
        assertEquals(FileClassifier.Kind.UNKNOWN, FileClassifier.classify("book.epub", "not a zip".encodeToByteArray()))
    }

    @Test
    fun `pdf extension with wrong magic returns UNKNOWN`() {
        assertEquals(FileClassifier.Kind.UNKNOWN, FileClassifier.classify("book.pdf", "not a pdf".encodeToByteArray()))
    }

    @Test
    fun `classifies cbz extension with zip magic as CBZ`() {
        assertEquals(FileClassifier.Kind.CBZ, FileClassifier.classify("comic.cbz", zipMagic))
    }

    @Test
    fun `classifies CBZ extension case-insensitively`() {
        assertEquals(FileClassifier.Kind.CBZ, FileClassifier.classify("Comic.CBZ", zipMagic))
    }

    @Test
    fun `cbz extension with non-zip magic returns UNKNOWN`() {
        assertEquals(
            FileClassifier.Kind.UNKNOWN,
            FileClassifier.classify("comic.cbz", "not a zip".encodeToByteArray()),
        )
    }

    @Test
    fun `empty head returns UNKNOWN`() {
        assertEquals(FileClassifier.Kind.UNKNOWN, FileClassifier.classify("book.epub", ByteArray(0)))
        assertEquals(FileClassifier.Kind.UNKNOWN, FileClassifier.classify("book.pdf", ByteArray(0)))
    }
}
