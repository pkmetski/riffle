package com.riffle.shared.reader

import com.riffle.core.domain.CbzDownloadResult.NetworkError
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import com.riffle.core.network.KomgaCbzApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Regression: before the #1048 fix, downloadCbz returned CbzDownloadResult.Success without
 * actually downloading anything. This was silent data-loss — the UI showed "downloaded" but
 * the file was never written, so offline reading would fail. Now it returns NetworkError to
 * surface the gap honestly until offline CBZ support is implemented on iOS.
 */
class IosCbzRepositoryTest {

    private val noopSourceRepository = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
        override suspend fun getActive(): Source? = null
        override suspend fun commit(
            pending: PendingSource,
            hiddenLibraryIds: Set<String>,
        ): CommitSourceResult = CommitSourceResult.Failure(UnsupportedOperationException())
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private val noopTokenStorage = object : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String? = null
        override suspend fun deleteToken(sourceId: String) {}
    }

    private val noopCbzApi = object : KomgaCbzApi {
        override suspend fun fetchCbzPageCount(
            baseUrl: String,
            bookId: String,
            token: String,
            insecureAllowed: Boolean,
        ): Int = 0
        override suspend fun fetchCbzPage(
            baseUrl: String,
            bookId: String,
            pageIndex: Int,
            maxWidth: Int?,
            token: String,
            insecureAllowed: Boolean,
        ): ByteArray = byteArrayOf()
    }

    private val noopPositionStore = object : ReadingPositionStore {
        override suspend fun save(sourceId: String, itemId: String, payload: String) {}
        override suspend fun load(sourceId: String, itemId: String): String? = null
        override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {}
        override suspend fun acceptServer(
            sourceId: String,
            itemId: String,
            payload: String,
            serverStamp: Long,
        ) {}
        override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) {}
    }

    private val stubDownloader = IosCbzDownloader(
        httpClient = HttpClient(MockEngine { error("unexpected HTTP call in downloadCbz test") }),
        sourceRepository = noopSourceRepository,
        tokenStorage = noopTokenStorage,
    )

    private val repo = IosCbzRepository(
        sourceRepository = noopSourceRepository,
        tokenStorage = noopTokenStorage,
        cbzApi = noopCbzApi,
        downloader = stubDownloader,
        positionStore = noopPositionStore,
    )

    private val fakeItem = LibraryItem(
        id = "item-1",
        libraryId = "lib-1",
        title = "Test CBZ Book",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Cbz,
        ebookFileIno = null,
        hasAudio = false,
        audioDurationSec = 0.0,
        description = null,
        seriesName = null,
        publishedYear = null,
        genres = emptyList(),
        publisher = null,
        language = null,
        lastOpenedAt = null,
        addedAt = null,
        isbn = null,
    )

    @Test
    fun downloadCbzReturnsNetworkErrorNotSuccess() = runTest {
        val result = repo.downloadCbz(fakeItem) { _, _ -> }
        assertIs<NetworkError>(
            result,
            "iOS downloadCbz must return NetworkError — returning Success would silently skip the actual download",
        )
    }
}
