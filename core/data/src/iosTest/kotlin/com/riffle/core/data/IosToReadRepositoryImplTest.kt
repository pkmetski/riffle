package com.riffle.core.data

import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.logging.RecordingLogger
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkCollection
import com.riffle.core.network.NetworkLibrary
import com.riffle.core.network.NetworkLibraryItem
import com.riffle.core.network.NetworkPlaylist
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.NetworkSeries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [IosToReadRepositoryImpl.refreshForSource] return-value semantics:
 * - `false` → server genuinely unreachable ([NetworkResult.Offline]); Riffle hub offline banner should show
 * - `true`  → server reachable but returned a non-network error (403, parse, etc.); banner must NOT show
 */
class IosToReadRepositoryImplTest {

    // ── refreshForSource ──────────────────────────────────────────────────────

    @Test
    fun refreshForSourceReturnsTrueOnSuccess() = runTest {
        val playlist = NetworkPlaylist(
            id = "pl-1", libraryId = "lib-1", name = TO_READ_PLAYLIST_NAME,
            items = emptyList(), bookIds = setOf("item-1"),
        )
        val repo = makeRepo(getPlaylistsResult = NetworkResult.Success(listOf(playlist)))
        assertTrue(repo.refreshForSource("src-1", "lib-1"))
        assertEquals(setOf("item-1"), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun refreshForSourceReturnsFalseWhenServerIsOffline() = runTest {
        // NetworkResult.Offline = server unreachable → must propagate as false so the
        // Riffle hub offline banner appears.
        val repo = makeRepo(getPlaylistsResult = NetworkResult.Offline(RuntimeException("connection refused")))
        assertFalse(repo.refreshForSource("src-1", "lib-1"))
    }

    @Test
    fun refreshForSourceReturnsTrueWhenServerReturnsAuthError() = runTest {
        // NetworkResult.Auth (403 — readlist permission not granted) means the server IS
        // reachable. The offline banner must not appear.
        val repo = makeRepo(getPlaylistsResult = NetworkResult.Auth)
        assertTrue(repo.refreshForSource("src-1", "lib-1"))
    }

    @Test
    fun refreshForSourceReturnsTrueWhenServerReturnsServerError() = runTest {
        // A 5xx or other HTTP error means the server IS reachable, just returned an error.
        val repo = makeRepo(getPlaylistsResult = NetworkResult.ServerError(500))
        assertTrue(repo.refreshForSource("src-1", "lib-1"))
    }

    @Test
    fun refreshForSourceReturnsTrueForNonAbsSource() = runTest {
        // Non-ABS sources don't have playlists. refreshForSource must return true (success)
        // without hitting the network — NetworkResult.Offline must not be seen.
        val repo = makeRepo(
            source = absSource().copy(type = SourceType.KOMGA),
            getPlaylistsResult = NetworkResult.Offline(RuntimeException("should not be called")),
        )
        assertTrue(repo.refreshForSource("src-1", "lib-1"))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun absSource() = Source(
        id = "src-1",
        url = SourceUrl.parse("http://abs.test")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "u",
        type = SourceType.ABS,
    )

    private fun makeRepo(
        source: Source = absSource(),
        token: String = "tok",
        getPlaylistsResult: NetworkResult<List<NetworkPlaylist>>,
    ) = IosToReadRepositoryImpl(
        absLibraryApi = FixedPlaylistApi(getPlaylistsResult),
        sourceRepository = FixedSourceRepository(source),
        tokenStorage = FixedTokenStorage(token),
        logger = RecordingLogger(),
    )

    private class FixedPlaylistApi(
        private val result: NetworkResult<List<NetworkPlaylist>>,
    ) : AbsLibraryApi {
        override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
            throw UnsupportedOperationException()
        override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
            throw UnsupportedOperationException()
        override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
            throw UnsupportedOperationException()
        override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
            throw UnsupportedOperationException()
        override suspend fun getPlaylists(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkPlaylist>> =
            result
    }

    private class FixedSourceRepository(private val source: Source) : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(listOf(source))
        override suspend fun getActive(): Source? = source
        override suspend fun getById(sourceId: String): Source? = source.takeIf { it.id == sourceId }
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(UnsupportedOperationException())
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private class FixedTokenStorage(private val token: String?) : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) = Unit
        override suspend fun getToken(sourceId: String): String? = token
        override suspend fun deleteToken(sourceId: String) = Unit
    }
}
