package com.riffle.core.catalog.abs

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.BookImportCapability
import com.riffle.core.catalog.CatalogImportMetadata
import com.riffle.core.catalog.CatalogImportResult
import com.riffle.core.catalog.CatalogImportPhase
import com.riffle.core.catalog.CatalogImportProgress
import com.riffle.core.catalog.CatalogImportRequest
import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.BookmarksCapability
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogAudioFingerprint
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.CatalogAudioTrack
import com.riffle.core.catalog.CatalogAudiobookChapter
import com.riffle.core.catalog.CatalogAudiobookStream
import com.riffle.core.catalog.CatalogBookmark
import com.riffle.core.catalog.CatalogCollection
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogPlaylist
import com.riffle.core.catalog.CatalogProgress
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.CatalogSeries
import com.riffle.core.catalog.CatalogSeriesEntry
import com.riffle.core.catalog.CatalogSessionHandle
import com.riffle.core.catalog.CatalogStats
import com.riffle.core.catalog.CfiDialect
import com.riffle.core.catalog.CollectionsCapability
import com.riffle.core.catalog.DownloadsCapability
import com.riffle.core.catalog.OfflineBrowseCapability
import com.riffle.core.catalog.PlaylistsCapability
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.catalog.ReadCapability
import com.riffle.core.catalog.ReadaloudCapability
import com.riffle.core.catalog.ReadingSessionsCapability
import com.riffle.core.catalog.SeriesCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.StatsCapability
import com.riffle.core.catalog.ToReadListCapability
import com.riffle.core.catalog.doesDestinationItemExist
import com.riffle.core.models.AudiobookFingerprint
import com.riffle.core.common.Clock
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsAudioUrl
import com.riffle.core.network.AbsBookmarkApi
import com.riffle.core.network.AbsFileDownloadApi
import com.riffle.core.network.AbsCoverUrl
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAbsAudioTrack
import com.riffle.core.network.NetworkAbsAuthorUpdate
import com.riffle.core.network.NetworkAbsChapterUpdate
import com.riffle.core.network.NetworkAbsMetadataUpdate
import com.riffle.core.network.NetworkAbsSeriesUpdate
import com.riffle.core.network.NetworkAbsBookmark
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkCollection
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkLibrary
import com.riffle.core.network.NetworkLibraryItem
import com.riffle.core.network.NetworkPlaylist
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.NetworkSeries
import com.riffle.core.network.NetworkServerProgress
import com.riffle.core.network.NetworkUploadMetadata
import com.riffle.core.network.NetworkUploadPart
import com.riffle.core.network.errorAsThrowable
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileInputStream

/**
 * The ABS-backed [Catalog] implementation. Wraps the existing ABS HTTP client (split across
 * [AbsLibraryApi], [AbsPlaybackApi], [AbsSessionApi], [AbsBookmarkApi], [AbsServerInfoApi]) and
 * implements every capability ABS provides (all eight).
 *
 * One [AbsCatalog] instance corresponds to one ABS Source row in the DB — the (baseUrl, token)
 * pair lives in [config], the (sourceId) namespace materialises at the repository boundary
 * (issue #434). Nothing in this class writes to local stores; repositories do that.
 *
 * Errors on any method surface as [CatalogException].
 */
class AbsCatalog(
    private val config: AbsCatalogConfig,
    private val libraryApi: AbsLibraryApi,
    private val fileDownloadApi: AbsFileDownloadApi,
    private val playbackApi: AbsPlaybackApi,
    private val sessionApi: AbsSessionApi,
    private val bookmarkApi: AbsBookmarkApi,
    private val serverInfoApi: AbsServerInfoApi,
    private val clock: Clock,
) : Catalog,
    SeriesCapability,
    CollectionsCapability,
    PlaylistsCapability,
    ProgressPeerCapability,
    AudiobookProgressPeerCapability,
    ReadingSessionsCapability,
    StatsCapability,
    AudiobookMediaCapability,
    BookmarksCapability,
    OfflineBrowseCapability,
    DownloadsCapability,
    ReadaloudCapability,
    ToReadListCapability,
    ReadCapability, BookImportCapability {

    override val sourceType: SourceType = SourceType.ABS

    // region Catalog — mandatory core

    override suspend fun listRoots(): List<CatalogRoot> =
        libraryApi.getLibraries(config.baseUrl, config.token, config.insecureAllowed)
            .unwrap()
            .map { it.toCatalogRoot() }

    override suspend fun importBook(request: CatalogImportRequest): CatalogImportResult {
        if (request.files.isEmpty()) {
            return CatalogImportResult.Failed(IllegalArgumentException("An upload needs at least one file"))
        }
        if (request.folderId.isNullOrBlank()) {
            return CatalogImportResult.Failed(
                IllegalArgumentException("ABS upload requires a destination library folder"),
            )
        }
        val tempFiles = mutableListOf<File>()
        val uploadStartMs = clock.nowMs()
        return try {
            request.onProgress(CatalogImportProgress(CatalogImportPhase.Preparing, 0, request.files.size))
            val uploadFiles = request.files.mapIndexed { index, file ->
                val tempFile = materializeImportFile(file)
                tempFiles += tempFile
                request.onProgress(CatalogImportProgress(CatalogImportPhase.Preparing, index + 1, request.files.size))
                NetworkUploadPart(
                    fileName = file.fileName,
                    mimeType = file.mimeType,
                    sizeBytes = tempFile.length(),
                    provider = { FileInputStream(tempFile).toByteReadChannel() },
                )
            }
            // ABS groups files by the shared title/folder fields, but its upload endpoint is
            // unreliable when several files are sent in one multipart request. Upload each
            // audiobook track separately so all tracks still land in the same ABS item.
            val uploadBatches = if (uploadFiles.size == 1) {
                listOf(uploadFiles)
            } else {
                uploadFiles.map(::listOf)
            }
            uploadBatches.forEachIndexed { index, files ->
                request.onProgress(
                    CatalogImportProgress(
                        phase = CatalogImportPhase.Uploading,
                        completedFiles = index,
                        totalFiles = uploadBatches.size,
                    ),
                )
                val result = libraryApi.uploadBook(
                    baseUrl = config.baseUrl,
                    libraryId = request.libraryId,
                    // ABS requires the configured library-folder id here. ABS creates the actual
                    // item directory beneath it from author/series/title metadata.
                    metadata = request.metadata.toNetworkUploadMetadata(request.folderId),
                    files = files,
                    token = config.token,
                    insecureAllowed = config.insecureAllowed,
                )
                result.unwrap()
            }
            request.onProgress(
                CatalogImportProgress(
                    phase = CatalogImportPhase.Uploading,
                    completedFiles = uploadBatches.size,
                    totalFiles = uploadBatches.size,
                ),
            )
            request.onProgress(CatalogImportProgress(CatalogImportPhase.Uploaded))
            // /api/upload only moves files. Ask ABS to start indexing them immediately; the
            // watcher remains asynchronous, so reconciliation below still polls the library.
            try {
                libraryApi.scanLibrary(
                    config.baseUrl,
                    request.libraryId,
                    config.token,
                    config.insecureAllowed,
                ).unwrap()
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                // Scanning is best effort. The normal watcher may still discover the files.
            }
            val reconciliationWarning = mutableListOf<String>()
            request.onProgress(CatalogImportProgress(CatalogImportPhase.Reconciling))
            val destinationItem = try {
                reconcileUploadedItem(request, uploadStartMs)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                reconciliationWarning += "Uploaded, but the destination item could not be reconciled: ${cause.message ?: "unknown error"}"
                null
            }
            if (destinationItem == null) {
                CatalogImportResult.Uploaded(
                    warnings = reconciliationWarning.ifEmpty {
                        listOf("Uploaded, but the destination item could not be reconciled")
                    },
                )
            } else {
                request.onProgress(CatalogImportProgress(CatalogImportPhase.Finalizing))
                val warnings = reconciliationWarning + enrichUploadedItem(destinationItem, request)
                CatalogImportResult.Uploaded(destinationItemId = destinationItem.id, warnings = warnings)
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            CatalogImportResult.Failed(cause)
        } finally {
            tempFiles.forEach(File::delete)
        }
    }

    private suspend fun materializeImportFile(file: com.riffle.core.catalog.CatalogImportFile): File {
        val temp = File.createTempFile("riffle-upload-", ".part")
        try {
            file.withStream { stream ->
                stream.byteStream().use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return temp
        } catch (cause: Throwable) {
            temp.delete()
            throw cause
        }
    }

    /**
     * ABS's upload endpoint only moves the files and does not return a library-item id. The
     * watcher creates the item asynchronously, so poll the selected library listing until the
     * item exists. Search is used as a fallback because its index can lag behind the listing.
     * Reuse the same stable-id/title+author matcher as the UI preflight so all post-upload
     * mutations target the item that was actually created.
     */
    private suspend fun reconcileUploadedItem(request: CatalogImportRequest, uploadStartMs: Long): ReconciledDestinationItem? {
        val sourceIdentity = CatalogItem(
            id = "import",
            rootId = request.libraryId,
            title = request.metadata.title,
            author = request.metadata.author,
            coverUrl = request.metadata.coverUrl,
            ebookFormat = BookFormat.Unsupported,
            seriesName = request.metadata.series,
            seriesSequence = request.metadata.seriesSequence,
            description = request.metadata.description,
            publisher = request.metadata.publisher,
            language = request.metadata.language,
            publishedYear = request.metadata.publishedYear,
            genres = request.metadata.genres,
            isbn = request.metadata.isbn,
            asin = request.metadata.asin,
        )
        repeat(RECONCILIATION_ATTEMPTS) { attempt ->
            // ABS returns immediately when another library scan is already running. A scan
            // kicked off before the upload can therefore miss these files; retry at intervals
            // so the upload is picked up once that scan has finished.
            if (attempt > 0 && attempt % RESCAN_INTERVAL_ATTEMPTS == 0) {
                try {
                    libraryApi.scanLibrary(
                        config.baseUrl,
                        request.libraryId,
                        config.token,
                        config.insecureAllowed,
                    ).unwrap()
                } catch (cause: CancellationException) {
                    throw cause
                } catch (_: Throwable) {
                    // Scanning is best effort; continue polling the listing.
                }
            }
            val candidates = try {
                libraryApi.getRecentlyAddedLibraryItems(
                    config.baseUrl,
                    request.libraryId,
                    RECONCILIATION_LIMIT,
                    config.token,
                    config.insecureAllowed,
                ).unwrap()
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                emptyList()
            }
            // ABS's directory is the identity Riffle requested. Check it before metadata or
            // timestamps: during a rescan, both title/author and addedAt may describe a prior
            // scan state even though the stable author/title folder is already present.
            candidates.firstOrNull { candidate ->
                doesDestinationItemExist(sourceIdentity, listOf(candidate.toCatalogItem()))
            }
                ?.let { return ReconciledDestinationItem(it.id, it.addedAt) }

            // addedAt identifies the item created by this upload even when ABS has not yet
            // parsed the EPUB/ID3 metadata, so do not require title/author matching here.
            candidates.firstOrNull { it.addedAt?.let { addedAt -> addedAt > uploadStartMs } == true }
                ?.let { return ReconciledDestinationItem(it.id, it.addedAt) }

            // Keep the logical matcher as a compatibility fallback for servers that omit
            // addedAt or have clock skew between the client and ABS.
            val fallbackCandidates = buildList {
                addAll(candidates)
                try {
                    addAll(
                        libraryApi.searchLibrary(
                            config.baseUrl,
                            request.libraryId,
                            request.metadata.title,
                            limit = 50,
                            config.token,
                            config.insecureAllowed,
                        ).unwrap()
                    )
                } catch (cause: CancellationException) {
                    throw cause
                } catch (_: Throwable) {
                    // Search indexing is asynchronous on ABS; continue polling the listing.
                }
            }
            fallbackCandidates.firstOrNull { candidate ->
                doesDestinationItemExist(sourceIdentity, listOf(candidate.toCatalogItem()))
            }?.let { return ReconciledDestinationItem(it.id, it.addedAt) }
            if (attempt < RECONCILIATION_ATTEMPTS - 1) delay(RECONCILIATION_DELAY_MS)
        }
        return null
    }

    private suspend fun enrichUploadedItem(
        destination: ReconciledDestinationItem,
        request: CatalogImportRequest,
    ): List<String> {
        val itemId = destination.id
        val warnings = mutableListOf<String>()
        suspend fun attempt(label: String, operation: suspend () -> Unit) {
            try {
                operation()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                warnings += "$label could not be applied: ${cause.message ?: "unknown error"}"
            }
        }

        attempt("Metadata") {
            val metadataUpdate = request.metadata.toNetworkAbsMetadataUpdate()
            libraryApi.updateItemMedia(
                config.baseUrl,
                itemId,
                metadataUpdate,
                config.token,
                config.insecureAllowed,
            ).unwrap()

            // /api/upload returns after moving files; ABS's watcher can still finish scanning the
            // directory and overwrite metadata from the EPUB/audio tags. Give that scan a chance
            // to settle, then re-apply the source metadata if it was overwritten.
            val remainingScanDelay = destination.addedAt
                ?.let { (it + METADATA_SETTLE_DELAY_MS - clock.nowMs()).coerceAtLeast(0L) }
                ?: METADATA_SETTLE_DELAY_MS
            delay(remainingScanDelay)
            val current = runCatching {
                libraryApi.getItem(
                    config.baseUrl,
                    itemId,
                    config.token,
                    config.insecureAllowed,
                ).unwrap()
            }.getOrNull()
            if (current == null || current.metadataDiffersFrom(request.metadata)) {
                libraryApi.updateItemMedia(
                    config.baseUrl,
                    itemId,
                    metadataUpdate,
                    config.token,
                    config.insecureAllowed,
                ).unwrap()
            }
        }
        if (request.chapters.isNotEmpty()) {
            attempt("Chapters") {
                libraryApi.updateItemChapters(
                    config.baseUrl,
                    itemId,
                    request.chapters.map { chapter ->
                        NetworkAbsChapterUpdate(chapter.id, chapter.startSec, chapter.endSec, chapter.title)
                    },
                    config.token,
                    config.insecureAllowed,
                ).unwrap()
            }
        }
        request.metadata.coverUrl?.takeIf(String::isNotBlank)?.let { coverUrl ->
            attempt("Cover") {
                libraryApi.uploadItemCoverFromUrl(
                    config.baseUrl,
                    itemId,
                    coverUrl,
                    config.token,
                    config.insecureAllowed,
                ).unwrap()
            }
        }
        if (request.ebookLocation != null) {
            request.readingProgress?.let { progress ->
                attempt("Reading progress") {
                    sessionApi.syncEbookProgress(
                        config.baseUrl,
                        itemId,
                        NetworkEbookProgressPayload(
                            // An empty location is valid for ABS and preserves the numeric
                            // progress when no source CFI is available. Never send a raw EPUB
                            // href here: ABS expects its epubcfi(...) dialect.
                            ebookLocation = request.ebookLocation,
                            ebookProgress = progress.coerceIn(0f, 1f),
                            isFinished = progress >= 1f,
                        ),
                        config.token,
                        config.insecureAllowed,
                    ).unwrap()
                }
            }
        }
        if (request.audioDurationSec > 0.0) {
            request.readingProgress?.let { progress ->
                attempt("Listening progress") {
                    sessionApi.syncAudiobookProgress(
                        config.baseUrl,
                        itemId,
                        NetworkAudiobookProgressPayload(
                            currentTime = progress.coerceIn(0f, 1f) * request.audioDurationSec,
                            duration = request.audioDurationSec,
                        ),
                        config.token,
                        config.insecureAllowed,
                    ).unwrap()
                }
            }
        }
        return warnings
    }

    override suspend fun browse(
        rootId: String,
        sort: SortKey,
        page: Int,
        pageSize: Int,
        facet: FacetSelection?,
    ): List<CatalogItem> {
        // ABS exposes no server-side facets today — `facet` is ignored.
        val items = libraryApi.getLibraryItems(config.baseUrl, rootId, config.token, config.insecureAllowed)
            .unwrap()
            .map { it.toCatalogItem() }
            .sortedWith(comparatorFor(sort))
        return items.pageOf(page, pageSize)
    }

    override suspend fun search(
        rootId: String,
        query: String,
        page: Int,
        pageSize: Int,
    ): List<CatalogItem> {
        // ABS's search endpoint takes `limit` (total cap) — not per-page — so request enough for
        // the page window, then slice client-side. Callers paging past `limit` get an empty list.
        val limit = ((page + 1) * pageSize).coerceAtLeast(pageSize)
        val hits = libraryApi.searchLibrary(config.baseUrl, rootId, query, limit, config.token, config.insecureAllowed)
            .unwrap()
            .map { it.toCatalogItem() }
        return hits.pageOf(page, pageSize)
    }

    override suspend fun getItem(itemId: String): CatalogItem? =
        libraryApi.getItem(config.baseUrl, itemId, config.token, config.insecureAllowed)
            .unwrap()
            ?.toCatalogItem()

    override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle {
        val authHeaders = mapOf("Authorization" to "Bearer ${config.token}")
        return when (format) {
            BookFormat.Epub, BookFormat.Pdf, BookFormat.Cbz -> {
                val ino = libraryApi.getItemEbookFileIno(config.baseUrl, itemId, config.token, config.insecureAllowed).unwrap()
                CatalogFileHandle.Stream(
                    url = "${config.baseUrl.trimEnd('/')}/api/items/$itemId/ebook/$ino",
                    headers = authHeaders,
                    format = format,
                )
            }
            BookFormat.Audiobook -> {
                // Audiobook streams are per-track — callers use AudiobookMediaCapability instead.
                throw CatalogException.UnsupportedFormat("Audiobook file handles are per-track — use AudiobookMediaCapability.buildStreamUrl")
            }
            BookFormat.Unsupported -> throw CatalogException.UnsupportedFormat("Cannot fetch Unsupported format")
        }
    }

    override suspend fun <T> withFileStream(
        itemId: String,
        format: BookFormat,
        handleHint: String?,
        block: suspend (CatalogFileStream) -> T,
    ): T = when (format) {
        BookFormat.Epub, BookFormat.Pdf, BookFormat.Cbz -> {
            val ino = handleHint?.takeIf { it.isNotEmpty() }
                ?: libraryApi.getItemEbookFileIno(config.baseUrl, itemId, config.token, config.insecureAllowed).unwrap()
            when (
                val result = fileDownloadApi.streamFile(
                    config.baseUrl,
                    itemId,
                    ino,
                    config.token,
                    config.insecureAllowed,
                ) { body ->
                    block(
                        object : CatalogFileStream {
                            override val contentLength: Long = body.contentLength
                            override fun byteStream(): java.io.InputStream = body.inputStream
                            override fun close() { body.inputStream.close() }
                        },
                    )
                }
            ) {
                is NetworkResult.Success -> result.value
                else -> throw CatalogException.Unknown(result.errorAsThrowable())
            }
        }
        BookFormat.Audiobook -> throw CatalogException.UnsupportedFormat(
            "Audiobook file streams are per-track — use AudiobookMediaCapability.buildStreamUrl",
        )
        BookFormat.Unsupported -> throw CatalogException.UnsupportedFormat("Cannot open Unsupported format")
    }

    override suspend fun connectivityCheck(): CatalogHealth {
        // AbsApiClient.getServerInfo swallows failures and returns null on any error, so we can't
        // surface a specific error string — reachability collapses to (version != null).
        val startMs = clock.nowMs()
        val version = serverInfoApi.getServerInfo(config.baseUrl, config.token, config.insecureAllowed)
        return CatalogHealth(
            isReachable = version != null,
            serverVersion = version,
            latencyMs = clock.nowMs() - startMs,
        )
    }

    // endregion

    // region SeriesCapability

    override suspend fun listSeries(rootId: String): List<CatalogSeries> =
        libraryApi.getSeries(config.baseUrl, rootId, config.token, config.insecureAllowed)
            .unwrap()
            .map { it.toCatalogSeries() }

    override suspend fun listItemsInSeries(rootId: String, seriesId: String): List<CatalogItem> {
        // ABS returns series-with-embedded-books via the same endpoint used by listSeries;
        // scan the response for the matching series and map its books to CatalogItem.
        val series = libraryApi.getSeries(config.baseUrl, rootId, config.token, config.insecureAllowed).unwrap()
        val match = series.firstOrNull { it.id == seriesId } ?: return emptyList()
        return match.items.map { book ->
            CatalogItem(
                id = book.id,
                rootId = book.libraryId.ifEmpty { rootId },
                title = book.title,
                author = book.author,
                coverUrl = coverUrl(book.id, book.updatedAt),
                ebookFormat = book.ebookFormat.toCatalogFormat(hasAudio = book.hasAudio),
                hasAudio = book.hasAudio,
                audioDurationSec = book.audioDurationSec,
                ebookFileIno = book.ebookFileIno,
                description = book.description,
                seriesName = book.seriesName,
                seriesSequence = book.sequence,
                publishedYear = book.publishedYear,
                genres = book.genres,
                publisher = book.publisher,
                readingProgress = book.readingProgress,
                updatedAt = book.updatedAt,
            )
        }
    }

    // endregion

    // region CollectionsCapability

    override suspend fun listCollections(rootId: String): List<CatalogCollection> =
        libraryApi.getCollections(config.baseUrl, rootId, config.token, config.insecureAllowed)
            .unwrap()
            .map { it.toCatalogCollection() }

    override suspend fun createCollection(rootId: String, name: String): CatalogCollection {
        val created = libraryApi.createCollection(config.baseUrl, rootId, name, initialBookId = null, config.token, config.insecureAllowed)
            .unwrap()
            ?: throw CatalogException.Unknown(IllegalStateException("ABS returned null for createCollection"))
        return created.toCatalogCollection()
    }

    override suspend fun addItemToCollection(collectionId: String, itemId: String) {
        libraryApi.addBookToCollection(config.baseUrl, collectionId, itemId, config.token, config.insecureAllowed).unwrap()
    }

    override suspend fun removeItemFromCollection(collectionId: String, itemId: String) {
        libraryApi.removeBookFromCollection(config.baseUrl, collectionId, itemId, config.token, config.insecureAllowed).unwrap()
    }

    // endregion

    // region PlaylistsCapability

    override suspend fun listPlaylists(rootId: String): List<CatalogPlaylist> =
        libraryApi.getPlaylists(config.baseUrl, rootId, config.token, config.insecureAllowed)
            .unwrap()
            .map { it.toCatalogPlaylist() }

    override suspend fun findPlaylist(rootId: String, name: String): CatalogPlaylist? =
        // ABS's `listPlaylists` returns fully-populated itemIds, so the trivial "list + filter"
        // is correct here (unlike Komga where listPlaylists is summary-only). Cheap enough to
        // not warrant a name-scoped endpoint.
        listPlaylists(rootId).firstOrNull { it.name == name }

    override suspend fun createPlaylist(rootId: String, name: String, initialItemId: String?): CatalogPlaylist {
        val created = libraryApi.createPlaylist(config.baseUrl, rootId, name, initialBookId = initialItemId, config.token, config.insecureAllowed)
            .unwrap()
            ?: throw CatalogException.Unknown(IllegalStateException("ABS returned null for createPlaylist"))
        return created.toCatalogPlaylist()
    }

    override suspend fun addItemToPlaylist(playlistId: String, itemId: String) {
        libraryApi.addBookToPlaylist(config.baseUrl, playlistId, itemId, config.token, config.insecureAllowed).unwrap()
    }

    override suspend fun removeItemFromPlaylist(playlistId: String, itemId: String) {
        libraryApi.removeBookFromPlaylist(config.baseUrl, playlistId, itemId, config.token, config.insecureAllowed).unwrap()
    }

    // endregion

    // region ProgressPeerCapability

    override val cfiDialect: CfiDialect = CfiDialect.EPUB_JS

    override suspend fun pushEbookProgress(
        itemId: String,
        location: String,
        progress: Float,
        isFinished: Boolean?,
        lastUpdateEpochMs: Long,
    ): Long? = sessionApi.syncEbookProgress(
        config.baseUrl,
        itemId,
        // Leave `isFinished` nullable through to the payload: null = leave the audio dimension of
        // ABS's shared media-progress record untouched. Non-null zeroes the audio side per
        // NetworkEbookProgressPayload's contract — only mark-read/mark-unread callers do that.
        NetworkEbookProgressPayload(ebookLocation = location, ebookProgress = progress, isFinished = isFinished),
        config.token,
        config.insecureAllowed,
    ).unwrap()

    override suspend fun pushAudiobookProgress(
        itemId: String,
        currentTimeSec: Double,
        durationSec: Double,
        isFinished: Boolean?,
        lastUpdateEpochMs: Long,
    ): Long? {
        // ABS derives finished-state server-side from progress==1.0 for audiobook records (ADR 0035),
        // so the `isFinished` param is captured for capability parity but not forwarded here.
        return sessionApi.syncAudiobookProgress(
            config.baseUrl,
            itemId,
            NetworkAudiobookProgressPayload(currentTime = currentTimeSec, duration = durationSec),
            config.token,
            config.insecureAllowed,
        ).unwrap()
    }

    override suspend fun pullProgress(itemId: String): CatalogProgress? {
        // A successful GET always yields a CatalogProgress (fields may all be empty for a
        // never-touched item — callers detect that via `lastUpdate <= 0L`). A network failure
        // surfaces as a thrown [CatalogException] from `unwrap()`; the peer-adapter's runCatching
        // treats that as "unreachable" and returns null. Collapsing "reachable-empty" to null here
        // would make the two states indistinguishable and drop the first push on a fresh book.
        val p = sessionApi.getProgress(config.baseUrl, itemId, config.token, config.insecureAllowed).unwrap()
        return p.toCatalogProgress(itemId)
    }

    override suspend fun pullAllProgress(): List<CatalogProgress> =
        libraryApi.getUserProgress(config.baseUrl, config.token, config.insecureAllowed)
            .unwrap()
            .map { (id, p) ->
                CatalogProgress(
                    itemId = id,
                    ebookLocation = null,
                    ebookProgress = p.ebookProgress ?: 0f,
                    audioCurrentTime = p.currentTime,
                    audioDuration = p.duration,
                    isFinished = p.isFinished || p.finishedAt != null,
                    finishedAt = p.finishedAt,
                    lastUpdate = p.lastUpdate ?: 0L,
                )
            }

    // endregion

    // region ReadingSessionsCapability

    override suspend fun openSession(itemId: String, deviceLabel: String): CatalogSessionHandle {
        val session = playbackApi.openPlaybackSession(config.baseUrl, itemId, config.deviceId, config.token, config.insecureAllowed).unwrap()
        val sessionId = session.sessionId
            ?: throw CatalogException.Unknown(IllegalStateException("ABS returned null sessionId for item $itemId"))
        return CatalogSessionHandle(
            sessionId = sessionId,
            itemId = itemId,
            startedAtEpochMs = clock.nowMs(),
        )
    }

    override suspend fun syncSession(handle: CatalogSessionHandle, currentTimeSec: Double, timeListenedSec: Double) {
        playbackApi.syncPlaybackSession(config.baseUrl, handle.sessionId, currentTimeSec, timeListenedSec, config.token, config.insecureAllowed).unwrap()
    }

    override suspend fun closeSession(handle: CatalogSessionHandle, currentTimeSec: Double, timeListenedSec: Double) {
        playbackApi.closePlaybackSession(config.baseUrl, handle.sessionId, currentTimeSec, timeListenedSec, config.token, config.insecureAllowed).unwrap()
    }

    // endregion

    // region StatsCapability

    override suspend fun getStats(): CatalogStats {
        val totalTime = serverInfoApi.getListeningStats(config.baseUrl, config.token, config.insecureAllowed).unwrap().totalTimeSec
        val progressRecords = libraryApi.getUserProgress(config.baseUrl, config.token, config.insecureAllowed).unwrap().values
        return CatalogStats(
            totalSecondsListened = totalTime,
            totalItemsInProgress = progressRecords.count { it.finishedAt == null },
            totalItemsFinished = progressRecords.count { it.finishedAt != null },
        )
    }

    // endregion

    // region AudiobookMediaCapability

    override suspend fun getTracks(itemId: String): List<CatalogAudioTrack> {
        val tracks = libraryApi.getAudiobookTracks(config.baseUrl, itemId, config.token, config.insecureAllowed).unwrap()
        var running = 0.0
        return tracks.map { t ->
            val startOffset = running
            running += t.durationSec
            t.toCatalogAudioTrack(itemId, startOffset)
        }
    }

    override suspend fun getFingerprint(itemId: String): CatalogAudioFingerprint? {
        // A successful GET with no audiobook is a first-class outcome (Success(null) on the
        // network layer) — surface it as null so the caller persists a definitive NO_AUDIOBOOK
        // verdict rather than re-fingerprinting on every open. Network failures still throw.
        val fp: AudiobookFingerprint = libraryApi.getAudiobookFingerprint(config.baseUrl, itemId, config.token, config.insecureAllowed).unwrap()
            ?: return null
        return CatalogAudioFingerprint(
            itemId = itemId,
            fileSizeBytes = fp.fileSizeBytes,
            totalDurationSec = fp.durationSec,
            trackDurations = fp.trackDurationsSec,
        )
    }

    override suspend fun getAudiobookChapters(itemId: String): List<CatalogAudiobookChapter> {
        val detail = when (val r = libraryApi.getItemDetail(config.baseUrl, itemId, config.token, config.insecureAllowed)) {
            is NetworkResult.Success -> r.value
            else -> return emptyList()
        }
        return detail.media.chapters.mapIndexed { i, c ->
            CatalogAudiobookChapter(index = i, startSec = c.startSec, endSec = c.endSec, title = c.title)
        }
    }

    override fun buildStreamUrl(itemId: String, trackIno: String): String {
        val base = AbsAudioUrl.track(config.baseUrl, itemId, trackIno)
        val sep = if (base.contains("?")) "&" else "?"
        return "$base${sep}token=${config.token}"
    }

    override suspend fun openAudiobook(itemId: String, deviceLabel: String): CatalogAudiobookStream? {
        val session = when (
            val r = playbackApi.openPlaybackSession(config.baseUrl, itemId, config.deviceId, config.token, config.insecureAllowed)
        ) {
            is NetworkResult.Success -> r.value
            else -> return null
        }
        if (session.tracks.isEmpty()) return null

        val baseTrimmed = config.baseUrl.trimEnd('/')
        val trackUrls = session.tracks.map { t ->
            val path = if (t.contentUrl.startsWith("/")) t.contentUrl else "/${t.contentUrl}"
            val sep = if (t.contentUrl.contains("?")) "&" else "?"
            "$baseTrimmed$path${sep}token=${config.token}"
        }
        val tracks = session.tracks.map { t ->
            CatalogAudioTrack(
                ino = t.contentUrl.substringAfterLast("/"),
                index = t.index,
                startOffsetSec = t.startOffsetSec,
                durationSec = t.durationSec,
                contentUrl = "$baseTrimmed${if (t.contentUrl.startsWith("/")) t.contentUrl else "/${t.contentUrl}"}",
                mimeType = t.mimeType,
            )
        }
        val chapters = session.chapters.mapIndexed { i, c ->
            CatalogAudiobookChapter(index = i, startSec = c.startSec, endSec = c.endSec, title = c.title)
        }
        val serverLastUpdate = (
            sessionApi.getProgress(config.baseUrl, itemId, config.token, config.insecureAllowed) as? NetworkResult.Success
        )?.value?.lastUpdate ?: 0L

        return CatalogAudiobookStream(
            trackUrls = trackUrls,
            tracks = tracks,
            chapters = chapters,
            totalDurationSec = session.durationSec,
            serverCurrentTimeSec = session.currentTimeSec,
            serverLastUpdate = serverLastUpdate,
        )
    }

    // endregion

    // region BookmarksCapability

    override suspend fun listAllBookmarks(): List<CatalogBookmark> =
        bookmarkApi.listBookmarks(config.baseUrl, config.token, config.insecureAllowed)
            .unwrap()
            .map { it.toCatalogBookmark() }

    override suspend fun createBookmark(itemId: String, timeSec: Int, title: String): CatalogBookmark =
        bookmarkApi.createBookmark(config.baseUrl, itemId, timeSec, title, config.token, config.insecureAllowed)
            .unwrap()
            .toCatalogBookmark()

    override suspend fun deleteBookmark(itemId: String, timeSec: Int) {
        bookmarkApi.deleteBookmark(config.baseUrl, itemId, timeSec, config.token, config.insecureAllowed).unwrap()
    }

    override suspend fun renameBookmark(itemId: String, timeSec: Int, newTitle: String): CatalogBookmark =
        bookmarkApi.updateBookmark(config.baseUrl, itemId, timeSec, newTitle, config.token, config.insecureAllowed)
            .unwrap()
            .toCatalogBookmark()

    private fun NetworkAbsBookmark.toCatalogBookmark(): CatalogBookmark = CatalogBookmark(
        itemId = libraryItemId,
        timeSec = timeSec,
        title = title,
        createdAt = createdAt,
    )

    // endregion

    // region mappers

    private fun coverUrl(itemId: String, updatedAt: Long?): String =
        AbsCoverUrl.of(config.baseUrl, itemId, updatedAt)

    private fun NetworkLibrary.toCatalogRoot(): CatalogRoot = CatalogRoot(
        id = id,
        name = name,
        mediaType = mediaType,
        isUnsupported = mediaType == "podcast",
        importFolderId = folders.firstOrNull()?.id,
    )

    private fun NetworkLibraryItem.toCatalogItem(): CatalogItem = CatalogItem(
        id = id,
        rootId = libraryId,
        title = title,
        author = author,
        coverUrl = coverUrl(id, updatedAt),
        ebookFormat = ebookFormat.toCatalogFormat(hasAudio = hasAudio),
        hasAudio = hasAudio,
        audioDurationSec = audioDurationSec,
        ebookFileIno = ebookFileIno,
        description = description,
        seriesName = seriesName,
        publishedYear = publishedYear,
        genres = genres,
        publisher = publisher,
        language = language,
        addedAt = addedAt,
        isbn = isbn,
        asin = asin,
        readingProgress = readingProgress,
        updatedAt = updatedAt,
        path = path,
        relPath = relPath,
    )

    private fun NetworkSeries.toCatalogSeries(): CatalogSeries = CatalogSeries(
        id = id,
        rootId = libraryId,
        name = name,
        coverUrl = items.firstOrNull()?.let { coverUrl(it.id, it.updatedAt) },
        bookCount = bookCount,
        items = items.map { CatalogSeriesEntry(itemId = it.id, sequence = it.sequence) },
    )

    private fun NetworkCollection.toCatalogCollection(): CatalogCollection = CatalogCollection(
        id = id,
        rootId = libraryId,
        name = name,
        bookCount = bookCount,
        itemIds = items.map { it.id },
    )

    private fun NetworkPlaylist.toCatalogPlaylist(): CatalogPlaylist = CatalogPlaylist(
        id = id,
        rootId = libraryId,
        name = name,
        bookCount = bookCount,
        itemIds = items.map { it.id },
    )

    private fun NetworkServerProgress.toCatalogProgress(itemId: String): CatalogProgress = CatalogProgress(
        itemId = itemId,
        ebookLocation = ebookLocation.takeIf { it.isNotEmpty() },
        ebookProgress = ebookProgress,
        audioCurrentTime = currentTime,
        audioDuration = duration,
        // NetworkServerProgress lacks an explicit `finishedAt`, so derive the same way ABS does
        // server-side: ebook 100% OR audio at/past duration. Matches pullAllProgress, which reads
        // ABS's user-level `finishedAt` — either path answers the same question for the same item.
        isFinished = ebookProgress >= 1f || (duration > 0.0 && currentTime >= duration),
        lastUpdate = lastUpdate,
    )

    private fun NetworkAbsAudioTrack.toCatalogAudioTrack(itemId: String, startOffsetSec: Double): CatalogAudioTrack =
        CatalogAudioTrack(
            ino = ino,
            index = index,
            startOffsetSec = startOffsetSec,
            durationSec = durationSec,
            contentUrl = AbsAudioUrl.track(config.baseUrl, itemId, ino),
        )

    private fun EbookFormat.toCatalogFormat(hasAudio: Boolean = false): BookFormat = when (this) {
        EbookFormat.Epub -> BookFormat.Epub
        EbookFormat.Pdf -> BookFormat.Pdf
        EbookFormat.Cbz -> BookFormat.Cbz
        EbookFormat.Unsupported -> if (hasAudio) BookFormat.Audiobook else BookFormat.Unsupported
    }

    private fun comparatorFor(sort: SortKey): Comparator<CatalogItem> = when (sort) {
        SortKey.TITLE -> compareBy { it.title.lowercase() }
        SortKey.AUTHOR -> compareBy { it.author.lowercase() }
        SortKey.ADDED_AT -> compareByDescending { it.addedAt ?: 0L }
        SortKey.PUBLISHED_YEAR -> compareBy { it.publishedYear ?: "" }
        // Last-opened is a per-device local concept ABS doesn't track. Repositories (#434) apply
        // this ordering on top of catalog output; the Catalog layer refuses so silent fall-through
        // to title-order can't mask the missing local-store lookup.
        SortKey.RECENTLY_OPENED -> throw CatalogException.UnsupportedFormat(
            "SortKey.RECENTLY_OPENED is a local ordering — apply it above the Catalog layer",
        )
    }

    private fun <T> List<T>.pageOf(page: Int, pageSize: Int): List<T> {
        val from = (page * pageSize).coerceAtLeast(0)
        if (from >= size) return emptyList()
        val to = (from + pageSize).coerceAtMost(size)
        return subList(from, to)
    }

    private fun CatalogImportMetadata.toNetworkUploadMetadata(folderId: String?) = NetworkUploadMetadata(
        title = title,
        author = author,
        folderId = folderId,
        series = series,
        description = description,
        publisher = publisher,
        language = language,
        publishedYear = publishedYear,
        genres = genres,
        isbn = isbn,
        asin = asin,
    )

    private fun CatalogImportMetadata.toNetworkAbsMetadataUpdate() = NetworkAbsMetadataUpdate(
        title = title,
        authors = listOf(NetworkAbsAuthorUpdate(author)),
        series = series?.let { listOf(NetworkAbsSeriesUpdate(it, seriesSequence)) }.orEmpty(),
        genres = genres,
        publishedYear = publishedYear,
        publisher = publisher,
        description = description,
        isbn = isbn,
        asin = asin,
        language = language,
    )

    private fun NetworkLibraryItem.metadataDiffersFrom(expected: CatalogImportMetadata): Boolean =
        title != expected.title ||
            author != expected.author ||
            (expected.description != null && description != expected.description) ||
            (expected.series != null && seriesName != expected.series) ||
            (expected.publishedYear != null && publishedYear != expected.publishedYear) ||
            (expected.genres.isNotEmpty() && genres != expected.genres) ||
            (expected.publisher != null && publisher != expected.publisher) ||
            (expected.language != null && language != expected.language) ||
            (expected.isbn != null && isbn != expected.isbn) ||
            (expected.asin != null && asin != expected.asin)

    private data class ReconciledDestinationItem(
        val id: String,
        val addedAt: Long?,
    )

    private companion object {
        const val RECONCILIATION_ATTEMPTS = 30
        const val RECONCILIATION_DELAY_MS = 2_000L
        // The upload may reuse an existing author/title directory, so ABS keeps the item's
        // original addedAt and it may no longer be in the newest ten results. Fetch a full
        // library-sized window so reconciliation can still locate that item.
        const val RECONCILIATION_LIMIT = 1_000
        const val RESCAN_INTERVAL_ATTEMPTS = 5
        const val METADATA_SETTLE_DELAY_MS = 14_000L
    }

    // endregion
}
