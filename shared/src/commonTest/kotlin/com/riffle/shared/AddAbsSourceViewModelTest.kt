package com.riffle.shared

import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkLibrary
import com.riffle.core.network.NetworkLoginUser
import com.riffle.core.network.NetworkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural coverage for the iOS add-ABS-source flow. Regression context: PR #908 unwired
 * [AddAbsSourceScreen] from the empty start destination, leaving iOS with no way to add an
 * Audiobookshelf server — see [AddSourceOptionsTest] for the surface pin; these tests pin the
 * connect flow itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddAbsSourceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class RecordingSourceRepository : SourceRepository {
        private val committedSource = Source(
            id = "new-source",
            url = com.riffle.core.models.SourceUrl.parse("https://abs.example.com")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "alice",
        )
        val committed = mutableListOf<PendingSource>()
        override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
        override suspend fun getActive(): Source? = null
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult {
            committed += pending
            return CommitSourceResult.Success(committedSource)
        }
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private fun libraryApi(
        libraries: NetworkResult<List<NetworkLibrary>> =
            NetworkResult.Success(
                listOf(NetworkLibrary(id = "lib1", name = "Books", mediaType = "book", audiobooksOnly = false)),
            ),
    ): AbsLibraryApi = object : AbsLibraryApi {
        override suspend fun getLibraries(
            baseUrl: String,
            token: String,
            insecureAllowed: Boolean,
        ): NetworkResult<List<NetworkLibrary>> = libraries

        override suspend fun getLibraryItems(
            baseUrl: String,
            libraryId: String,
            token: String,
            insecureAllowed: Boolean,
        ) = error("not used")

        override suspend fun getSeries(
            baseUrl: String,
            libraryId: String,
            token: String,
            insecureAllowed: Boolean,
        ) = error("not used")

        override suspend fun getCollections(
            baseUrl: String,
            libraryId: String,
            token: String,
            insecureAllowed: Boolean,
        ) = error("not used")
    }

    private val successfulLogin = AbsApi { _, _, _, _ ->
        NetworkResult.Success(NetworkLoginUser(userId = "u1", token = "tok", username = "alice"))
    }

    private fun viewModel(
        absApi: AbsApi = successfulLogin,
        absLibraryApi: AbsLibraryApi = libraryApi(),
        repository: SourceRepository = RecordingSourceRepository(),
    ) = AddAbsSourceViewModel(absApi, absLibraryApi, repository).apply {
        url = "abs.example.com"
        username = "alice"
        password = "secret"
    }

    @Test
    fun successfulConnectCommitsAbsSourceAndEmitsSourceAdded() = runTest(testDispatcher.scheduler) {
        val repository = RecordingSourceRepository()
        val vm = viewModel(repository = repository)

        vm.onConnect()
        advanceUntilIdle()

        val pending = repository.committed.singleOrNull()
        assertNotNull(pending, "commit must be called exactly once")
        assertEquals(SourceType.ABS, pending.sourceType)
        assertEquals(ServerType.AUDIOBOOKSHELF, pending.serverType)
        assertEquals("alice", pending.username)
        assertEquals("https://abs.example.com", pending.url.value)
        assertEquals(listOf("lib1"), pending.libraries.map { it.id })
        assertNull(vm.error)
        // The screen navigates away on this emission — it must fire.
        vm.sourceAdded.first()
    }

    @Test
    fun failedLoginSurfacesErrorAndDoesNotCommit() = runTest(testDispatcher.scheduler) {
        val repository = RecordingSourceRepository()
        val vm = viewModel(
            absApi = AbsApi { _, _, _, _ -> NetworkResult.Auth },
            repository = repository,
        )

        vm.onConnect()
        advanceUntilIdle()

        assertEquals("Invalid username or password", vm.error)
        assertTrue(repository.committed.isEmpty(), "must not commit a source on failed login")
        assertEquals(false, vm.isLoading)
    }

    @Test
    fun serverWithNoBookLibrariesSurfacesErrorAndDoesNotCommit() = runTest(testDispatcher.scheduler) {
        val repository = RecordingSourceRepository()
        val vm = viewModel(
            absLibraryApi = libraryApi(
                NetworkResult.Success(
                    listOf(NetworkLibrary(id = "pods", name = "Podcasts", mediaType = "podcast", audiobooksOnly = false)),
                ),
            ),
            repository = repository,
        )

        vm.onConnect()
        advanceUntilIdle()

        assertEquals("No book libraries found on this server", vm.error)
        assertTrue(repository.committed.isEmpty())
    }
}

/**
 * Pins the add-source surfaces' option set. [availableAddSourceOptions] drives both the empty
 * start destination and the Settings "Sources" section; removing Audiobookshelf from it (the
 * PR #908 regression) must fail here.
 */
class AddSourceOptionsTest {

    @Test
    fun addSourceSurfacesOfferAudiobookshelfAndLocalFiles() {
        val options = availableAddSourceOptions()
        assertTrue(AddSourceOption.Audiobookshelf in options, "iOS must offer adding an ABS server")
        assertTrue(AddSourceOption.LocalFiles in options, "iOS must offer adding local files")
    }
}
