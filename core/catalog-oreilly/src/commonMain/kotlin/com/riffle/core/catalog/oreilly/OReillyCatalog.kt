package com.riffle.core.catalog.oreilly

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogAudioFingerprint
import com.riffle.core.catalog.CatalogAudioTrack
import com.riffle.core.catalog.CatalogAudiobookChapter
import com.riffle.core.catalog.CatalogAudiobookStream
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileRetryPolicy
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogEbookDetails
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.DownloadsCapability
import com.riffle.core.catalog.EbookDetailsCapability
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.LazyPublicationCapability
import com.riffle.core.catalog.LazyPublicationShape
import com.riffle.core.catalog.LazySpineItem
import com.riffle.core.catalog.ReadCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.ToReadListCapability
import com.riffle.core.models.TocEntry
import kotlin.math.ceil
import com.riffle.core.catalog.oreilly.epub.EpubAssembler
import com.riffle.core.catalog.oreilly.epub.EpubChapter
import com.riffle.core.catalog.oreilly.epub.EpubResource
import com.riffle.core.catalog.oreilly.epub.SynthesizedBook
import com.riffle.core.catalog.withCatalogFileStream
import com.riffle.core.common.Clock
import com.riffle.core.common.platformSystemClock
import com.riffle.core.models.SourceType
import com.riffle.core.catalog.LazyAssetFile
import com.riffle.core.catalog.oreilly.epub.EpubZipEntry
import com.riffle.core.catalog.oreilly.epub.EpubZipWriter
import io.ktor.client.HttpClient
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * O'Reilly (`learning.oreilly.com`) catalog. Browses/searches the Learning API, streams audiobooks
 * directly (chaptered), and — for ebooks — **scrapes and synthesizes an EPUB in memory** on
 * [withFileStream], which is the download/read byte source. Nothing is written to disk here; the
 * repository's Cache/Download tier persists the bytes just as it does for any network Source.
 */
class OReillyCatalog internal constructor(
    private val api: OReillyApi,
    private val bytesClient: HttpClient,
    // Full cookie header (orm-jwt + session cookies) harvested from the WebView login; used for
    // authenticated audio-track streaming as well as the API calls the [api] makes.
    private val cookieHeader: String,
    private val clock: Clock = platformSystemClock,
    /**
     * Max content/asset requests in flight at once. Concurrency hides per-request latency (the
     * dominant cost on high-RTT links) without raising the *rate*. Aligned with OkHttp's default
     * per-host cap (5).
     */
    private val maxConcurrency: Int = 5,
    /**
     * Minimum spacing between request *starts* (a global rate cap ≈ 1000/this per second). Bounds how
     * aggressive synthesis looks to O'Reilly's bulk-abuse guard, independently of [maxConcurrency].
     */
    private val minRequestIntervalMs: Long = 120L,
    /**
     * Maximum spacing between request *starts*. Randomizing in [[minRequestIntervalMs],
     * [maxRequestIntervalMs]] adds jitter so bulk synthesis doesn't sustain a flat machine-clock rate.
     */
    private val maxRequestIntervalMs: Long = 400L,
    /** Backoff retries when a chapter comes back 403 or truncated (a throttled DRM sample). */
    private val maxContentRetries: Int = 4,
    /** Base for exponential backoff between retries (1s, 2s, 4s, …). */
    private val backoffBaseMs: Long = 1000L,
    /** Reports chapter-fetch progress during synthesis (done, total) for the reader's loading UI. */
    private val onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    /** Called when synthesis finishes or fails, to clear the progress UI. */
    private val onProgressDone: () -> Unit = {},
) : Catalog,
    AudiobookMediaCapability,
    ReadCapability,
    DownloadsCapability,
    ToReadListCapability,
    EbookDetailsCapability,
    LazyPublicationCapability {

    override val sourceType: SourceType = SourceType.OREILLY

    // Shared pacer for the lazy-reading path (fetchChapterForLazy / fetchAssetForLazy).
    // One pacer instance shared across all calls so concurrent lazy fetches are rate-limited
    // by the same interval guard as the batch synthesis path.
    private val lazyPacer = RequestPacer(
        maxConcurrency = 1,
        minIntervalMs = minRequestIntervalMs,
        maxIntervalMs = maxRequestIntervalMs,
    ) { clock.nowMs() }

    override suspend fun listRoots(): List<CatalogRoot> = listOf(
        CatalogRoot(id = OReillyRoots.BOOKS, name = "Books", mediaType = "book"),
        CatalogRoot(id = OReillyRoots.AUDIOBOOKS, name = "Audiobooks", mediaType = "audiobook"),
    )

    override suspend fun browse(
        rootId: String,
        sort: SortKey,
        page: Int,
        pageSize: Int,
        facet: FacetSelection?,
    ): List<CatalogItem> =
        OReillyParser.parseSearch(api.getJson(api.browseUrl(rootId, page, pageSize)), rootId)

    override suspend fun search(
        rootId: String,
        query: String,
        page: Int,
        pageSize: Int,
    ): List<CatalogItem> {
        if (query.isBlank()) return emptyList()
        return OReillyParser.parseSearch(api.getJson(api.searchUrl(query, rootId, page, pageSize)), rootId)
    }

    override suspend fun getItem(itemId: String): CatalogItem? {
        val body = runCatching { api.getJson(api.bookDetailUrl(itemId)) }.getOrNull() ?: return null
        // NOTE: O'Reilly's book-detail metadata (`/epubs/{urn}/`) carries NO author — authors only
        // appear in the search listing — so this returns a blank author. The web-source gate fills it
        // back in from the browse listing it already holds (WebSourceItemGate.openItem), which is free;
        // recovering it here would cost an extra search request per detail open (see the bot-detection
        // hardening doc). The ABS-upload path is covered by [withFallbackAuthor].
        return OReillyParser.bookDetailToCatalogItem(
            id = itemId,
            detail = OReillyParser.parseBookDetail(body),
            coverUrl = api.coverUrl(itemId),
        )
    }

    /**
     * Cheap TOC + reading-length estimate from metadata only — the spine (chapter order + titles)
     * and the file list (per-file `file_size`). Computes Readium-equivalent positions
     * (ceil(size / 1024) per chapter) so the estimate matches what opening the synthesized EPUB would
     * yield, WITHOUT downloading any chapter content. Keeps the detail screen instant for O'Reilly.
     */
    override suspend fun ebookDetails(itemId: String): CatalogEbookDetails? {
        val spine = fetchAllSpine(itemId)
            .results.mapNotNull { s -> s.fullPath?.let { p -> p to (s.title ?: "") } }
        if (spine.isEmpty()) return null
        val sizeByPath = fetchAllFiles(itemId).associate { it.fullPath to it.fileSize }
        val totalPositions = spine.sumOf { (path, _) ->
            ceil((sizeByPath[path] ?: 0L).toDouble() / 1024.0).toInt().coerceAtLeast(1)
        }
        val toc = spine.map { (path, title) -> TocEntry(title = title.ifBlank { path }, href = path) }
        return CatalogEbookDetails(
            totalPositions = totalPositions.takeIf { it > 0 },
            tocEntries = toc,
            epubVersion = "3.0",
        )
    }

    // ---- Lazy (on-demand) publication (LazyPublicationCapability) ----------------------------

    /**
     * Builds the publication shape from metadata only — spine order + file sizes from two cheap
     * JSON calls, same source as [ebookDetails]. Returns null when the spine is empty or
     * unreachable. No chapter HTML is downloaded.
     */
    override suspend fun lazyPublication(itemId: String): LazyPublicationShape? {
        val detail = runCatching {
            OReillyParser.parseBookDetail(api.getJson(api.bookDetailUrl(itemId)))
        }.getOrNull() ?: return null

        val spineItems = fetchAllSpine(itemId)
            .results.mapNotNull { s -> s.fullPath?.let { path -> path to (s.title ?: "") } }
        if (spineItems.isEmpty()) return null

        val files = fetchAllFiles(itemId)
        val fileMeta = files.associateBy { it.fullPath }
        val cssFullPaths = files.filter { it.kind == "stylesheet" }.map { it.fullPath }

        val absPrefix = api.baseUrl().trimEnd('/') + api.filesPrefix(itemId)
        val pathPrefix = api.filesPrefix(itemId)

        val distinctSpine = spineItems.distinctBy { it.first }

        val spine = distinctSpine.mapIndexed { index, (fullPath, title) ->
            val meta = fileMeta[fullPath]
            LazySpineItem(
                index = index,
                fullPath = fullPath,
                title = title.ifBlank { "Chapter ${index + 1}" },
                declaredByteSize = meta?.fileSize ?: 0L,
                mediaType = meta?.mediaType ?: "application/xhtml+xml",
            )
        }

        val chapterPaths = distinctSpine.map { it.first }.toSet()
        val assetFiles = filterAssetFiles(files, chapterPaths)
            .map { LazyAssetFile(it.fullPath, it.mediaType) }

        return LazyPublicationShape(
            bookId = itemId,
            identifier = "urn:orm:book:${detail.identifier.ifBlank { itemId }}",
            title = detail.title.ifBlank { itemId },
            language = detail.language ?: "en",
            spine = spine,
            absoluteFilesPrefix = absPrefix,
            pathFilesPrefix = pathPrefix,
            cssFullPaths = cssFullPaths,
            assetFiles = assetFiles,
        )
    }

    /**
     * Fetch one chapter's HTML for the lazy-reading path. Uses the same backoff/truncation logic
     * as [withFileStream] but with a single-request pacer (each lazy chapter fetch is independent).
     * Returns null after exhausting retries so the caller can surface a per-chapter error.
     */
    override suspend fun fetchChapterForLazy(itemId: String, fullPath: String, expectedByteSize: Long): String? =
        runCatching { fetchChapterContent(itemId, fullPath, expectedByteSize, lazyPacer) }.getOrNull()

    /**
     * Fetch one binary asset for the lazy-reading path (non-fatal — returns null on failure).
     */
    override suspend fun fetchAssetForLazy(itemId: String, fullPath: String): ByteArray? =
        fetchAssetBytes(itemId, fullPath, lazyPacer)

    // ---- Ebook: scrape + synthesize (verified against the live v2 epubs API) -----------------

    override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle =
        throw OReillyException("O'Reilly ebooks are synthesized in memory — use withFileStream")

    override suspend fun <T> withFileStream(
        itemId: String,
        format: BookFormat,
        handleHint: String?,
        block: suspend (CatalogFileStream) -> T,
    ): T {
        if (format != BookFormat.Epub) {
            throw OReillyException("Only EPUB synthesis is supported for O'Reilly ebooks; got $format")
        }

        // Phase 1 (fast): fetch metadata from the API — JSON calls only, no content yet.
        val detail = OReillyParser.parseBookDetail(api.getJson(api.bookDetailUrl(itemId)))
        val spine = fetchAllSpine(itemId)
            .results.mapNotNull { it.fullPath?.let { p -> p to (it.title ?: "") } }
        val files = fetchAllFiles(itemId)
        val sizeByPath = files.associate { it.fullPath to it.fileSize }

        val absPrefix = api.baseUrl().trimEnd('/') + api.filesPrefix(itemId)
        val pathPrefix = api.filesPrefix(itemId)
        val cssFullPaths = files.filter { it.kind == "stylesheet" }.map { it.fullPath }
        val chapterPaths = spine.map { it.first }.toSet()
        val assetFiles = filterAssetFiles(files, chapterPaths)
        val distinctSpine = spine.distinctBy { it.first }

        // Estimate total ZIP size from declared file sizes so the consumer can report progress.
        // Each ZIP entry costs: 30 (local header) + nameBytes + data. Central dir: 46 + nameBytes.
        // We over-count slightly (chapter XHTML adds wrapper markup beyond the raw HTML), but
        // that's fine — the consumer's progress fraction stays < 1.0 until the final close bytes.
        val entriesCount = 2 /* preamble */ + distinctSpine.size + assetFiles.size + 2 /* opf+nav */
        val estimatedContentBytes = 2 /* preamble: mimetype+container */ * 30L +
            "mimetype".length + "application/epub+zip".length +
            "META-INF/container.xml".length + 300L /* container.xml content */ +
            distinctSpine.sumOf { (p, _) -> 30L + "OEBPS/$p".length + (sizeByPath[p] ?: 1024L) } +
            assetFiles.sumOf { f -> 30L + "OEBPS/${f.fullPath}".length + (f.fileSize) } +
            2L * (30L + 200L) /* content.opf + nav.xhtml rough estimate */ +
            entriesCount * 46L /* central dir records */ + 22L /* EOCD */

        // Phase 2: stream bytes through a ByteChannel pipe.
        // Producer writes zip entries as each chapter/asset is fetched. Consumer (block) reads
        // immediately — download progress advances throughout synthesis, not just at the end.
        val pipe = ByteChannel(autoFlush = true)
        val stream = object : CatalogFileStream {
            override val contentLength: Long = estimatedContentBytes
            override val channel: ByteReadChannel = pipe
        }

        return coroutineScope {
            val producer = launch {
                try {
                    val writer = EpubZipWriter.streamWriter()
                    val writeMutex = Mutex()

                    // Serialize both the central-directory record AND the pipe write under one lock
                    // so concurrent chapter/asset jobs can't interleave their bytes in the channel.
                    suspend fun writeEntry(entry: EpubZipEntry) {
                        writeMutex.withLock {
                            pipe.writeFully(writer.writeEntry(entry))
                        }
                    }

                    // (a) Preamble entries: no network needed, write immediately so the consumer
                    //     can start storing bytes while chapters are being fetched.
                    writeEntry(EpubZipEntry("mimetype", "application/epub+zip".encodeToByteArray()))
                    writeEntry(EpubZipEntry("META-INF/container.xml", EpubAssembler.containerXml().encodeToByteArray()))

                    // (b) Fetch chapters + assets concurrently (same bounded pool as before).
                    val total = distinctSpine.size + assetFiles.size
                    val pacer = RequestPacer(maxConcurrency, minRequestIntervalMs, maxRequestIntervalMs) { clock.nowMs() }
                    val progressMutex = Mutex()
                    var done = 0
                    onProgress(0, total)
                    suspend fun tick() {
                        val d = progressMutex.withLock { done += 1; done }
                        onProgress(d, total)
                    }

                    val collectedChapters = ArrayList<EpubChapter>(distinctSpine.size)
                    val collectedResources = ArrayList<EpubResource>(assetFiles.size)
                    val chapterMutex = Mutex()

                    val assetJobs = assetFiles.map { f ->
                        async {
                            val bytes = fetchAssetBytes(itemId, f.fullPath, pacer)
                            tick()
                            if (bytes != null) {
                                val res = EpubResource(f.fullPath, bytes, f.mediaType)
                                chapterMutex.withLock { collectedResources += res }
                                writeEntry(EpubZipEntry("OEBPS/${f.fullPath}", bytes))
                            }
                        }
                    }
                    val chapterJobs = distinctSpine.mapIndexed { index, (fullPath, title) ->
                        async {
                            val raw = fetchChapterContent(itemId, fullPath, sizeByPath[fullPath] ?: 0L, pacer)
                            tick()
                            val t = title.ifBlank { "Chapter ${index + 1}" }
                            val relPrefix = OReillyEpub.relPrefixFor(fullPath)
                            val rewritten = raw.replace(absPrefix, relPrefix).replace(pathPrefix, relPrefix)
                            val cssHrefs = cssFullPaths.map { OReillyEpub.relativeTo(fullPath, it) }
                            val ch = EpubChapter(
                                id = OReillyEpub.chapterId(index),
                                relativePath = fullPath,
                                title = t,
                                xhtml = OReillyEpub.wrapChapter(t, rewritten, cssHrefs),
                            )
                            chapterMutex.withLock { collectedChapters += ch }
                            writeEntry(EpubZipEntry("OEBPS/$fullPath", ch.xhtml.encodeToByteArray()))
                        }
                    }

                    assetJobs.awaitAll()
                    chapterJobs.awaitAll()

                    if (collectedChapters.isEmpty()) throw OReillyException("No chapters resolved for O'Reilly $itemId")

                    // Chapters must be in spine order for a valid OPF manifest spine.
                    val sortedChapters = distinctSpine.mapNotNull { (p, _) ->
                        collectedChapters.find { it.relativePath == p }
                    }

                    // (c) Package document + navigation (depend on knowing all chapters/resources).
                    val book = SynthesizedBook(
                        identifier = "urn:orm:book:${detail.identifier.ifBlank { itemId }}",
                        title = detail.title.ifBlank { itemId },
                        authors = emptyList(),
                        language = detail.language ?: "en",
                        publisher = null,
                        chapters = sortedChapters,
                        resources = collectedResources,
                        coverPath = null,
                    )
                    writeEntry(EpubZipEntry("OEBPS/content.opf", EpubAssembler.contentOpf(book).encodeToByteArray()))
                    writeEntry(EpubZipEntry("OEBPS/nav.xhtml", EpubAssembler.navXhtml(book).encodeToByteArray()))

                    // (d) Central directory + EOCD.
                    writeMutex.withLock { pipe.writeFully(writer.close()) }
                    pipe.close()
                } catch (e: CancellationException) {
                    pipe.cancel(e)
                    throw e
                } catch (e: Exception) {
                    pipe.cancel(e)
                } finally {
                    onProgressDone()
                }
            }

            val result = block(stream)
            producer.join()
            result
        }
    }

    /**
     * Reconstruct a self-contained EPUB from the v2 content API: metadata + ordered spine + the full
     * file list, fetching each chapter's XHTML and every packaged asset (images/css/fonts). Absolute
     * asset URLs in chapter bodies (`/api/v2/epubs/{urn}/files/…`) are rewritten to paths relative to
     * each chapter's packaged location; stylesheets are linked into each chapter head.
     *
     * Chapter/asset content is fetched via [OReillyApi.getContent]/[OReillyApi.getBytes], which use
     * `?download=false` + an HTML `Accept` — that combination returns the FULL chapter; fetching a
     * file as a plain "download" (or with an `application/json` Accept) yields only a ~2KB DRM sample.
     *
     * O'Reilly applies anti-abuse throttling to rapid bulk content access. To stay under it:
     *  (1) each fetch is paced by [pacingDelayMs];
     *  (2) a `403` or a *truncated* chapter (throttled sample) triggers exponential backoff + retry;
     *  (3) truncation is detected deterministically by comparing the fetched length to the file's
     *      real `file_size` from the file list — a chapter far smaller than its declared size is a
     *      sample, not real content;
     *  (4) if a chapter still can't be fetched in full after [maxContentRetries], synthesis ABORTS
     *      (throws) so a partial/sample EPUB is never cached — a later retry can succeed cleanly.
     * Missing *assets* (images/css) are non-fatal and skipped; missing *chapters* are fatal.
     */
    internal suspend fun synthesizeEpub(itemId: String): ByteArray {
        val detail = OReillyParser.parseBookDetail(api.getJson(api.bookDetailUrl(itemId)))
        val spine = fetchAllSpine(itemId)
            .results.mapNotNull { it.fullPath?.let { p -> p to (it.title ?: "") } }
        val files = fetchAllFiles(itemId)
        val sizeByPath = files.associate { it.fullPath to it.fileSize }

        val absPrefix = api.baseUrl().trimEnd('/') + api.filesPrefix(itemId) // full URL prefix
        val pathPrefix = api.filesPrefix(itemId)                             // path-only prefix
        val cssFullPaths = files.filter { it.kind == "stylesheet" }.map { it.fullPath }

        // Assets to package: every non-chapter file at its own full_path.
        val chapterPaths = spine.map { it.first }.toSet()
        val assetFiles = filterAssetFiles(files, chapterPaths)

        // Dedupe repeated spine paths (a duplicate zip entry would fail Readium's parser).
        val distinctSpine = spine.distinctBy { it.first }

        // Fetch concurrently: a bounded pool ([maxConcurrency]) hides per-request latency — the
        // dominant cost — while a global rate cap ([minRequestIntervalMs] between request starts) keeps
        // us under O'Reilly's bulk-abuse guard. Progress spans both phases so the reader's
        // "Preparing book… N/M" advances throughout.
        val total = assetFiles.size + distinctSpine.size
        val pacer = RequestPacer(maxConcurrency, minRequestIntervalMs, maxRequestIntervalMs) { clock.nowMs() }
        val progressMutex = Mutex()
        var done = 0
        onProgress(0, total)
        suspend fun tick() {
            val d = progressMutex.withLock { done += 1; done }
            onProgress(d, total)
        }
        try {
            return coroutineScope {
                val assetJobs = assetFiles.map { f ->
                    async {
                        val bytes = fetchAssetBytes(itemId, f.fullPath, pacer) // non-fatal
                        tick()
                        bytes?.let { EpubResource(f.fullPath, it, f.mediaType) }
                    }
                }
                val chapterJobs = distinctSpine.mapIndexed { index, (fullPath, title) ->
                    async {
                        // Fatal on failure (throws) → coroutineScope cancels siblings and aborts, so a
                        // partial/sample book is never assembled or cached.
                        val raw = fetchChapterContent(itemId, fullPath, sizeByPath[fullPath] ?: 0L, pacer)
                        tick()
                        val t = title.ifBlank { "Chapter ${index + 1}" }
                        val relPrefix = OReillyEpub.relPrefixFor(fullPath)
                        val rewritten = raw.replace(absPrefix, relPrefix).replace(pathPrefix, relPrefix)
                        val cssHrefs = cssFullPaths.map { OReillyEpub.relativeTo(fullPath, it) }
                        EpubChapter(
                            id = OReillyEpub.chapterId(index),
                            relativePath = fullPath,
                            title = t,
                            xhtml = OReillyEpub.wrapChapter(t, rewritten, cssHrefs),
                        )
                    }
                }
                val resources = assetJobs.awaitAll().filterNotNull()
                val chapters = chapterJobs.awaitAll() // in spine order
                if (chapters.isEmpty()) throw OReillyException("No chapters resolved for O'Reilly $itemId")
                EpubAssembler.assemble(
                    SynthesizedBook(
                        identifier = "urn:orm:book:${detail.identifier.ifBlank { itemId }}",
                        title = detail.title.ifBlank { itemId },
                        authors = emptyList(), // v2 metadata carries no authors; not needed to read
                        language = detail.language ?: "en",
                        publisher = null,
                        chapters = chapters,
                        resources = resources,
                        coverPath = null,
                    ),
                )
            }
        } finally {
            onProgressDone()
        }
    }

    /** Fetch every packaged file, following the API's pagination `next` cursor. */
    private suspend fun fetchAllSpine(itemId: String): OReillySpineResponse {
        val all = ArrayList<OReillySpineItem>()
        var url: String? = api.spineUrl(itemId)
        while (url != null) {
            val page = OReillyParser.parseSpine(api.getJson(url))
            all += page.results
            url = if (page.next.isNullOrBlank() || page.results.isEmpty()) null else page.next
        }
        return OReillySpineResponse(count = all.size, next = null, results = all)
    }

    private suspend fun fetchAllFiles(itemId: String): List<OReillyFileMeta> {
        val all = ArrayList<OReillyFileMeta>()
        var offset = 0
        val limit = 300
        while (true) {
            val page = OReillyParser.parseFiles(api.getJson(api.filesUrl(itemId, limit, offset)))
            all += page.results
            if (page.next.isNullOrBlank() || page.results.isEmpty()) break
            offset += page.results.size
        }
        return all
    }

    /**
     * Returns the packaged non-chapter assets from [files], excluding synthetic entries that
     * [EpubAssembler] generates itself (content.opf, nav.xhtml) and OPF/NCX originals that would
     * create duplicate ZIP entries if re-packaged.
     */
    internal fun filterAssetFiles(
        files: List<OReillyFileMeta>,
        chapterPaths: Set<String>,
    ): List<OReillyFileMeta> {
        val reservedPaths = setOf("content.opf", "nav.xhtml")
        return files.filter { f ->
            f.kind != "chapter" && f.fullPath.isNotBlank() &&
                f.fullPath !in chapterPaths && f.fullPath !in reservedPaths &&
                f.mediaType != "application/oebps-package+xml" &&
                f.mediaType != "application/x-dtbncx+xml"
        }.distinctBy { it.fullPath }
    }

    /**
     * Fetch one chapter's HTML, pacing the request and retrying with exponential backoff on a `403`
     * or a truncated (throttled-sample) response. Throws [OReillyException] if a full copy can't be
     * obtained within [maxContentRetries] — the caller aborts synthesis so a partial book is never
     * cached.
     */
    private suspend fun fetchChapterContent(
        itemId: String,
        fullPath: String,
        expectedSize: Long,
        pacer: RequestPacer,
    ): String {
        var attempt = 0
        while (true) {
            val result = runCatching { pacer.execute { api.getContent(api.fileContentUrl(itemId, fullPath)) } }
            val body = result.getOrNull()
            val throttled = (result.exceptionOrNull() as? OReillyHttpException)?.code == 403 ||
                (body != null && isTruncatedBody(body, expectedSize))
            if (body != null && !throttled) return body
            if (attempt >= maxContentRetries) {
                throw OReillyException(
                    "O'Reilly chapter '$fullPath' unavailable after ${maxContentRetries + 1} attempts " +
                        "(rate-limited or truncated). Aborting synthesis so no partial book is cached.",
                )
            }
            delay(backoffBaseMs shl attempt) // 1s, 2s, 4s, … (no pool permit held during backoff)
            attempt++
        }
    }

    /**
     * Fetch one packaged asset (image/css/font), paced, with a single backoff retry on `403`.
     * Assets are non-fatal: returns null on failure so synthesis proceeds with a degraded book
     * rather than aborting over one missing image.
     */
    private suspend fun fetchAssetBytes(itemId: String, fullPath: String, pacer: RequestPacer): ByteArray? {
        repeat(2) { attempt ->
            val result = runCatching { pacer.execute { api.getBytes(api.fileContentUrl(itemId, fullPath)) } }
            result.getOrNull()?.let { return it }
            val is403 = (result.exceptionOrNull() as? OReillyHttpException)?.code == 403
            if (!is403 || attempt == 1) return null
            delay(backoffBaseMs)
        }
        return null
    }

    /**
     * Bounds concurrent requests to [maxConcurrency] and spaces request *starts* by a jittered
     * interval in [[minIntervalMs], [maxIntervalMs]] (a global rate cap). Concurrency hides
     * per-request latency; the rate cap bounds abuse-guard exposure — the two are independent.
     * A retry's backoff happens outside [execute], so it never holds a permit while merely waiting.
     */
    internal class RequestPacer(
        maxConcurrency: Int,
        private val minIntervalMs: Long,
        private val maxIntervalMs: Long = minIntervalMs,
        private val nowMs: () -> Long,
    ) {
        private val permits = Semaphore(maxConcurrency)
        private val gate = Mutex()
        private var nextAllowedMs = 0L

        suspend fun <T> execute(block: suspend () -> T): T = permits.withPermit {
            val waitMs = gate.withLock {
                val now = nowMs()
                val start = maxOf(now, nextAllowedMs)
                nextAllowedMs = start + randomInterval(minIntervalMs, maxIntervalMs)
                start - now
            }
            if (waitMs > 0) delay(waitMs)
            block()
        }

        companion object {
            internal fun randomInterval(minMs: Long, maxMs: Long): Long {
                if (minMs >= maxMs) return minMs
                return minMs + kotlin.random.Random.nextLong(maxMs - minMs + 1)
            }
        }
    }


    // ---- Audiobook (AudiobookMediaCapability) -------------------------------
    // O'Reilly audiobooks are Kaltura-hosted "videos" (verified live 2026-09). One playback session
    // is assembled from: videotocs (ordered chapter list + durations) → videoclips (per-chapter
    // kaltura_entry_id) → kaltura_config (partner_id) + kaltura_session (KS) → a Kaltura HLS URL per
    // chapter. The KS expires ~1h after minting, so URLs are baked at openAudiobook time; a listening
    // session longer than an hour may need reopening. Media3 plays the AAC-in-HLS flavor natively.

    override suspend fun getTracks(itemId: String): List<CatalogAudioTrack> =
        loadAudiobookTracks(itemId)?.tracks ?: emptyList()

    /**
     * Resolves the full track list for [itemId]: chapter order + durations from `videotocs`, each
     * chapter's Kaltura entry id from `videoclips` (fetched concurrently, bounded by [maxConcurrency]),
     * and a Kaltura HLS URL per chapter carrying a freshly-minted KS. Returns `null` when the item has
     * no audiobook (empty toc) or the Kaltura config/session can't be obtained.
     */
    private suspend fun loadAudiobookTracks(itemId: String): AudiobookAssembly? {
        val toc = OReillyParser.parseVideoToc(api.getJson(api.videoTocUrl(itemId))).toc
            .filter { it.referenceId.isNotBlank() }
        if (toc.isEmpty()) return null

        val (partnerId, ks) = coroutineScope {
            val partnerIdD = async { OReillyParser.parseKalturaConfig(api.getJson(api.kalturaConfigUrl())).partnerId }
            val ksD = async { OReillyParser.parseKalturaSession(api.getJson(api.kalturaSessionUrl())).session }
            partnerIdD.await() to ksD.await()
        }
        if (partnerId.isBlank() || ks.isBlank()) return null

        val gate = Semaphore(maxConcurrency)
        val entryIdByRef = coroutineScope {
            toc.map { entry ->
                async {
                    entry.referenceId to gate.withPermit {
                        runCatching {
                            OReillyParser.parseVideoClip(api.getJson(api.videoClipUrl(entry.referenceId)))
                                .kalturaEntryId
                        }.getOrDefault("")
                    }
                }
            }.awaitAll()
        }.toMap()

        // All-or-nothing: if any chapter's Kaltura entry can't be resolved, fail the whole session
        // rather than play a book with a silent hole and a timeline that no longer matches the toc
        // (getAudiobookChapters is toc-derived). A partial audiobook is worse than a clear failure.
        var offset = 0.0
        val tracks = mutableListOf<CatalogAudioTrack>()
        val downloadUrls = mutableListOf<String>()
        val chapters = mutableListOf<CatalogAudiobookChapter>()
        toc.forEachIndexed { index, entry ->
            val entryId = entryIdByRef[entry.referenceId].orEmpty()
            if (entryId.isBlank()) return null
            val duration = entry.duration.toDouble()
            tracks += CatalogAudioTrack(
                ino = entry.referenceId,
                index = index,
                startOffsetSec = offset,
                durationSec = duration,
                contentUrl = OReillyApi.kalturaHlsUrl(partnerId, entryId, ks),
                mimeType = OReillyApi.HLS_MIME,
            )
            // HLS manifests cannot be byte-downloaded; the format/url redirect returns a signed MP4
            // that AudiobookTrackDownloader can stream to disk (verified live 2026-09).
            downloadUrls += OReillyApi.kalturaDownloadUrl(partnerId, entryId, ks)
            chapters += CatalogAudiobookChapter(
                index = index,
                startSec = offset,
                endSec = offset + duration,
                title = entry.title.ifBlank { "Chapter ${index + 1}" },
            )
            offset += duration
        }
        return if (tracks.isEmpty()) null
        else AudiobookAssembly(tracks, downloadUrls, chapters, totalDurationSec = offset)
    }

    private class AudiobookAssembly(
        val tracks: List<CatalogAudioTrack>,
        val downloadTrackUrls: List<String>,
        val chapters: List<CatalogAudiobookChapter>,
        val totalDurationSec: Double,
    )

    override suspend fun getFingerprint(itemId: String): CatalogAudioFingerprint? {
        val toc = OReillyParser.parseVideoToc(api.getJson(api.videoTocUrl(itemId))).toc
            .filter { it.referenceId.isNotBlank() }
        if (toc.isEmpty()) return null
        val durations = toc.map { it.duration.toDouble() }
        return CatalogAudioFingerprint(
            itemId = itemId,
            fileSizeBytes = 0L, // streaming-only; identity relies on durations
            totalDurationSec = durations.sum(),
            trackDurations = durations,
        )
    }

    override fun buildStreamUrl(itemId: String, trackIno: String): String = trackIno

    override suspend fun <T> withTrackStream(
        itemId: String,
        trackIno: String,
        block: suspend (CatalogFileStream) -> T,
    ): T = bytesClient.withCatalogFileStream(
        handle = CatalogFileHandle.Stream(
            url = trackIno,
            headers = mapOf("Cookie" to cookieHeader),
            format = BookFormat.Audiobook,
        ),
        retryPolicy = CatalogFileRetryPolicy(statusCodes = setOf(429, 503), delaysMs = listOf(500L, 1000L, 2000L)),
        httpFailure = { failure -> OReillyException("Failed to stream O'Reilly track: ${failure.code}") },
        block = block,
    )

    override suspend fun openAudiobook(itemId: String, deviceLabel: String): CatalogAudiobookStream? {
        val assembly = loadAudiobookTracks(itemId) ?: return null
        return CatalogAudiobookStream(
            trackUrls = assembly.tracks.map { it.contentUrl },
            downloadTrackUrls = assembly.downloadTrackUrls,
            tracks = assembly.tracks,
            chapters = assembly.chapters,
            totalDurationSec = assembly.totalDurationSec,
            // O'Reilly audiobook progress isn't wired back to the server yet — start at 0 and let the
            // app's own position store drive resume. (No last-writer-wins peer for O'Reilly audio.)
            serverCurrentTimeSec = 0.0,
            serverLastUpdate = 0L,
        )
    }

    override suspend fun getAudiobookChapters(itemId: String): List<CatalogAudiobookChapter> {
        // Cheap: chapter titles + durations come straight from videotocs, no Kaltura session needed.
        val toc = OReillyParser.parseVideoToc(api.getJson(api.videoTocUrl(itemId))).toc
            .filter { it.referenceId.isNotBlank() }
        var offset = 0.0
        return toc.mapIndexed { index, entry ->
            val duration = entry.duration.toDouble()
            val chapter = CatalogAudiobookChapter(
                index = index,
                startSec = offset,
                endSec = offset + duration,
                title = entry.title.ifBlank { "Chapter ${index + 1}" },
            )
            offset += duration
            chapter
        }
    }

    // ---- Connectivity -------------------------------------------------------

    override suspend fun connectivityCheck(): CatalogHealth {
        val start = clock.nowMs()
        val ok = api.ping()
        return CatalogHealth(
            isReachable = ok,
            serverVersion = null,
            latencyMs = clock.nowMs() - start,
            error = if (ok) null else "learning.oreilly.com is unreachable or the session expired",
        )
    }

    companion object {
        const val ROOT_BOOKS = OReillyRoots.BOOKS
        const val ROOT_AUDIOBOOKS = OReillyRoots.AUDIOBOOKS

        /**
         * Files whose declared size is at least this are subject to the truncation check. The DRM
         * sample is ~2KB; real chapters are 10KB+. Below this we can't reliably tell a sample from a
         * genuinely short section (front matter), so we accept it as-is.
         */
        internal const val TRUNCATION_MIN_BYTES = 8000L

        /**
         * A chapter is a throttled DRM sample when its fetched size is far below the file's declared
         * `file_size`. Only applied to files large enough to distinguish (front matter is
         * legitimately small and matches its declared size, so it never false-positives).
         */
        internal fun isTruncatedSample(fetchedLen: Long, expectedSize: Long): Boolean =
            expectedSize >= TRUNCATION_MIN_BYTES && fetchedLen < expectedSize / 2

        /**
         * Truncation check for a fetched chapter [body] against its byte-denominated [expectedSize].
         * MUST measure UTF-8 bytes — `String.length` counts UTF-16 code units, which undercounts
         * multibyte (e.g. CJK) chapters and would falsely flag a full chapter as a DRM sample.
         */
        internal fun isTruncatedBody(body: String, expectedSize: Long): Boolean {
            // Count UTF-8 bytes without materialising a full byte-array copy: each BMP code point
            // outside ASCII contributes 2 (U+0080..U+07FF) or 3 (U+0800..U+FFFF) bytes; a
            // surrogate pair (two Char values) encodes a supplementary code point as 4 bytes.
            var byteLen = 0L
            var i = 0
            while (i < body.length) {
                val c = body[i].code
                byteLen += when {
                    c < 0x80 -> 1
                    c < 0x800 -> 2
                    c in 0xD800..0xDBFF -> { i++; 4 } // high surrogate — low follows
                    else -> 3
                }
                i++
            }
            return isTruncatedSample(byteLen, expectedSize)
        }
    }
}

internal class OReillyException(message: String) : RuntimeException(message)
