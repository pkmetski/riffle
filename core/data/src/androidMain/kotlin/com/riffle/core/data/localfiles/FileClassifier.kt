package com.riffle.core.data.localfiles

/**
 * Classifies a local file by extension + magic bytes. Extension alone would misclassify anything
 * renamed on-disk; magic bytes alone would classify anything that happens to be a zip. Both must
 * agree.
 */
object FileClassifier {

    enum class Kind { EPUB, PDF, CBZ, UNKNOWN }

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK\x03\x04"
    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)

    fun classify(name: String, head: ByteArray): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "epub" -> if (isZip(head)) Kind.EPUB else Kind.UNKNOWN
            "pdf" -> if (isPdf(head)) Kind.PDF else Kind.UNKNOWN
            "cbz" -> if (isZip(head)) Kind.CBZ else Kind.UNKNOWN
            else -> Kind.UNKNOWN
        }
    }

    // Extension `.epub`/`.cbz` + zip magic classifies as EPUB/CBZ. We deliberately don't verify the
    // uncompressed `mimetype` local-file-header — many archivers deviate from the strict offset-30
    // layout. The downstream metadata extractor is the source of truth; malformed archives fall back
    // to filename as title — safe degradation, no data loss.
    private fun isZip(head: ByteArray): Boolean {
        if (head.size < 4) return false
        for (i in 0..3) if (head[i] != ZIP_MAGIC[i]) return false
        return true
    }

    private fun isPdf(head: ByteArray): Boolean {
        if (head.size < PDF_MAGIC.size) return false
        for (i in PDF_MAGIC.indices) if (head[i] != PDF_MAGIC[i]) return false
        return true
    }
}
