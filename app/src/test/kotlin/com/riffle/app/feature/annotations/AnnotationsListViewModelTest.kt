package com.riffle.app.feature.annotations

import com.riffle.core.data.AnnotatedBook
import com.riffle.core.data.AnnotationsLibraryRepository
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.PendingServer
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import androidx.lifecycle.SavedStateHandle
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationsListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    private fun annotatedBook(itemId: String, highlightCount: Int, latestUpdatedAt: Long) = AnnotatedBook(
        serverId = "unused",
        itemId = itemId,
        title = "Title $itemId",
        author = "Author $itemId",
        coverUrl = null,
        highlightCount = highlightCount,
        latestUpdatedAt = latestUpdatedAt,
    )

    @Test
    fun emitsRepoBooksForActiveServer() = runTest(dispatcher) {
        val repo = FakeAnnotationsLibraryRepository().apply {
            emitForLibrary("S1", "lib1", listOf(annotatedBook("B", 1, 300), annotatedBook("A", 2, 200)))
        }
        val serverRepository = FakeServerRepository(activeServerId = "S1")
        val tokenStorage = fakeTokenStorage()
        val vm = AnnotationsListViewModel(serverRepository, repo, tokenStorage, savedStateHandle("lib1"))

        val state = vm.state.first { !it.loading }
        assertEquals(listOf("B", "A"), state.books.map { it.itemId })
    }

    @Test
    fun switchesServerAndReflectsNewList() = runTest(dispatcher) {
        val repo = FakeAnnotationsLibraryRepository().apply {
            emitForLibrary("S1", "lib1", listOf(annotatedBook("A", 1, 100)))
            emitForLibrary("S2", "lib1", listOf(annotatedBook("X", 3, 999)))
        }
        val serverRepository = FakeServerRepository(activeServerId = "S1")
        val tokenStorage = fakeTokenStorage()
        val vm = AnnotationsListViewModel(serverRepository, repo, tokenStorage, savedStateHandle("lib1"))

        vm.state.first { it.books.singleOrNull()?.itemId == "A" }

        serverRepository.setActiveServerId("S2")

        vm.state.first { it.books.singleOrNull()?.itemId == "X" }
    }

    // Step B (per-Library scoping): the VM must read libraryId from its SavedStateHandle nav arg
    // (mirroring LibraryItemsViewModel's `savedStateHandle.get<String>("libraryId")` pattern) and
    // call the library-scoped repo overload. Without this the VM would still call the per-server
    // overload and leak books from every library on the server into a single library's tab.
    @Test
    fun emitsOnlyBooksScopedToTheGivenLibrary() = runTest(dispatcher) {
        val repo = FakeAnnotationsLibraryRepository().apply {
            emitForLibrary("S1", "lib1", listOf(annotatedBook("A", 1, 100)))
            emitForLibrary("S1", "lib2", listOf(annotatedBook("B", 2, 200)))
        }
        val serverRepository = FakeServerRepository(activeServerId = "S1")
        val tokenStorage = fakeTokenStorage()
        val vm = AnnotationsListViewModel(serverRepository, repo, tokenStorage, savedStateHandle("lib1"))

        val state = vm.state.first { !it.loading }

        assertEquals(listOf("A"), state.books.map { it.itemId })
    }

    @Test
    fun switchingLibraryCleanlySwitchesEmittedListWithNoStaleMerge() = runTest(dispatcher) {
        val repo = FakeAnnotationsLibraryRepository().apply {
            emitForLibrary("S1", "lib1", listOf(annotatedBook("A", 1, 100)))
            emitForLibrary("S1", "lib2", listOf(annotatedBook("B", 2, 200)))
        }
        val serverRepository = FakeServerRepository(activeServerId = "S1")
        val tokenStorage = fakeTokenStorage()
        val vmLib1 = AnnotationsListViewModel(serverRepository, repo, tokenStorage, savedStateHandle("lib1"))
        val vmLib2 = AnnotationsListViewModel(serverRepository, repo, tokenStorage, savedStateHandle("lib2"))

        val stateLib1 = vmLib1.state.first { !it.loading }
        val stateLib2 = vmLib2.state.first { !it.loading }

        assertEquals(listOf("A"), stateLib1.books.map { it.itemId })
        assertEquals(listOf("B"), stateLib2.books.map { it.itemId })
    }
}

private fun savedStateHandle(libraryId: String) = SavedStateHandle(mapOf("libraryId" to libraryId))

private fun fakeTokenStorage(): TokenStorage = object : TokenStorage {
    override suspend fun saveToken(serverId: String, token: String) {}
    override suspend fun getToken(serverId: String): String? = null
    override suspend fun deleteToken(serverId: String) {}
}

/** Test-only fake mirroring [ServerRepository]; exposes a mutable active-server id for tests. */
private class FakeServerRepository(activeServerId: String?) : ServerRepository {
    private val servers = MutableStateFlow(
        listOfNotNull(activeServerId?.let { server(it, active = true) }),
    )

    fun setActiveServerId(serverId: String) {
        servers.update { current ->
            val existing = current.firstOrNull { it.id == serverId }
            val updated = current.map { it.copy(isActive = it.id == serverId) }
            if (existing != null) updated else updated + server(serverId, active = true)
        }
    }

    private fun server(id: String, active: Boolean) = Server(
        id = id,
        url = ServerUrl.parse("https://$id.example.com")!!,
        isActive = active,
        insecureConnectionAllowed = false,
        username = "",
        serverType = ServerType.AUDIOBOOKSHELF,
    )

    override fun observeAll(): Flow<List<Server>> = servers
    override suspend fun getActive(): Server? = servers.value.firstOrNull { it.isActive }
    override suspend fun authenticate(
        url: ServerUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
        serverType: ServerType,
    ): AuthenticateResult = AuthenticateResult.NetworkError(IOException())
    override suspend fun commit(pending: PendingServer, hiddenLibraryIds: Set<String>): CommitServerResult =
        CommitServerResult.Failure(IOException())
    override suspend fun setActive(serverId: String) {
        setActiveServerId(serverId)
    }
    override suspend fun remove(serverId: String) {}
    override suspend fun getServerVersion(serverId: String): String? = null
}

/** Test-only fake with a per-server and per-(server, library) mutable emission source. */
private class FakeAnnotationsLibraryRepository : AnnotationsLibraryRepository {
    private val booksByServer = mutableMapOf<String, MutableStateFlow<List<AnnotatedBook>>>()
    private val booksByServerAndLibrary = mutableMapOf<Pair<String, String>, MutableStateFlow<List<AnnotatedBook>>>()

    fun emit(serverId: String, books: List<AnnotatedBook>) {
        flowFor(serverId).value = books
    }

    fun emitForLibrary(serverId: String, libraryId: String, books: List<AnnotatedBook>) {
        flowForLibrary(serverId, libraryId).value = books
    }

    private fun flowFor(serverId: String) =
        booksByServer.getOrPut(serverId) { MutableStateFlow(emptyList()) }

    private fun flowForLibrary(serverId: String, libraryId: String) =
        booksByServerAndLibrary.getOrPut(serverId to libraryId) { MutableStateFlow(emptyList()) }

    override fun observeAnnotatedBooks(serverId: String): Flow<List<AnnotatedBook>> = flowFor(serverId)

    override fun observeAnnotatedBooks(serverId: String, libraryId: String): Flow<List<AnnotatedBook>> =
        flowForLibrary(serverId, libraryId)
}
