package com.riffle.app.feature.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class CachedChapterEntry(val xhtmlBytes: ByteArray, val heightPx: Int) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CachedChapterEntry) return false
        return heightPx == other.heightPx && xhtmlBytes.contentEquals(other.xhtmlBytes)
    }

    override fun hashCode(): Int = 31 * heightPx + xhtmlBytes.contentHashCode()
}

class ReaderChapterCache(private val cacheDir: File) {

    private val dir get() = File(cacheDir, "reader_chapter")
    private val metaFile get() = File(dir, "meta.json")
    private val metaTmpFile get() = File(dir, "meta.tmp")
    private val bodyFile get() = File(dir, "body.bin")

    suspend fun save(
        itemId: String,
        chapterHref: String,
        prefsFingerprint: String,
        xhtmlBytes: ByteArray,
        heightPx: Int,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            bodyFile.writeBytes(xhtmlBytes)
            val meta = JSONObject().apply {
                put("itemId", itemId)
                put("chapterHref", chapterHref)
                put("prefsFingerprint", prefsFingerprint)
                put("heightPx", heightPx)
                put("xhtmlSize", xhtmlBytes.size)
            }
            metaTmpFile.writeText(meta.toString())
            metaTmpFile.renameTo(metaFile)
        }
        Unit
    }

    fun load(itemId: String, chapterHref: String, prefsFingerprint: String): CachedChapterEntry? {
        return runCatching {
            if (!metaFile.exists()) return null
            val meta = JSONObject(metaFile.readText())
            if (meta.getString("itemId") != itemId) return null
            if (meta.getString("chapterHref") != chapterHref) return null
            if (meta.getString("prefsFingerprint") != prefsFingerprint) return null
            val xhtmlSize = meta.getInt("xhtmlSize")
            val heightPx = meta.getInt("heightPx")
            val bytes = bodyFile.readBytes()
            if (bytes.size != xhtmlSize) return null
            CachedChapterEntry(bytes, heightPx)
        }.getOrNull()
    }
}
