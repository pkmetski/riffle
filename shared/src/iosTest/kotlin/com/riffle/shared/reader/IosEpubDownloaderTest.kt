package com.riffle.shared.reader

import com.riffle.core.common.FileStore
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import com.riffle.core.models.SourceUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression test for the missing parent-directory creation bug in IosEpubDownloader.
 *
 * The cache path is Documents/epub-cache/$sourceId/$itemId.epub. IosFileStore.resolve() only
 * creates Documents/epub-cache/ — it does not create the $sourceId/ subdirectory. Before the fix,
 * NSFileManager.createFileAtPath() was used directly and silently returned false because the
 * parent directory did not exist, so localPath() returned null and the reader showed
 * "Could not download book" for every book on every source.
 *
 * After the fix, IosItemFiles.writeBytes() creates the parent directory before writing, so
 * localPath() returns the path successfully.
 */
@OptIn(ExperimentalForeignApi::class)
class IosEpubDownloaderTest {

    private val roots = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private inner class TempFileStore : FileStore {
        private val root = NSTemporaryDirectory() + "epub_dl_test_" + NSUUID().UUIDString()

        init {
            roots += root
        }

        override fun resolve(namespace: String, relativePath: String): String {
            val base = "$root/$namespace"
            NSFileManager.defaultManager.createDirectoryAtPath(base, withIntermediateDirectories = true, attributes = null, error = null)
            return if (relativePath.isEmpty()) base else "$base/$relativePath"
        }
    }

    private val source = Source(
        id = "src-1",
        url = SourceUrl.parse("https://abs.example.test/")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "reader",
    )

    private val sourceRepository = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(listOf(source))
        override suspend fun getActive(): Source? = source
        override suspend fun getById(sourceId: String): Source? = source.takeIf { it.id == sourceId }
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(UnsupportedOperationException())
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private val tokenStorage = object : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String? = "tok"
        override suspend fun deleteToken(sourceId: String) {}
    }

    private val epubBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4)

    private val item = LibraryItem(
        id = "item-1",
        sourceId = "src-1",
        libraryId = "lib-1",
        title = "Test EPUB Book",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        ebookFileIno = "ino-1",
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

    private fun servingDownloader(fileStore: FileStore) = IosEpubDownloader(
        httpClient = HttpClient(
            MockEngine { respond(content = epubBytes, status = HttpStatusCode.OK, headers = headersOf("Content-Length", epubBytes.size.toString())) },
        ),
        sourceRepository = sourceRepository,
        tokenStorage = tokenStorage,
        fileStore = fileStore,
    )

    private fun failingDownloader(fileStore: FileStore) = IosEpubDownloader(
        httpClient = HttpClient(
            MockEngine { respond(content = ByteArray(0), status = HttpStatusCode.InternalServerError) },
        ),
        sourceRepository = sourceRepository,
        tokenStorage = tokenStorage,
        fileStore = fileStore,
    )

    @Test
    fun localPathCreatesTheSourceIdSubdirectoryAndReturnsTheCachePath() = runTest {
        val store = TempFileStore()
        val path = servingDownloader(store).localPath(item)

        assertNotNull(path, "localPath must return a non-null path when the download succeeds")
        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath(path),
            "the file must exist at the returned path",
        )
        assertTrue(
            path.endsWith("src-1/item-1.epub"),
            "path must include the sourceId/ subdirectory: was $path",
        )
    }

    @Test
    fun localPathReturnsNullWhenTheServerRespondsWithAnError() = runTest {
        val path = failingDownloader(TempFileStore()).localPath(item)
        assertNull(path, "a server-error response must yield null so the reader shows the error UI")
    }

    @Test
    fun localPathReturnsCacheHitWithoutANetworkRoundTrip() = runTest {
        val store = TempFileStore()
        val downloader = servingDownloader(store)

        val first = downloader.localPath(item)
        assertNotNull(first, "first call must download and cache")

        val failDownloader = failingDownloader(store)
        val second = failDownloader.localPath(item)
        assertNotNull(second, "second call must return the cached path without going to the network")
    }
}
