package com.riffle.core.catalog.abs

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.BookImportCapability
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogImportFile
import com.riffle.core.catalog.CatalogImportChapter
import com.riffle.core.catalog.CatalogImportMetadata
import com.riffle.core.catalog.CatalogImportRequest
import com.riffle.core.catalog.CatalogImportResult
import com.riffle.core.catalog.CatalogImportPhase
import com.riffle.core.catalog.CatalogFileHandle
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
import com.riffle.core.catalog.has
import com.riffle.core.models.AudiobookFingerprint
import com.riffle.core.common.Clock
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsBookmarkApi
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsFileDownloadApi
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAbsAudioTrack
import com.riffle.core.network.NetworkAbsChapterUpdate
import com.riffle.core.network.NetworkAbsMetadataUpdate
import com.riffle.core.network.NetworkAbsBookmark
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkAudioTrack
import com.riffle.core.network.NetworkCollection
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkLibrary
import com.riffle.core.network.NetworkLibraryFolder
import com.riffle.core.network.NetworkLibraryItem
import com.riffle.core.network.NetworkListeningStats
import com.riffle.core.network.NetworkPlaybackSession
import com.riffle.core.network.NetworkPlaylist
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.NetworkSeries
import com.riffle.core.network.NetworkSeriesItem
import com.riffle.core.network.NetworkServerProgress
import com.riffle.core.network.NetworkUserMediaProgress
import com.riffle.core.network.NetworkUploadMetadata
import com.riffle.core.network.NetworkUploadPart
import kotlinx.coroutines.test.runTest
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.ByteReadChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AbsCatalogTest {

    private val config = AbsCatalogConfig(
        baseUrl = "https://abs.example.com",
        token = "T",
        insecureAllowed = false,
        deviceId = "device-A",
    )

    private val clock = object : Clock {
        var now = 1_700_000_000_000L
        override fun nowMs(): Long = now
        override fun nowNs(): Long = 0L
    }

    private val libraryApi = FakeAbsLibraryApi()
    private val fileDownloadApi = object : AbsFileDownloadApi {
        override suspend fun <T> streamFile(
            baseUrl: String,
            itemId: String,
            fileIno: String,
            token: String,
            insecureAllowed: Boolean,
            block: suspend (com.riffle.core.network.AbsFileStream) -> T,
        ): NetworkResult<T> = NetworkResult.Unknown(UnsupportedOperationException("not used by this test"))
    }
    private val playbackApi = FakeAbsPlaybackApi()
    private val sessionApi = FakeAbsSessionApi()
    private val serverInfoApi = FakeAbsServerInfoApi()
    private val bookmarkApi = FakeAbsBookmarkApi()

    private val catalog = AbsCatalog(
        config = config,
        libraryApi = libraryApi,
        fileDownloadApi = fileDownloadApi,
        playbackApi = playbackApi,
        sessionApi = sessionApi,
        bookmarkApi = bookmarkApi,
        serverInfoApi = serverInfoApi,
        clock = clock,
    )

    // region sourceType + capability presence

    @Test fun `sourceType is ABS`() {
        assertEquals(SourceType.ABS, catalog.sourceType)
    }

    @Test fun `implements every capability ABS provides`() {
        assertTrue(catalog.has<SeriesCapability>())
        assertTrue(catalog.has<CollectionsCapability>())
        assertTrue(catalog.has<PlaylistsCapability>())
        assertTrue(catalog.has<ProgressPeerCapability>())
        assertTrue(catalog.has<ReadingSessionsCapability>())
        assertTrue(catalog.has<StatsCapability>())
        assertTrue(catalog.has<AudiobookMediaCapability>())
        assertTrue(catalog.has<OfflineBrowseCapability>())
        assertTrue(catalog.has<DownloadsCapability>())
        assertTrue(catalog.has<ReadaloudCapability>())
        assertTrue(catalog.has<ToReadListCapability>())
        assertTrue(catalog.has<ReadCapability>())
        assertTrue(catalog.has<BookImportCapability>())
    }

    // endregion

    @Test fun `importBook forwards metadata and ordered source files`() = runTest {
        val sourceBytes = "epub".encodeToByteArray()
        val result = catalog.importBook(
            CatalogImportRequest(
                libraryId = "lib-a",
                folderId = "folder-a",
                metadata = CatalogImportMetadata(title = "A title", author = "An author"),
                files = listOf(
                    CatalogImportFile(
                        fileName = "01.epub",
                        mimeType = "application/epub+zip",
                        withStream = { block ->
                            block(object : CatalogFileStream {
                                override val contentLength = sourceBytes.size.toLong()
                                override val channel = ByteReadChannel(sourceBytes)
                            })
                        },
                    ),
                ),
            ),
        )

        assertTrue(result is com.riffle.core.catalog.CatalogImportResult.Uploaded)
        assertEquals("lib-a", libraryApi.lastUploadLibraryId)
        assertEquals("folder-a", libraryApi.lastUploadMetadata?.folderId)
        assertEquals("A title", libraryApi.lastUploadMetadata?.title)
        assertEquals("01.epub", libraryApi.lastUploadFiles.single().fileName)
        assertEquals(sourceBytes.toList(), libraryApi.lastUploadBytes.single())
    }

    @Test fun `importBook uploads audiobook files as separate ABS uploads`() = runTest {
        libraryApi.libraryItems["lib-a"] = listOf(
            item("created", title = "A title", author = "An author", addedAt = clock.now + 1),
        )
        libraryApi.singleItems["created"] = item("created", title = "A title", author = "An author")

        val result = catalog.importBook(
            CatalogImportRequest(
                libraryId = "lib-a",
                folderId = "folder-a",
                metadata = CatalogImportMetadata(title = "A title", author = "An author"),
                files = listOf("01.mp3", "02.mp3").map { fileName ->
                    CatalogImportFile(
                        fileName = fileName,
                        mimeType = "audio/mpeg",
                        withStream = { block ->
                            block(object : CatalogFileStream {
                                override val contentLength = 1L
                                override val channel = ByteReadChannel(byteArrayOf(1))
                            })
                        },
                    )
                },
            ),
        )

        assertTrue(result is CatalogImportResult.Uploaded)
        assertEquals(listOf(listOf("01.mp3"), listOf("02.mp3")), libraryApi.uploadCalls)
    }

    @Test fun `importBook reconciles an existing item outside the newest ten`() = runTest {
        libraryApi.libraryItems["lib-a"] = (1..10).map { index ->
            item("new-$index", title = "New $index", author = "Other", addedAt = clock.now - index)
        } + item("existing", title = "A title", author = "An author", addedAt = clock.now - 100)
        libraryApi.singleItems["existing"] = item("existing", title = "A title", author = "An author")

        val result = catalog.importBook(
            CatalogImportRequest(
                libraryId = "lib-a",
                folderId = "folder-a",
                metadata = CatalogImportMetadata(title = "A title", author = "An author"),
                files = listOf(
                    CatalogImportFile(
                        fileName = "book.epub",
                        mimeType = "application/epub+zip",
                        withStream = { block ->
                            block(object : CatalogFileStream {
                                override val contentLength = 1L
                                override val channel = ByteReadChannel(byteArrayOf(1))
                            })
                        },
                    ),
                ),
            ),
        )

        assertEquals("existing", (result as CatalogImportResult.Uploaded).destinationItemId)
        assertEquals(1_000, libraryApi.lastRecentlyAddedLimit)
    }

    @Test fun `importBook reconciles existing ABS folder when embedded metadata differs`() = runTest {
        libraryApi.libraryItems["lib-a"] = listOf(
            item(
                "existing",
                title = "A title :embedded tag",
                author = "Anauthor",
                addedAt = clock.now - 100,
            ).copy(path = "/books/An author/A title"),
        )
        libraryApi.singleItems["existing"] = item("existing", title = "A title", author = "An author")

        val result = catalog.importBook(
            CatalogImportRequest(
                libraryId = "lib-a",
                folderId = "folder-a",
                metadata = CatalogImportMetadata(title = "A title", author = "An author"),
                files = listOf(
                    CatalogImportFile(
                        fileName = "book.epub",
                        mimeType = "application/epub+zip",
                        withStream = { block ->
                            block(object : CatalogFileStream {
                                override val contentLength = 1L
                                override val channel = ByteReadChannel(byteArrayOf(1))
                            })
                        },
                    ),
                ),
            ),
        )

        assertEquals("existing", (result as CatalogImportResult.Uploaded).destinationItemId)
    }

    @Test fun `importBook reconciles ABS folder when author spacing differs`() = runTest {
        libraryApi.libraryItems["lib-a"] = listOf(
            item(
                "existing",
                title = "A title :embedded tag",
                author = "Anauthor",
                addedAt = clock.now - 100,
            ).copy(path = "/books/An author/A title"),
        )

        val result = catalog.importBook(
            CatalogImportRequest(
                libraryId = "lib-a",
                folderId = "folder-a",
                metadata = CatalogImportMetadata(title = "A title", author = "AnAuthor"),
                files = listOf(
                    CatalogImportFile(
                        fileName = "book.epub",
                        mimeType = "application/epub+zip",
                        withStream = { block ->
                            block(object : CatalogFileStream {
                                override val contentLength = 1L
                                override val channel = ByteReadChannel(byteArrayOf(1))
                            })
                        },
                    ),
                ),
            ),
        )

        assertEquals("existing", (result as CatalogImportResult.Uploaded).destinationItemId)
    }

    @Test fun `importBook reconciles ABS folder when source title has an audio tag suffix`() = runTest {
        libraryApi.libraryItems["lib-a"] = listOf(
            item(
                "existing",
                title = "A title",
                author = "An author",
                addedAt = clock.now - 100,
            ).copy(path = "/books/An author/A title"),
        )

        val result = catalog.importBook(
            CatalogImportRequest(
                libraryId = "lib-a",
                folderId = "folder-a",
                metadata = CatalogImportMetadata(
                    title = "A title :An author :radio",
                    author = "An author",
                ),
                files = listOf(
                    CatalogImportFile(
                        fileName = "book.mp3",
                        mimeType = "audio/mpeg",
                        withStream = { block ->
                            block(object : CatalogFileStream {
                                override val contentLength = 1L
                                override val channel = ByteReadChannel(byteArrayOf(1))
                            })
                        },
                    ),
                ),
            ),
        )

        assertEquals("existing", (result as CatalogImportResult.Uploaded).destinationItemId)
    }

    @Test fun `importBook reconciles clean ABS title folder against tagged source title`() = runTest {
        libraryApi.libraryItems["lib-a"] = listOf(
            item("existing", title = "Clean title", author = "An author")
                .copy(path = "/books/An author/Clean title"),
        )

        val result = catalog.importBook(
            CatalogImportRequest(
                libraryId = "lib-a",
                folderId = "folder-a",
                metadata = CatalogImportMetadata(
                    title = "Clean title :embedded :radio",
                    author = "An author",
                ),
                files = listOf(
                    CatalogImportFile(
                        fileName = "book.mp3",
                        mimeType = "audio/mpeg",
                        withStream = { block ->
                            block(object : CatalogFileStream {
                                override val contentLength = 1L
                                override val channel = ByteReadChannel(byteArrayOf(1))
                            })
                        },
                    ),
                ),
            ),
        )

        assertEquals("existing", (result as CatalogImportResult.Uploaded).destinationItemId)
    }

    @Test fun `importBook reconciles and enriches the created item`() = runTest {
        // Item appears immediately with the correct title/author so doesDestinationItemExist
        // resolves it on the first poll — no recovery scanLibrary fires.
        libraryApi.libraryItems["lib-a"] = listOf(
            item("created", title = "A title", author = "An author", addedAt = clock.now + 1),
        )
        libraryApi.singleItems["created"] = item("created", title = "A title", author = "An author").copy(
            description = "scanner summary",
            publishedYear = "2020",
        )
        val phases = mutableListOf<CatalogImportPhase>()

        val result = catalog.importBook(
            CatalogImportRequest(
                libraryId = "lib-a",
                folderId = "folder-a",
                metadata = CatalogImportMetadata(
                    title = "A title",
                    author = "An author",
                    description = "A description",
                    publishedYear = "1984",
                    series = "A series",
                    seriesSequence = "2",
                    coverUrl = "https://example.com/cover.jpg",
                ),
                files = listOf(
                    CatalogImportFile(
                        fileName = "book.epub",
                        mimeType = "application/epub+zip",
                        withStream = { block ->
                            block(object : CatalogFileStream {
                                override val contentLength = 1L
                                override val channel = ByteReadChannel(byteArrayOf(1))
                            })
                        },
                    ),
                ),
                chapters = listOf(CatalogImportChapter(0, 0.0, 12.5, "Chapter one")),
                readingProgress = 0.25f,
                ebookLocation = "epubcfi(/6/4)",
                onProgress = { phases += it.phase },
            ),
        )

        val uploaded = result as CatalogImportResult.Uploaded
        assertEquals("created", uploaded.destinationItemId)
        // No immediate post-upload scan: ABS's internal async scan (triggered by /api/upload)
        // creates the item; a concurrent explicit scan races and creates a duplicate. The
        // reconciliation loop handles recovery scans (every RESCAN_INTERVAL_ATTEMPTS) if the
        // item doesn't appear promptly — here it appears on the first poll so none fire.
        assertFalse(libraryApi.scanLibraryCalled)
        assertEquals("A series", libraryApi.lastMetadataUpdate?.series?.single()?.name)
        assertEquals("A description", libraryApi.lastMetadataUpdate?.description)
        assertEquals("1984", libraryApi.lastMetadataUpdate?.publishedYear)
        assertEquals(2, libraryApi.metadataUpdateCount)
        assertEquals("created", libraryApi.lastChaptersItemId)
        assertEquals("Chapter one", libraryApi.lastChapters?.single()?.title)
        assertEquals("created", libraryApi.lastCoverItemId)
        assertEquals("created", sessionApi.lastEbookPushItemId)
        assertEquals(0.25f, sessionApi.lastEbookPushPayload?.ebookProgress)
        assertTrue(phases.contains(CatalogImportPhase.Uploaded))
        assertTrue(phases.contains(CatalogImportPhase.Reconciling))
        assertTrue(phases.contains(CatalogImportPhase.Finalizing))
    }

    @Test fun `importBook triggers recovery scanLibrary when item does not appear within RESCAN_INTERVAL_ATTEMPTS polls`() = runTest {
        // Simulate the item not appearing until after RESCAN_INTERVAL_ATTEMPTS (5) polls.
        // The reconciliation loop must call scanLibrary at that point, not before.
        libraryApi.libraryItems["lib-a"] = listOf(
            item("created", title = "A title", author = "An author", addedAt = clock.now + 1),
        )
        libraryApi.delayItemsUntilPoll = AbsCatalog.RESCAN_INTERVAL_ATTEMPTS

        catalog.importBook(
            CatalogImportRequest(
                libraryId = "lib-a",
                folderId = "folder-a",
                metadata = CatalogImportMetadata(title = "A title", author = "An author"),
                files = listOf(
                    CatalogImportFile(
                        fileName = "book.epub",
                        mimeType = "application/epub+zip",
                        withStream = { block ->
                            block(object : CatalogFileStream {
                                override val contentLength = 1L
                                override val channel = ByteReadChannel(byteArrayOf(1))
                            })
                        },
                    ),
                ),
            ),
        )

        // The scan fires from the reconciliation loop at attempt RESCAN_INTERVAL_ATTEMPTS, not
        // immediately after upload. Any immediate post-upload scan would be a sign that the
        // duplicate-item race (ABS internal scan + explicit scan both creating items) is back.
        assertTrue(libraryApi.scanLibraryCalled)
        assertTrue(libraryApi.getRecentlyAddedCallCount >= AbsCatalog.RESCAN_INTERVAL_ATTEMPTS)
    }

    @Test fun `importBook resolves to matching item when concurrent upload also added a newer item`() = runTest {
        // Two items both newer than uploadStartMs (concurrent uploads). The timestamp branch is
        // ambiguous — it is skipped when multiple new items exist. The reconciler falls back to
        // doesDestinationItemExist, which uses title/author to find the correct item.
        libraryApi.libraryItems["lib-a"] = listOf(
            item("other", title = "Other Book", author = "Other Author", addedAt = clock.now + 2),
            item("created", title = "A title", author = "An author", addedAt = clock.now + 1),
        )

        val result = catalog.importBook(
            CatalogImportRequest(
                libraryId = "lib-a",
                folderId = "folder-a",
                metadata = CatalogImportMetadata(title = "A title", author = "An author"),
                files = listOf(
                    CatalogImportFile(
                        fileName = "book.epub",
                        mimeType = "application/epub+zip",
                        withStream = { block ->
                            block(object : CatalogFileStream {
                                override val contentLength = 1L
                                override val channel = ByteReadChannel(byteArrayOf(1))
                            })
                        },
                    ),
                ),
            ),
        )

        assertEquals("created", (result as CatalogImportResult.Uploaded).destinationItemId)
    }

    @Test fun `importBook waits for metadata indexing when concurrent upload's item is also present`() = runTest {
        // Two items present from concurrent uploads, neither with indexed metadata yet.
        // Reconciliation must keep polling until ABS indexes "created" with the correct
        // title/author — at that point doesDestinationItemExist resolves it without any
        // timestamp guessing.
        libraryApi.libraryItems["lib-a"] = listOf(
            item("other", title = "Scanner title B", author = "Scanner author B", addedAt = clock.now + 2),
            item("created", title = "Scanner title A", author = "Scanner author A", addedAt = clock.now + 1),
        )
        // One poll later ABS has indexed "created" with the correct title/author.
        libraryApi.delayItemsUntilPoll = 1
        libraryApi.libraryItemsAfterDelay["lib-a"] = listOf(
            item("other", title = "Scanner title B", author = "Scanner author B", addedAt = clock.now + 2),
            item("created", title = "A title", author = "An author", addedAt = clock.now + 1),
        )

        val result = catalog.importBook(
            CatalogImportRequest(
                libraryId = "lib-a",
                folderId = "folder-a",
                metadata = CatalogImportMetadata(title = "A title", author = "An author"),
                files = listOf(
                    CatalogImportFile(
                        fileName = "book.epub",
                        mimeType = "application/epub+zip",
                        withStream = { block ->
                            block(object : CatalogFileStream {
                                override val contentLength = 1L
                                override val channel = ByteReadChannel(byteArrayOf(1))
                            })
                        },
                    ),
                ),
            ),
        )

        assertEquals("created", (result as CatalogImportResult.Uploaded).destinationItemId)
    }

    @Test fun `importBook skips an item claimed by another upload and resolves its own`() = runTest {
        // Simulate concurrent uploads: "other" matches by title/author for the sibling and is
        // already claimed, so claimDestinationItem returns false for it. "created" is unclaimed
        // and should be returned for this upload.
        libraryApi.libraryItems["lib-a"] = listOf(
            item("other", title = "Other Book", author = "Other Author", addedAt = clock.now + 2),
            item("created", title = "A title", author = "An author", addedAt = clock.now + 1),
        )
        val alreadyClaimed = mutableSetOf("other")

        val result = catalog.importBook(
            CatalogImportRequest(
                libraryId = "lib-a",
                folderId = "folder-a",
                metadata = CatalogImportMetadata(title = "A title", author = "An author"),
                files = listOf(
                    CatalogImportFile(
                        fileName = "book.epub",
                        mimeType = "application/epub+zip",
                        withStream = { block ->
                            block(object : CatalogFileStream {
                                override val contentLength = 1L
                                override val channel = ByteReadChannel(byteArrayOf(1))
                            })
                        },
                    ),
                ),
                claimDestinationItem = { id -> alreadyClaimed.add(id) },
            ),
        )

        assertEquals("created", (result as CatalogImportResult.Uploaded).destinationItemId)
    }

    @Test fun `importBook preserves ebook progress when no CFI is available`() = runTest {
        libraryApi.searchResults["A title"] = listOf(item("created", title = "A title", author = "An author"))

        val result = catalog.importBook(
            CatalogImportRequest(
                libraryId = "lib-a",
                folderId = "folder-a",
                metadata = CatalogImportMetadata(title = "A title", author = "An author"),
                files = listOf(
                    CatalogImportFile(
                        fileName = "book.epub",
                        mimeType = "application/epub+zip",
                        withStream = { block ->
                            block(object : CatalogFileStream {
                                override val contentLength = 1L
                                override val channel = ByteReadChannel(byteArrayOf(1))
                            })
                        },
                    ),
                ),
                readingProgress = 0.25f,
                ebookLocation = "",
            ),
        )

        assertTrue(result is CatalogImportResult.Uploaded)
        assertEquals("", sessionApi.lastEbookPushPayload?.ebookLocation)
        assertEquals(0.25f, sessionApi.lastEbookPushPayload?.ebookProgress)
    }

    @Test fun `importBook sends isFinished=true when audiobook progress is 100%`() = runTest {
        libraryApi.libraryItems["lib-a"] = listOf(
            item("created", title = "A title", author = "An author", addedAt = clock.now + 1),
        )

        catalog.importBook(
            CatalogImportRequest(
                libraryId = "lib-a",
                folderId = "folder-a",
                metadata = CatalogImportMetadata(title = "A title", author = "An author"),
                files = listOf(
                    CatalogImportFile(
                        fileName = "book.mp3",
                        mimeType = "audio/mpeg",
                        withStream = { block ->
                            block(object : CatalogFileStream {
                                override val contentLength = 1L
                                override val channel = ByteReadChannel(byteArrayOf(1))
                            })
                        },
                    ),
                ),
                audioDurationSec = 3600.0,
                readingProgress = 1.0f,
            ),
        )

        assertEquals(3600.0, sessionApi.lastAudiobookPushPayload!!.currentTime, 0.0)
        assertEquals(true, sessionApi.lastAudiobookPushPayload!!.isFinished)
    }

    @Test fun `importBook does not send isFinished for partial audiobook progress`() = runTest {
        libraryApi.libraryItems["lib-a"] = listOf(
            item("created", title = "A title", author = "An author", addedAt = clock.now + 1),
        )

        catalog.importBook(
            CatalogImportRequest(
                libraryId = "lib-a",
                folderId = "folder-a",
                metadata = CatalogImportMetadata(title = "A title", author = "An author"),
                files = listOf(
                    CatalogImportFile(
                        fileName = "book.mp3",
                        mimeType = "audio/mpeg",
                        withStream = { block ->
                            block(object : CatalogFileStream {
                                override val contentLength = 1L
                                override val channel = ByteReadChannel(byteArrayOf(1))
                            })
                        },
                    ),
                ),
                audioDurationSec = 3600.0,
                readingProgress = 0.42f,
            ),
        )

        assertEquals(0.42f * 3600.0, sessionApi.lastAudiobookPushPayload!!.currentTime, 1.0)
        assertNull(sessionApi.lastAudiobookPushPayload!!.isFinished)
    }

    // region Catalog — mandatory core

    @Test fun `listRoots maps libraries to CatalogRoot`() = runTest {
        libraryApi.libraries = listOf(
            NetworkLibrary(
                id = "lib-a",
                name = "Ebooks",
                mediaType = "book",
                audiobooksOnly = false,
                folders = listOf(NetworkLibraryFolder(id = "folder-a", fullPath = "/books")),
            ),
            NetworkLibrary(id = "lib-b", name = "Casts", mediaType = "podcast", audiobooksOnly = false),
        )

        val roots = catalog.listRoots()

        assertEquals(2, roots.size)
        assertEquals("lib-a", roots[0].id)
        assertEquals("Ebooks", roots[0].name)
        assertEquals("book", roots[0].mediaType)
        assertEquals(false, roots[0].isUnsupported)
        assertEquals("folder-a", roots[0].importFolderId)
        // Podcast media flagged as unsupported so UI can hide the tab.
        assertEquals(true, roots[1].isUnsupported)
    }

    @Test fun `browse sorts by title and pages results`() = runTest {
        libraryApi.libraryItems["lib-a"] = listOf(
            item(id = "3", title = "Charlie"),
            item(id = "1", title = "Alpha"),
            item(id = "2", title = "Bravo"),
            item(id = "4", title = "Delta"),
        )

        val page0 = catalog.browse(rootId = "lib-a", sort = SortKey.TITLE, page = 0, pageSize = 2)
        val page1 = catalog.browse(rootId = "lib-a", sort = SortKey.TITLE, page = 1, pageSize = 2)
        val page2 = catalog.browse(rootId = "lib-a", sort = SortKey.TITLE, page = 2, pageSize = 2)

        assertEquals(listOf("Alpha", "Bravo"), page0.map { it.title })
        assertEquals(listOf("Charlie", "Delta"), page1.map { it.title })
        assertTrue(page2.isEmpty())
    }

    @Test fun `browse with RECENTLY_OPENED refuses instead of silently sorting by title`() = runTest {
        libraryApi.libraryItems["lib-a"] = listOf(item(id = "1", title = "Alpha"))

        try {
            catalog.browse(rootId = "lib-a", sort = SortKey.RECENTLY_OPENED)
            fail("expected CatalogException.UnsupportedFormat")
        } catch (_: CatalogException.UnsupportedFormat) {
        }
    }

    @Test fun `browse sorts by ADDED_AT descending`() = runTest {
        libraryApi.libraryItems["lib-a"] = listOf(
            item(id = "1", title = "Old", addedAt = 100L),
            item(id = "2", title = "New", addedAt = 300L),
            item(id = "3", title = "Mid", addedAt = 200L),
        )

        val items = catalog.browse(rootId = "lib-a", sort = SortKey.ADDED_AT)

        assertEquals(listOf("New", "Mid", "Old"), items.map { it.title })
    }

    @Test fun `search maps hits and pages client-side`() = runTest {
        libraryApi.searchResults["hobbit"] = listOf(
            item(id = "h1", title = "The Hobbit"),
            item(id = "h2", title = "The Hobbit — Illustrated"),
        )

        val page0 = catalog.search(rootId = "lib-a", query = "hobbit", page = 0, pageSize = 1)
        val page1 = catalog.search(rootId = "lib-a", query = "hobbit", page = 1, pageSize = 1)

        assertEquals(1, page0.size)
        assertEquals("The Hobbit", page0.single().title)
        assertEquals("The Hobbit — Illustrated", page1.single().title)
        // ABS's limit param must be at least pageSize per call.
        assertTrue(libraryApi.lastSearchLimit >= 1)
    }

    @Test fun `getItem returns null on missing item`() = runTest {
        val item = catalog.getItem(itemId = "missing")

        assertNull(item)
    }

    @Test fun `getItem maps to CatalogItem with cover URL`() = runTest {
        libraryApi.singleItems["it-1"] = item(id = "it-1", title = "Item 1")

        val result = catalog.getItem(itemId = "it-1")

        assertNotNull(result)
        assertEquals("it-1", result!!.id)
        assertEquals("Item 1", result.title)
        assertEquals("https://abs.example.com/api/items/it-1/cover?t=555", result.coverUrl)
    }

    @Test fun `fetchFile Epub returns Stream with auth headers`() = runTest {
        libraryApi.ebookInos["it-1"] = "ino-x"

        val handle = catalog.fetchFile(itemId = "it-1", format = BookFormat.Epub) as CatalogFileHandle.Stream

        assertEquals("https://abs.example.com/api/items/it-1/ebook/ino-x", handle.url)
        assertEquals("Bearer T", handle.headers["Authorization"])
        assertEquals(BookFormat.Epub, handle.format)
    }

    @Test fun `fetchFile Audiobook rejects with CatalogException UnsupportedFormat`() = runTest {
        try {
            catalog.fetchFile(itemId = "it-1", format = BookFormat.Audiobook)
            fail("expected CatalogException.UnsupportedFormat")
        } catch (_: CatalogException.UnsupportedFormat) {
        }
    }

    @Test fun `fetchFile Unsupported rejects with CatalogException UnsupportedFormat`() = runTest {
        try {
            catalog.fetchFile(itemId = "it-1", format = BookFormat.Unsupported)
            fail("expected CatalogException.UnsupportedFormat")
        } catch (_: CatalogException.UnsupportedFormat) {
        }
    }

    @Test fun `connectivityCheck reports reachable when serverInfo returns version`() = runTest {
        serverInfoApi.serverVersion = "2.19.0"
        clock.now = 1000
        // getServerInfo advances the clock so the AbsCatalog latency read differs.
        serverInfoApi.onCall = { clock.now = 1042 }

        val health = catalog.connectivityCheck()

        assertEquals(true, health.isReachable)
        assertEquals("2.19.0", health.serverVersion)
        assertEquals(42L, health.latencyMs)
    }

    @Test fun `connectivityCheck reports unreachable when serverInfo returns null`() = runTest {
        serverInfoApi.serverVersion = null

        val health = catalog.connectivityCheck()

        assertEquals(false, health.isReachable)
        assertNull(health.serverVersion)
    }

    // endregion

    // region SeriesCapability

    @Test fun `listSeries maps series responses`() = runTest {
        libraryApi.seriesByLibrary["lib-a"] = listOf(
            NetworkSeries(
                id = "s1",
                libraryId = "lib-a",
                name = "Foundation",
                items = listOf(seriesItem("f1", "Foundation Book 1")),
            ),
        )

        val result = catalog.listSeries(rootId = "lib-a")

        assertEquals(1, result.size)
        assertEquals("s1", result.single().id)
        assertEquals("lib-a", result.single().rootId)
        assertEquals("Foundation", result.single().name)
        assertEquals(1, result.single().bookCount)
        // Cover URL falls through the first book.
        assertTrue(result.single().coverUrl!!.contains("/api/items/f1/cover"))
    }

    @Test fun `listItemsInSeries returns empty when series id not found`() = runTest {
        libraryApi.seriesByLibrary["lib-a"] = listOf(
            NetworkSeries(id = "s1", libraryId = "lib-a", name = "F", items = emptyList()),
        )

        val items = catalog.listItemsInSeries(rootId = "lib-a", seriesId = "unknown")

        assertTrue(items.isEmpty())
    }

    @Test fun `listItemsInSeries maps books of matching series`() = runTest {
        libraryApi.seriesByLibrary["lib-a"] = listOf(
            NetworkSeries(
                id = "s1",
                libraryId = "lib-a",
                name = "Foundation",
                items = listOf(seriesItem("f1", "Foundation Book 1"), seriesItem("f2", "Foundation Book 2")),
            ),
        )

        val items = catalog.listItemsInSeries(rootId = "lib-a", seriesId = "s1")

        assertEquals(listOf("f1", "f2"), items.map { it.id })
        assertEquals(listOf("Foundation Book 1", "Foundation Book 2"), items.map { it.title })
    }

    @Test fun `listItemsInSeries carries hasAudio through so audio-only titles stay Audiobook`() = runTest {
        libraryApi.seriesByLibrary["lib-a"] = listOf(
            NetworkSeries(
                id = "s1",
                libraryId = "lib-a",
                name = "Foundation",
                items = listOf(
                    seriesItem("f1", "Ebook Book").copy(hasAudio = false, ebookFormat = EbookFormat.Epub),
                    seriesItem("f2", "Audiobook Book").copy(hasAudio = true, audioDurationSec = 3600.0, ebookFormat = EbookFormat.Unsupported),
                ),
            ),
        )

        val items = catalog.listItemsInSeries(rootId = "lib-a", seriesId = "s1")

        assertEquals(BookFormat.Epub, items[0].ebookFormat)
        assertEquals(BookFormat.Audiobook, items[1].ebookFormat)
        assertEquals(true, items[1].hasAudio)
        assertEquals(3600.0, items[1].audioDurationSec, 0.0)
    }

    // endregion

    // region CollectionsCapability

    @Test fun `listCollections maps ABS collections`() = runTest {
        libraryApi.collectionsByLibrary["lib-a"] = listOf(
            NetworkCollection(id = "c1", libraryId = "lib-a", name = "Favorites", items = emptyList()),
        )

        val result = catalog.listCollections(rootId = "lib-a")

        assertEquals("c1", result.single().id)
        assertEquals("Favorites", result.single().name)
    }

    @Test fun `createCollection returns the created collection`() = runTest {
        libraryApi.nextCreatedCollection = NetworkCollection(id = "c-new", libraryId = "lib-a", name = "New List", items = emptyList())

        val result = catalog.createCollection(rootId = "lib-a", name = "New List")

        assertEquals("c-new", result.id)
        assertEquals("New List", result.name)
    }

    @Test fun `addItemToCollection routes to the book endpoint`() = runTest {
        libraryApi.nextCreatedCollection = NetworkCollection("c1", "lib-a", "X", emptyList())

        catalog.addItemToCollection(collectionId = "c1", itemId = "book-1")

        assertEquals("c1" to "book-1", libraryApi.lastCollectionAdd)
    }

    @Test fun `removeItemFromCollection routes to the book endpoint`() = runTest {
        libraryApi.nextCreatedCollection = NetworkCollection("c1", "lib-a", "X", emptyList())

        catalog.removeItemFromCollection(collectionId = "c1", itemId = "book-1")

        assertEquals("c1" to "book-1", libraryApi.lastCollectionRemove)
    }

    // endregion

    // region PlaylistsCapability

    @Test fun `listPlaylists maps ABS playlists`() = runTest {
        libraryApi.playlistsByLibrary["lib-a"] = listOf(
            NetworkPlaylist(id = "p1", libraryId = "lib-a", name = "Queue", items = emptyList(), bookIds = emptySet()),
        )

        val result = catalog.listPlaylists(rootId = "lib-a")

        assertEquals("p1", result.single().id)
        assertEquals("Queue", result.single().name)
    }

    @Test fun `createPlaylist returns the created playlist`() = runTest {
        libraryApi.nextCreatedPlaylist = NetworkPlaylist("p-new", "lib-a", "New Q", emptyList(), emptySet())

        val result = catalog.createPlaylist(rootId = "lib-a", name = "New Q")

        assertEquals("p-new", result.id)
    }

    @Test fun `addItemToPlaylist routes to the book endpoint`() = runTest {
        libraryApi.nextCreatedPlaylist = NetworkPlaylist("p1", "lib-a", "Q", emptyList(), emptySet())

        catalog.addItemToPlaylist(playlistId = "p1", itemId = "book-1")

        assertEquals("p1" to "book-1", libraryApi.lastPlaylistAdd)
    }

    // endregion

    // region ProgressPeerCapability

    @Test fun `pushEbookProgress with isFinished=null leaves the audio dimension untouched (routine save)`() = runTest {
        // Regression: forwarding `false` here would zero ABS's audio currentTime+progress per
        // NetworkEbookProgressPayload's contract, clobbering audiobook progress on every ordinary
        // reader position save. Only mark-finished / mark-unread callers pass a non-null value.
        catalog.pushEbookProgress(
            itemId = "it-1",
            location = "epubcfi(/6/4)",
            progress = 0.5f,
            isFinished = null,
            lastUpdateEpochMs = 42L,
        )

        assertEquals("it-1", sessionApi.lastEbookPushItemId)
        val payload = sessionApi.lastEbookPushPayload!!
        assertEquals("epubcfi(/6/4)", payload.ebookLocation)
        assertEquals(0.5f, payload.ebookProgress)
        assertEquals(null, payload.isFinished)
    }

    @Test fun `pushEbookProgress with isFinished=true forwards the flag (mark-finished)`() = runTest {
        catalog.pushEbookProgress(
            itemId = "it-1",
            location = "epubcfi(/6/4)",
            progress = 1.0f,
            isFinished = true,
            lastUpdateEpochMs = 42L,
        )
        assertEquals(true, sessionApi.lastEbookPushPayload!!.isFinished)
    }

    @Test fun `pushAudiobookProgress sends currentTime and duration`() = runTest {
        catalog.pushAudiobookProgress(
            itemId = "it-1",
            currentTimeSec = 120.5,
            durationSec = 3600.0,
            isFinished = null,
            lastUpdateEpochMs = 42L,
        )

        assertEquals("it-1", sessionApi.lastAudiobookPushItemId)
        assertEquals(120.5, sessionApi.lastAudiobookPushPayload!!.currentTime, 0.0)
        assertEquals(3600.0, sessionApi.lastAudiobookPushPayload!!.duration, 0.0)
    }

    @Test fun `pullProgress returns a reachable-empty CatalogProgress (lastUpdate=0) rather than null`() = runTest {
        // Distinct from a null on network failure — an empty record means "reachable but never
        // touched", so the caller can push the first position on this device instead of skipping.
        sessionApi.progressForItem["it-1"] = NetworkServerProgress(ebookLocation = "", lastUpdate = 0L)

        val result = catalog.pullProgress(itemId = "it-1")!!

        assertEquals(0L, result.lastUpdate)
        assertEquals(null, result.ebookLocation) // toCatalogProgress collapses "" → null
    }

    @Test fun `pullProgress maps a non-empty ABS record`() = runTest {
        sessionApi.progressForItem["it-1"] = NetworkServerProgress(
            ebookLocation = "epubcfi(/6/4)",
            ebookProgress = 0.5f,
            currentTime = 30.0,
            duration = 300.0,
            lastUpdate = 999L,
        )

        val result = catalog.pullProgress(itemId = "it-1")!!

        assertEquals("epubcfi(/6/4)", result.ebookLocation)
        assertEquals(0.5f, result.ebookProgress)
        assertEquals(30.0, result.audioCurrentTime, 0.0)
        assertEquals(300.0, result.audioDuration, 0.0)
        assertEquals(999L, result.lastUpdate)
        assertEquals(false, result.isFinished)
    }

    @Test fun `pullProgress derives isFinished when ebook progress hits 1`() = runTest {
        sessionApi.progressForItem["it-1"] = NetworkServerProgress(
            ebookLocation = "epubcfi(/6/8)",
            ebookProgress = 1f,
            lastUpdate = 999L,
        )

        val result = catalog.pullProgress(itemId = "it-1")!!

        assertEquals(true, result.isFinished)
    }

    @Test fun `pullProgress derives isFinished when audio currentTime reaches duration`() = runTest {
        sessionApi.progressForItem["it-1"] = NetworkServerProgress(
            ebookLocation = "",
            currentTime = 3600.0,
            duration = 3600.0,
            lastUpdate = 999L,
        )

        val result = catalog.pullProgress(itemId = "it-1")!!

        assertEquals(true, result.isFinished)
    }

    @Test fun `pullAllProgress converts NetworkUserMediaProgress map to list`() = runTest {
        libraryApi.userProgress = mapOf(
            "a" to NetworkUserMediaProgress(ebookProgress = 0.25f, lastUpdate = 100L, finishedAt = null),
            "b" to NetworkUserMediaProgress(ebookProgress = 1f, lastUpdate = 200L, finishedAt = 300L),
        )

        val result = catalog.pullAllProgress().associateBy { it.itemId }

        assertEquals(0.25f, result["a"]!!.ebookProgress)
        assertEquals(false, result["a"]!!.isFinished)
        assertEquals(true, result["b"]!!.isFinished)
    }

    @Test fun `pullAllProgress carries audio position and isFinished instead of hardcoding zeros`() = runTest {
        // Regression for the "progress bar jumps back and forth" bug: the bulk pull used to
        // hardcode audioCurrentTime/audioDuration to 0.0, so the library refresh could never
        // derive the same unified fraction as the per-item pullProgress and the two writers
        // ping-ponged library_items.readingProgress.
        libraryApi.userProgress = mapOf(
            "a" to NetworkUserMediaProgress(
                ebookProgress = null, lastUpdate = 100L,
                currentTime = 59.0, duration = 100.0, isFinished = false,
            ),
            "b" to NetworkUserMediaProgress(ebookProgress = null, lastUpdate = 200L, isFinished = true),
        )

        val result = catalog.pullAllProgress().associateBy { it.itemId }

        assertEquals(59.0, result["a"]!!.audioCurrentTime, 0.0001)
        assertEquals(100.0, result["a"]!!.audioDuration, 0.0001)
        assertEquals(false, result["a"]!!.isFinished)
        assertEquals(true, result["b"]!!.isFinished)
    }

    // endregion

    // region ReadingSessionsCapability

    @Test fun `openSession returns handle keyed on session id and current clock`() = runTest {
        playbackApi.nextSession = NetworkPlaybackSession(
            sessionId = "sess-1",
            tracks = emptyList(),
            chapters = emptyList(),
            currentTimeSec = 0.0,
            durationSec = 0.0,
        )
        clock.now = 9_000L

        val handle = catalog.openSession(itemId = "it-1", deviceLabel = "Pixel")

        assertEquals("sess-1", handle.sessionId)
        assertEquals("it-1", handle.itemId)
        assertEquals(9_000L, handle.startedAtEpochMs)
        assertEquals("device-A", playbackApi.lastDeviceId)
    }

    @Test fun `openSession throws Unknown when ABS returns null sessionId`() = runTest {
        playbackApi.nextSession = NetworkPlaybackSession(
            sessionId = null,
            tracks = emptyList(),
            chapters = emptyList(),
            currentTimeSec = 0.0,
            durationSec = 0.0,
        )

        try {
            catalog.openSession(itemId = "it-1", deviceLabel = "Pixel")
            fail("expected CatalogException.Unknown")
        } catch (_: CatalogException.Unknown) {
        }
    }

    @Test fun `syncSession forwards sessionId currentTime and timeListened`() = runTest {
        val handle = com.riffle.core.catalog.CatalogSessionHandle("sess-1", "it-1", 0L)

        catalog.syncSession(handle, currentTimeSec = 120.0, timeListenedSec = 60.0)

        assertEquals("sess-1", playbackApi.lastSyncSessionId)
        assertEquals(120.0, playbackApi.lastSyncCurrent!!, 0.0)
        assertEquals(60.0, playbackApi.lastSyncListened!!, 0.0)
    }

    @Test fun `closeSession forwards sessionId currentTime and timeListened`() = runTest {
        val handle = com.riffle.core.catalog.CatalogSessionHandle("sess-1", "it-1", 0L)

        catalog.closeSession(handle, currentTimeSec = 200.0, timeListenedSec = 90.0)

        assertEquals("sess-1", playbackApi.lastCloseSessionId)
        assertEquals(200.0, playbackApi.lastCloseCurrent!!, 0.0)
    }

    // endregion

    // region StatsCapability

    @Test fun `getStats aggregates listening time and item counts`() = runTest {
        serverInfoApi.stats = NetworkListeningStats(totalTimeSec = 7200.0)
        libraryApi.userProgress = mapOf(
            "a" to NetworkUserMediaProgress(0.5f, 0L, finishedAt = null),
            "b" to NetworkUserMediaProgress(1f, 0L, finishedAt = 999L),
            "c" to NetworkUserMediaProgress(0.1f, 0L, finishedAt = null),
        )

        val stats = catalog.getStats()

        assertEquals(7200.0, stats.totalSecondsListened, 0.0)
        assertEquals(2, stats.totalItemsInProgress)
        assertEquals(1, stats.totalItemsFinished)
    }

    // endregion

    // region AudiobookMediaCapability

    @Test fun `getTracks builds startOffsets and content URLs from durations`() = runTest {
        libraryApi.audiobookTracks["it-1"] = listOf(
            NetworkAbsAudioTrack(ino = "a", index = 0, durationSec = 60.0),
            NetworkAbsAudioTrack(ino = "b", index = 1, durationSec = 120.0),
            NetworkAbsAudioTrack(ino = "c", index = 2, durationSec = 30.0),
        )

        val tracks = catalog.getTracks(itemId = "it-1")

        assertEquals(0.0, tracks[0].startOffsetSec, 0.0)
        assertEquals(60.0, tracks[1].startOffsetSec, 0.0)
        assertEquals(180.0, tracks[2].startOffsetSec, 0.0)
        assertEquals("https://abs.example.com/api/items/it-1/file/a", tracks[0].contentUrl)
    }

    @Test fun `getFingerprint maps ABS AudiobookFingerprint to catalog type`() = runTest {
        libraryApi.fingerprints["it-1"] = AudiobookFingerprint(
            fileSizeBytes = 12345L,
            durationSec = 3600.0,
            trackDurationsSec = listOf(1800.0, 1800.0),
        )

        val fp = catalog.getFingerprint(itemId = "it-1")

        assertEquals("it-1", fp!!.itemId)
        assertEquals(3600.0, fp.totalDurationSec, 0.0)
        assertEquals(listOf(1800.0, 1800.0), fp.trackDurations)
    }

    @Test fun `getFingerprint returns null when item has no audiobook (definitive NO_AUDIOBOOK verdict)`() = runTest {
        libraryApi.fingerprints["it-1"] = null

        assertEquals(null, catalog.getFingerprint(itemId = "it-1"))
    }

    @Test fun `buildStreamUrl mirrors AbsAudioUrl track pattern and bakes the auth token in`() {
        val url = catalog.buildStreamUrl(itemId = "it-1", trackIno = "a")

        assertEquals("https://abs.example.com/api/items/it-1/file/a?token=T", url)
    }

    // endregion

    // region error propagation

    @Test fun `network Auth surfaces as CatalogException Auth`() = runTest {
        libraryApi.librariesResult = NetworkResult.Auth

        try {
            catalog.listRoots()
            fail("expected CatalogException.Auth")
        } catch (_: CatalogException.Auth) {
        }
    }

    @Test fun `network ServerError surfaces the code`() = runTest {
        libraryApi.librariesResult = NetworkResult.ServerError(code = 503, errorMessage = "down")

        try {
            catalog.listRoots()
            fail("expected CatalogException.ServerError")
        } catch (e: CatalogException.ServerError) {
            assertEquals(503, e.code)
        }
    }

    @Test fun `network Offline surfaces as CatalogException Offline`() = runTest {
        libraryApi.librariesResult = NetworkResult.Offline(java.io.IOException("boom"))

        try {
            catalog.listRoots()
            fail("expected CatalogException.Offline")
        } catch (_: CatalogException.Offline) {
        }
    }

    // endregion

    // region helpers

    private fun item(
        id: String,
        title: String = "Untitled",
        author: String = "Anon",
        addedAt: Long? = null,
        hasAudio: Boolean = false,
        format: EbookFormat = EbookFormat.Epub,
        path: String? = null,
    ) = NetworkLibraryItem(
        id = id,
        libraryId = "lib-a",
        title = title,
        author = author,
        readingProgress = null,
        ebookFormat = format,
        ebookFileIno = "ino-$id",
        hasAudio = hasAudio,
        addedAt = addedAt,
        updatedAt = 555L,
        path = path,
    )

    private fun seriesItem(id: String, title: String) = NetworkSeriesItem(
        id = id,
        libraryId = "lib-a",
        title = title,
        author = "Anon",
        sequence = null,
        readingProgress = null,
        ebookFormat = EbookFormat.Epub,
        updatedAt = 555L,
    )

    // endregion
}

// region fakes

private class FakeAbsLibraryApi : AbsLibraryApi {
    var libraries: List<NetworkLibrary> = emptyList()
    var librariesResult: NetworkResult<List<NetworkLibrary>>? = null
    val libraryItems = mutableMapOf<String, List<NetworkLibraryItem>>()
    val searchResults = mutableMapOf<String, List<NetworkLibraryItem>>()
    var lastSearchLimit: Int = -1
    val singleItems = mutableMapOf<String, NetworkLibraryItem>()
    val ebookInos = mutableMapOf<String, String>()
    val seriesByLibrary = mutableMapOf<String, List<NetworkSeries>>()
    val collectionsByLibrary = mutableMapOf<String, List<NetworkCollection>>()
    val playlistsByLibrary = mutableMapOf<String, List<NetworkPlaylist>>()
    var nextCreatedCollection: NetworkCollection? = null
    var nextCreatedPlaylist: NetworkPlaylist? = null
    var lastCollectionAdd: Pair<String, String>? = null
    var lastCollectionRemove: Pair<String, String>? = null
    var lastPlaylistAdd: Pair<String, String>? = null
    var lastPlaylistRemove: Pair<String, String>? = null
    val audiobookTracks = mutableMapOf<String, List<NetworkAbsAudioTrack>>()
    val fingerprints = mutableMapOf<String, AudiobookFingerprint?>()
    var userProgress: Map<String, NetworkUserMediaProgress> = emptyMap()
    var lastUploadLibraryId: String? = null
    var lastUploadMetadata: NetworkUploadMetadata? = null
    var lastUploadFiles: List<NetworkUploadPart> = emptyList()
    var lastUploadBytes: List<List<Byte>> = emptyList()
    val uploadCalls = mutableListOf<List<String>>()
    var lastMetadataUpdate: NetworkAbsMetadataUpdate? = null
    var metadataUpdateCount: Int = 0
    var lastChaptersItemId: String? = null
    var lastChapters: List<NetworkAbsChapterUpdate>? = null
    var lastCoverItemId: String? = null
    var scanLibraryCalled: Boolean = false
    var scanLibraryCallCount: Int = 0
    var lastRecentlyAddedLimit: Int = -1
    var getRecentlyAddedCallCount: Int = 0
    // When set, getRecentlyAddedLibraryItems returns empty until this many calls have been made.
    var delayItemsUntilPoll: Int = 0
    // When set, getRecentlyAddedLibraryItems returns this map's items after delayItemsUntilPoll polls.
    val libraryItemsAfterDelay = mutableMapOf<String, List<NetworkLibraryItem>>()

    override suspend fun uploadBook(
        baseUrl: String,
        libraryId: String,
        metadata: NetworkUploadMetadata,
        files: List<NetworkUploadPart>,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> {
        lastUploadLibraryId = libraryId
        lastUploadMetadata = metadata
        lastUploadFiles = files
        lastUploadBytes = files.map { it.provider().readRemaining().readBytes().toList() }
        uploadCalls += files.map { it.fileName }
        return NetworkResult.Success(Unit)
    }

    override suspend fun updateItemMedia(
        baseUrl: String,
        itemId: String,
        metadata: NetworkAbsMetadataUpdate,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> {
        lastMetadataUpdate = metadata
        metadataUpdateCount++
        return NetworkResult.Success(Unit)
    }

    override suspend fun updateItemChapters(
        baseUrl: String,
        itemId: String,
        chapters: List<NetworkAbsChapterUpdate>,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> {
        lastChaptersItemId = itemId
        lastChapters = chapters
        return NetworkResult.Success(Unit)
    }

    override suspend fun uploadItemCoverFromUrl(
        baseUrl: String,
        itemId: String,
        url: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> {
        lastCoverItemId = itemId
        return NetworkResult.Success(Unit)
    }

    override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
        librariesResult ?: NetworkResult.Success(libraries)

    override suspend fun scanLibrary(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<Unit> {
        scanLibraryCalled = true
        scanLibraryCallCount++
        return NetworkResult.Success(Unit)
    }


    override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
        NetworkResult.Success(libraryItems[libraryId].orEmpty())

    override suspend fun getRecentlyAddedLibraryItems(
        baseUrl: String,
        libraryId: String,
        limit: Int,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkLibraryItem>> {
        lastRecentlyAddedLimit = limit
        val callIndex = getRecentlyAddedCallCount++
        val items = when {
            callIndex < delayItemsUntilPoll -> emptyList()
            libraryItemsAfterDelay.containsKey(libraryId) -> libraryItemsAfterDelay[libraryId].orEmpty()
            else -> libraryItems[libraryId].orEmpty()
        }
        return NetworkResult.Success(
            items.sortedByDescending { it.addedAt ?: Long.MIN_VALUE }.take(limit),
        )
    }

    override suspend fun searchLibrary(baseUrl: String, libraryId: String, query: String, limit: Int, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> {
        lastSearchLimit = limit
        return NetworkResult.Success(searchResults[query].orEmpty())
    }

    override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
        NetworkResult.Success(seriesByLibrary[libraryId].orEmpty())

    override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
        NetworkResult.Success(collectionsByLibrary[libraryId].orEmpty())

    override suspend fun createCollection(baseUrl: String, libraryId: String, name: String, initialBookId: String?, token: String, insecureAllowed: Boolean): NetworkResult<NetworkCollection?> =
        NetworkResult.Success(nextCreatedCollection)

    override suspend fun addBookToCollection(baseUrl: String, collectionId: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkCollection?> {
        lastCollectionAdd = collectionId to libraryItemId
        return NetworkResult.Success(nextCreatedCollection)
    }

    override suspend fun removeBookFromCollection(baseUrl: String, collectionId: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkCollection?> {
        lastCollectionRemove = collectionId to libraryItemId
        return NetworkResult.Success(nextCreatedCollection)
    }

    override suspend fun getPlaylists(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkPlaylist>> =
        NetworkResult.Success(playlistsByLibrary[libraryId].orEmpty())

    override suspend fun createPlaylist(baseUrl: String, libraryId: String, name: String, initialBookId: String?, token: String, insecureAllowed: Boolean): NetworkResult<NetworkPlaylist?> =
        NetworkResult.Success(nextCreatedPlaylist)

    override suspend fun addBookToPlaylist(baseUrl: String, playlistId: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkPlaylist?> {
        lastPlaylistAdd = playlistId to libraryItemId
        return NetworkResult.Success(nextCreatedPlaylist)
    }

    override suspend fun removeBookFromPlaylist(baseUrl: String, playlistId: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkPlaylist?> {
        lastPlaylistRemove = playlistId to libraryItemId
        return NetworkResult.Success(nextCreatedPlaylist)
    }

    override suspend fun getItemEbookFileIno(baseUrl: String, itemId: String, token: String, insecureAllowed: Boolean): NetworkResult<String> =
        NetworkResult.Success(ebookInos[itemId] ?: "unknown-ino")

    override suspend fun getItem(baseUrl: String, itemId: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkLibraryItem?> =
        NetworkResult.Success(singleItems[itemId])

    override suspend fun getAudiobookFingerprint(baseUrl: String, itemId: String, token: String, insecureAllowed: Boolean): NetworkResult<AudiobookFingerprint?> =
        NetworkResult.Success(fingerprints[itemId])

    override suspend fun getAudiobookTracks(baseUrl: String, itemId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkAbsAudioTrack>> =
        NetworkResult.Success(audiobookTracks[itemId].orEmpty())

    override suspend fun getUserProgress(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<Map<String, NetworkUserMediaProgress>> =
        NetworkResult.Success(userProgress)
}

private class FakeAbsPlaybackApi : AbsPlaybackApi {
    var nextSession: NetworkPlaybackSession = NetworkPlaybackSession(
        sessionId = "sess-default",
        tracks = emptyList<NetworkAudioTrack>(),
        chapters = emptyList(),
        currentTimeSec = 0.0,
        durationSec = 0.0,
    )
    var lastDeviceId: String? = null
    var lastSyncSessionId: String? = null
    var lastSyncCurrent: Double? = null
    var lastSyncListened: Double? = null
    var lastCloseSessionId: String? = null
    var lastCloseCurrent: Double? = null
    var lastCloseListened: Double? = null

    override suspend fun openPlaybackSession(baseUrl: String, libraryItemId: String, deviceId: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkPlaybackSession> {
        lastDeviceId = deviceId
        return NetworkResult.Success(nextSession)
    }

    override suspend fun syncPlaybackSession(baseUrl: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, token: String, insecureAllowed: Boolean): NetworkResult<Unit> {
        lastSyncSessionId = sessionId
        lastSyncCurrent = currentTimeSec
        lastSyncListened = timeListenedSec
        return NetworkResult.Success(Unit)
    }

    override suspend fun closePlaybackSession(baseUrl: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, token: String, insecureAllowed: Boolean): NetworkResult<Unit> {
        lastCloseSessionId = sessionId
        lastCloseCurrent = currentTimeSec
        lastCloseListened = timeListenedSec
        return NetworkResult.Success(Unit)
    }
}

private class FakeAbsSessionApi : AbsSessionApi {
    val progressForItem = mutableMapOf<String, NetworkServerProgress>()
    var lastEbookPushItemId: String? = null
    var lastEbookPushPayload: NetworkEbookProgressPayload? = null
    var lastAudiobookPushItemId: String? = null
    var lastAudiobookPushPayload: NetworkAudiobookProgressPayload? = null

    override suspend fun syncEbookProgress(baseUrl: String, libraryItemId: String, payload: NetworkEbookProgressPayload, token: String, insecureAllowed: Boolean): NetworkResult<Long> {
        lastEbookPushItemId = libraryItemId
        lastEbookPushPayload = payload
        return NetworkResult.Success(1L)
    }

    override suspend fun syncAudiobookProgress(baseUrl: String, libraryItemId: String, payload: NetworkAudiobookProgressPayload, token: String, insecureAllowed: Boolean): NetworkResult<Long> {
        lastAudiobookPushItemId = libraryItemId
        lastAudiobookPushPayload = payload
        return NetworkResult.Success(1L)
    }

    override suspend fun getProgress(baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkServerProgress> =
        NetworkResult.Success(progressForItem[libraryItemId] ?: NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
}

private class FakeAbsBookmarkApi : AbsBookmarkApi {
    override suspend fun createBookmark(baseUrl: String, itemId: String, timeSec: Int, title: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkAbsBookmark> =
        NetworkResult.Success(NetworkAbsBookmark(itemId, title, timeSec, 0L))

    override suspend fun updateBookmark(baseUrl: String, itemId: String, timeSec: Int, title: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkAbsBookmark> =
        NetworkResult.Success(NetworkAbsBookmark(itemId, title, timeSec, 0L))

    override suspend fun deleteBookmark(baseUrl: String, itemId: String, timeSec: Int, token: String, insecureAllowed: Boolean): NetworkResult<NetworkAbsBookmark> =
        NetworkResult.Success(NetworkAbsBookmark(itemId, "", timeSec, 0L))

    override suspend fun listBookmarks(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkAbsBookmark>> =
        NetworkResult.Success(emptyList())
}

private class FakeAbsServerInfoApi : AbsServerInfoApi {
    var serverVersion: String? = "1.0.0"
    var stats: NetworkListeningStats = NetworkListeningStats(0.0)
    var onCall: (() -> Unit)? = null

    override suspend fun getServerInfo(baseUrl: String, token: String, insecureAllowed: Boolean): String? {
        onCall?.invoke()
        return serverVersion
    }

    override suspend fun getCurrentUserId(baseUrl: String, token: String, insecureAllowed: Boolean): String? = "user-1"

    override suspend fun getListeningStats(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkListeningStats> =
        NetworkResult.Success(stats)
}

// endregion
