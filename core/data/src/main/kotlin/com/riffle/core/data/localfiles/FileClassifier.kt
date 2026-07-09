package com.riffle.core.data.localfiles

/**
 * Classifies a local file by extension + magic bytes. Extension alone would misclassify anything
 * renamed on-disk; magic bytes alone would classify anything that happens to be a zip. Both must
 * agree.
 */
object FileClassifier {

    enum class Kind { EPUB, PDF, UNKNOWN }

    private val EPUB_ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK\x03\x04"
    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)
    private const val EPUB_MIMETYPE = "application/epub+zip"

    fun classify(name: String, head: ByteArray): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "epub" -> if (isEpub(head)) Kind.EPUB else Kind.UNKNOWN
            "pdf" -> if (isPdf(head)) Kind.PDF else Kind.UNKNOWN
            else -> Kind.UNKNOWN
        }
    }

    private fun isEpub(head: ByteArray): Boolean {
        if (head.size < 4) return false
        for (i in 0..3) if (head[i] != EPUB_ZIP_MAGIC[i]) return false
        // EPUB requires a `mimetype` file starting at zip local-file-header data offset 30 (name
        // length = 8, extra length = 0). We accept the mimetype string appearing at that offset
        // as a strong indicator; some archivers deviate slightly (extra fields, etc.), so if the
        // magic PK header is present but the mimetype check fails we still accept it.
        if (head.size < 30 + EPUB_MIMETYPE.length) return true
        val slice = head.copyOfRange(30, 30 + EPUB_MIMETYPE.length)
        return slice.toString(Charsets.US_ASCII) == EPUB_MIMETYPE || true
    }

    private fun isPdf(head: ByteArray): Boolean {
        if (head.size < PDF_MAGIC.size) return false
        for (i in PDF_MAGIC.indices) if (head[i] != PDF_MAGIC[i]) return false
        return true
    }
}
