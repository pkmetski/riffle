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

    fun classify(name: String, head: ByteArray): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "epub" -> if (isZip(head)) Kind.EPUB else Kind.UNKNOWN
            "pdf" -> if (isPdf(head)) Kind.PDF else Kind.UNKNOWN
            else -> Kind.UNKNOWN
        }
    }

    // Extension `.epub` + zip magic classifies as EPUB. We deliberately don't try to verify the
    // uncompressed `mimetype` local-file-header here — many archivers deviate from the strict
    // offset-30 layout, so a byte-scan would produce false negatives. The downstream
    // EpubMetadataExtractor is the source of truth for actual EPUB validity; a zip that turns
    // out not to be an EPUB gets an EpubMetadata.EMPTY result and the scanner falls back to the
    // filename as title — safe degradation, no data loss.
    private fun isZip(head: ByteArray): Boolean {
        if (head.size < 4) return false
        for (i in 0..3) if (head[i] != EPUB_ZIP_MAGIC[i]) return false
        return true
    }

    private fun isPdf(head: ByteArray): Boolean {
        if (head.size < PDF_MAGIC.size) return false
        for (i in PDF_MAGIC.indices) if (head[i] != PDF_MAGIC[i]) return false
        return true
    }
}
