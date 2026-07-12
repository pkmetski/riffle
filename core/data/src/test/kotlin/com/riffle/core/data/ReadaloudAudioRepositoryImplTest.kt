package com.riffle.core.data

import com.riffle.core.network.NetworkResult

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.MediaOverlayClip
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.SourceUrl
import com.riffle.core.domain.StoredItemRef
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.StorytellerBundleProbeApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

class ReadaloudAudioRepositoryImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val track = ReadaloudTrack(listOf(MediaOverlayClip("c1.xhtml#s0", "audio/0.mp3", 0.0, 30.0)))

    private fun makeRepo(bundleFile: File?, parseResult: ReadaloudTrack?): Pair<ReadaloudAudioRepositoryImpl, () -> Int> {
        var parseCalls = 0
        val repo = object : ReadaloudAudioRepositoryImpl(
            downloader = AudiobookBundleDownloader(
                api = { _, _, _, _, _ -> throw UnsupportedOperationException() },
                targetFileProvider = { _, _ -> File("") },
                dispatchers = com.riffle.core.domain.DefaultDispatcherProvider,
            ),
            bundleProbe = StorytellerBundleProbeApi { _, _, _, _ ->
                NetworkResult.Offline(UnsupportedOperationException())
            },
            cacheStore = FakeStore(null),
            downloadsStore = FakeStore(bundleFile),
            sourceRepository = NoopServerRepository,
            tokenStorage = NoopTokenStorage,
            dispatchers = com.riffle.core.domain.DefaultDispatcherProvider,
        ) {
            override fun parseTrack(file: File): ReadaloudTrack? {
                parseCalls++
                return parseResult
            }
        }
        return repo to { parseCalls }
    }

    @Test
    fun `readTrack returns null when no bundle file`() = runTest {
        val (repo, _) = makeRepo(bundleFile = null, parseResult = track)
        assertNull(repo.readTrack("s", "i"))
    }

    @Test
    fun `readTrack returns null when parser returns null`() = runTest {
        val bundle = tmp.newFile("book.zip")
        val (repo, parseCalls) = makeRepo(bundleFile = bundle, parseResult = null)
        assertNull(repo.readTrack("s", "i"))
        assertEquals(1, parseCalls())
    }

    @Test
    fun `readTrack parses once and caches the result`() = runTest {
        val bundle = tmp.newFile("book.zip")
        val (repo, parseCalls) = makeRepo(bundleFile = bundle, parseResult = track)

        val first = repo.readTrack("s", "i")
        val second = repo.readTrack("s", "i")

        assertEquals(track, first)
        assertEquals(track, second)
        assertEquals("should parse exactly once", 1, parseCalls())
    }

    @Test
    fun `readTrack re-parses after bundle file is replaced`() = runTest {
        val bundle = tmp.newFile("book.zip")
        val (repo, parseCalls) = makeRepo(bundleFile = bundle, parseResult = track)

        repo.readTrack("s", "i")
        // Simulate a bundle re-download by touching the file (updates lastModified).
        bundle.setLastModified(bundle.lastModified() + 1_000)
        repo.readTrack("s", "i")

        assertEquals("should re-parse after bundle replaced", 2, parseCalls())
    }

    @Test
    fun `readTrack isolates cache by (sourceId, itemId)`() = runTest {
        val bundle = tmp.newFile("book.zip")
        val (repo, parseCalls) = makeRepo(bundleFile = bundle, parseResult = track)

        repo.readTrack("source-A", "item-1")
        repo.readTrack("source-B", "item-1")

        assertEquals("different servers produce separate cache entries", 2, parseCalls())
    }

    // ------- minimal collaborator stubs -------

    private class FakeStore(private val file: File?) : LocalStore {
        override fun get(sourceId: String, itemId: String): File? = file
        override suspend fun save(sourceId: String, itemId: String, stream: InputStream): File = throw UnsupportedOperationException()
        override fun delete(sourceId: String, itemId: String) = Unit
        override fun deleteSource(sourceId: String) = Unit
        override fun clear() = Unit
        override fun listItems(): List<StoredItemRef> = emptyList()
    }

    private object NoopServerRepository : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = MutableStateFlow(emptyList())
        override suspend fun getActive(): Source? = null
        override suspend fun authenticate(
            url: SourceUrl,
            username: String,
            password: String,
            insecureAllowed: Boolean,
            serverType: ServerType,
            sourceType: com.riffle.core.domain.SourceType,
        ): AuthenticateResult = AuthenticateResult.NetworkError(UnsupportedOperationException())
        override suspend fun commit(
            pending: PendingSource,
            hiddenLibraryIds: Set<String>,
        ): CommitSourceResult = CommitSourceResult.Failure(UnsupportedOperationException())
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private object NoopTokenStorage : TokenStorage {
        override suspend fun getToken(sourceId: String): String? = null
        override suspend fun saveToken(sourceId: String, token: String) = Unit
        override suspend fun deleteToken(sourceId: String) = Unit
    }
}
