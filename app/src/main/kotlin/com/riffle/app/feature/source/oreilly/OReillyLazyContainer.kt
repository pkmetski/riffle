package com.riffle.app.feature.source.oreilly

import com.riffle.core.catalog.LazyPublicationShape
import com.riffle.core.catalog.LazySpineItem
import com.riffle.core.catalog.oreilly.OReillyEpub
import com.riffle.core.catalog.oreilly.epub.EpubAssembler
import com.riffle.core.catalog.oreilly.epub.EpubChapter
import com.riffle.core.catalog.oreilly.epub.EpubResource
import com.riffle.core.catalog.oreilly.epub.SynthesizedBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.resource.Resource
import java.io.File

/**
 * Readium [Container] for O'Reilly lazy publications. Each chapter resource is fetched on the
 * first [Resource.read] call and stored in a `(bookId, fullPath)` disk cache so re-reading a
 * chapter never re-fetches. Asset bytes (images/css) are fetched similarly and cached.
 *
 * Nothing is eagerly downloaded; only the resources Readium actually renders are ever fetched.
 * Call [prefetchNext] after a chapter renders to warm the following chapter in the background
 * so most chapter turns feel instant.
 *
 * Write-once cache: each slot writes to a `.tmp` file then renames, so concurrent readers never
 * see a partial file.
 */
class OReillyLazyContainer(
    val pub: LazyPublicationShape,
    private val cacheDir: File,
    /** Fetch one chapter's HTML (may throw on network error). */
    private val fetchChapter: suspend (itemId: String, fullPath: String, expectedByteSize: Long) -> String,
    /** Fetch one binary asset; null on failure (non-fatal). */
    private val fetchAsset: suspend (itemId: String, fullPath: String) -> ByteArray?,
    private val scope: CoroutineScope,
    private val urlFactory: (String) -> Url? = { Url(it) },
) : Container<Resource> {

    private val urlToSpine: Map<Url, LazySpineItem>
    private val allSpineUrls: Set<Url>

    init {
        val spineMap = mutableMapOf<Url, LazySpineItem>()
        for (item in pub.spine) {
            urlFactory(item.fullPath)?.let { url -> spineMap[url] = item }
        }
        urlToSpine = spineMap
        allSpineUrls = spineMap.keys.toSet()
    }

    override val entries: Set<Url> get() = allSpineUrls

    override fun get(url: Url): Resource? {
        val spineItem = urlToSpine[url]
        if (spineItem != null) {
            return LazyChapterResource(
                sourceUrl = url as? AbsoluteUrl,
                spineItem = spineItem,
                pub = pub,
                cacheDir = cacheDir,
                fetchChapter = fetchChapter,
            )
        }
        // Unknown URL — try as a relative asset path (Readium requests css/images by their href).
        // Sub-resources (images, CSS) arrive as absolute readium_package:// URLs:
        //   https://readium_package/{path}
        // Strip that prefix to recover the relative path used in the O'Reilly files API.
        val relativePath = extractRelativePath(url.toString())
        return LazyAssetResource(
            sourceUrl = url as? AbsoluteUrl,
            fullPath = relativePath,
            bookId = pub.bookId,
            cacheDir = cacheDir,
            fetchAsset = fetchAsset,
        )
    }

    override fun close() = Unit

    /**
     * Background-prefetch the chapter after [currentIndex] so it's cached before the user
     * navigates there. No-op if already cached or index is out of range.
     */
    fun prefetchNext(currentIndex: Int) {
        val nextItem = pub.spine.getOrNull(currentIndex + 1) ?: return
        val cacheFile = cacheFileFor(cacheDir, pub.bookId, nextItem.fullPath)
        if (cacheFile.exists()) return
        scope.launch {
            runCatching {
                val html = fetchChapter(pub.bookId, nextItem.fullPath, nextItem.declaredByteSize)
                val xhtml = buildChapterXhtml(pub, nextItem, html)
                writeCacheFile(cacheFile, xhtml.encodeToByteArray())
            }
        }
    }

    /**
     * Slowly caches every uncached chapter and asset in spine/file order, running in [scope]
     * (cancelled when the user leaves the reader). Fetches are paced at [minDelayMs]–[maxDelayMs]
     * to stay well below anti-abuse thresholds. Already-cached files are skipped instantly;
     * individual failures are swallowed so one bad asset doesn't abort the run.
     *
     * When all chapters **and** assets are confirmed on disk, [onAllCached] is called with the
     * assembled EPUB bytes so the caller can write them to the offline store. If assembly fails
     * (e.g. a chapter is still missing after the loop despite best efforts) the callback is not
     * invoked and offline caching is deferred to the next open.
     */
    fun startBackgroundPrefetch(
        minDelayMs: Long = 1_500L,
        maxDelayMs: Long = 4_000L,
        onAllCached: suspend (epub: ByteArray) -> Unit = {},
    ) {
        scope.launch {
            fun jitter() = if (minDelayMs >= maxDelayMs) minDelayMs
            else minDelayMs + kotlin.random.Random.nextLong(maxDelayMs - minDelayMs + 1)

            // Phase 1: chapters (in spine order).
            for (item in pub.spine) {
                val cacheFile = cacheFileFor(cacheDir, pub.bookId, item.fullPath)
                if (!cacheFile.exists()) {
                    kotlinx.coroutines.delay(jitter())
                    runCatching {
                        val html = fetchChapter(pub.bookId, item.fullPath, item.declaredByteSize)
                        val xhtml = buildChapterXhtml(pub, item, html)
                        writeCacheFile(cacheFile, xhtml.encodeToByteArray())
                    }
                }
            }

            // Phase 2: assets (images, CSS, fonts). Same pacing — each fetch is an independent
            // API call and O'Reilly's rate guard doesn't distinguish chapter vs asset requests.
            for (asset in pub.assetFiles) {
                val cacheFile = cacheFileFor(cacheDir, pub.bookId, asset.fullPath)
                if (!cacheFile.exists()) {
                    kotlinx.coroutines.delay(jitter())
                    runCatching {
                        val bytes = fetchAsset(pub.bookId, asset.fullPath)
                        if (bytes != null) writeCacheFile(cacheFile, bytes)
                    }
                }
            }

            // Phase 3: assemble and hand off — only if every chapter is present on disk.
            val epub = assembleEpubFromCache() ?: return@launch
            onAllCached(epub)
        }
    }

    /**
     * Assembles a complete EPUB from the on-disk lazy cache. Returns null if any spine chapter is
     * missing from cache (which means the prefetch didn't finish). Assets that are absent are
     * silently omitted — the book is still readable without every image.
     */
    internal fun assembleEpubFromCache(): ByteArray? {
        val chapters = pub.spine.mapIndexed { index, item ->
            val file = cacheFileFor(cacheDir, pub.bookId, item.fullPath)
            if (!file.exists()) return null
            EpubChapter(
                id = OReillyEpub.chapterId(index),
                relativePath = item.fullPath,
                title = item.title,
                xhtml = file.readText(),
            )
        }
        val resources = pub.assetFiles.mapNotNull { asset ->
            val file = cacheFileFor(cacheDir, pub.bookId, asset.fullPath)
            if (!file.exists()) null
            else EpubResource(asset.fullPath, file.readBytes(), asset.mediaType)
        }
        return EpubAssembler.assemble(
            SynthesizedBook(
                identifier = pub.identifier,
                title = pub.title,
                authors = emptyList(),
                language = pub.language,
                chapters = chapters,
                resources = resources,
                coverPath = null,
            ),
        )
    }

    companion object {
        /**
         * Strips the `https://readium_package/` origin that Readium prepends to sub-resource
         * URLs (images, CSS) when requesting them from the container. Returns the bare relative
         * path suitable for the O'Reilly files API.
         */
        internal fun extractRelativePath(urlString: String): String =
            urlString.removePrefix("https://readium_package/").removePrefix("/")

        /** Stable cache path for `(bookId, fullPath)` under `cacheDir`. */
        fun cacheFileFor(cacheDir: File, bookId: String, fullPath: String): File {
            val safeBook = bookId.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            val safePath = fullPath.replace('/', '_').replace(Regex("[^A-Za-z0-9_.\\-]"), "_")
            return File(cacheDir, "$safeBook/$safePath")
        }

        /** Atomic write: write to `.tmp` then rename so readers never see partial files. */
        fun writeCacheFile(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parent, "${target.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                // renameTo can fail across mount points (e.g. under storage pressure). Fall back to
                // a direct write so the cache is still populated; the file won't be atomic but is
                // better than leaving it absent and re-fetching from network on every read.
                target.writeBytes(bytes)
                tmp.delete()
            }
        }

        fun buildChapterXhtml(pub: LazyPublicationShape, item: LazySpineItem, rawHtml: String): String {
            val rewritten = rawHtml
                .replace(pub.absoluteFilesPrefix, OReillyEpub.relPrefixFor(item.fullPath))
                .replace(pub.pathFilesPrefix, OReillyEpub.relPrefixFor(item.fullPath))
            val cssHrefs = pub.cssFullPaths.map { OReillyEpub.relativeTo(item.fullPath, it) }
            return OReillyEpub.wrapChapter(item.title, rewritten, cssHrefs)
        }
    }
}

private class LazyChapterResource(
    override val sourceUrl: AbsoluteUrl?,
    private val spineItem: LazySpineItem,
    private val pub: LazyPublicationShape,
    private val cacheDir: File,
    private val fetchChapter: suspend (itemId: String, fullPath: String, expectedByteSize: Long) -> String,
) : Resource {

    override suspend fun properties(): Try<Resource.Properties, ReadError> =
        Try.success(Resource.Properties())

    override suspend fun length(): Try<Long, ReadError> =
        Try.success(spineItem.declaredByteSize)

    override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> {
        return try {
            val bytes = getOrFetchBytes()
            val result = if (range == null) bytes
            else {
                val start = range.first.coerceIn(0, bytes.size.toLong()).toInt()
                val end = (range.last + 1).coerceIn(0, bytes.size.toLong()).toInt()
                if (end <= start) ByteArray(0) else bytes.copyOfRange(start, end)
            }
            Try.success(result)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Try.failure(ReadError.Decoding(e))
        }
    }

    private suspend fun getOrFetchBytes(): ByteArray {
        val cacheFile = OReillyLazyContainer.cacheFileFor(cacheDir, pub.bookId, spineItem.fullPath)
        if (cacheFile.exists()) return cacheFile.readBytes()
        val html = fetchChapter(pub.bookId, spineItem.fullPath, spineItem.declaredByteSize)
        val xhtml = OReillyLazyContainer.buildChapterXhtml(pub, spineItem, html)
        val bytes = xhtml.encodeToByteArray()
        OReillyLazyContainer.writeCacheFile(cacheFile, bytes)
        return bytes
    }

    override fun close() = Unit
}

private class LazyAssetResource(
    override val sourceUrl: AbsoluteUrl?,
    private val fullPath: String,
    private val bookId: String,
    private val cacheDir: File,
    private val fetchAsset: suspend (itemId: String, fullPath: String) -> ByteArray?,
) : Resource {

    override suspend fun properties(): Try<Resource.Properties, ReadError> =
        Try.success(Resource.Properties())

    override suspend fun length(): Try<Long, ReadError> {
        val f = cacheFile()
        if (f.exists()) return Try.success(f.length())
        // ReadableInputStreamAdapter (Readium) calls length() before read(). If we return 0
        // here it will interpret the resource as empty and never call read(). Fetch eagerly so
        // the correct byte count is available, and cache to disk so read() can serve instantly.
        val fetched = fetchAsset(bookId, fullPath)
        return if (fetched != null && fetched.isNotEmpty()) {
            OReillyLazyContainer.writeCacheFile(f, fetched)
            Try.success(fetched.size.toLong())
        } else {
            Try.success(0L)
        }
    }

    override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> {
        return try {
            val cacheFile = cacheFile()
            val bytes = if (cacheFile.exists()) {
                cacheFile.readBytes()
            } else {
                val fetched = fetchAsset(bookId, fullPath)
                val result = fetched ?: ByteArray(0)
                if (result.isNotEmpty()) OReillyLazyContainer.writeCacheFile(cacheFile, result)
                result
            }
            val result = if (range == null) bytes
            else {
                val start = range.first.coerceIn(0, bytes.size.toLong()).toInt()
                val end = (range.last + 1).coerceIn(0, bytes.size.toLong()).toInt()
                if (end <= start) ByteArray(0) else bytes.copyOfRange(start, end)
            }
            Try.success(result)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Try.failure(ReadError.Decoding(e))
        }
    }

    private fun cacheFile() = OReillyLazyContainer.cacheFileFor(cacheDir, bookId, fullPath)

    override fun close() = Unit
}
