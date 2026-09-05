package com.riffle.feature.library

import com.riffle.core.domain.AnnotatedBook
import com.riffle.core.domain.AnnotationsLibraryRepository
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceUrl
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ported from the former :app AnnotationsListViewModelTest when the VM was relocated from :shared
 * to :feature:library so :app could consume it (see docs/testing/android-ios-parity-audit.md).
 * Verifies the active-server derivation (Storyteller excluded) and per-library scoping on both
 * platforms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationsListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun before() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun after() { Dispatchers.resetMain() }

    private fun annotatedBook(itemId: String, highlightCount: Int, latestUpdatedAt: Long) = AnnotatedBook(
        sourceId = "unused",
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
        val vm = AnnotationsListViewModel("lib1", FakeSourceRepository("S1"), repo, fakeTokenStorage())
        val state = vm.state.first { !it.loading }
        assertEquals(listOf("B", "A"), state.books.map { it.itemId })
    }

    @Test
    fun switchesServerAndReflectsNewList() = runTest(dispatcher) {
        val repo = FakeAnnotationsLibraryRepository().apply {
            emitForLibrary("S1", "lib1", listOf(annotatedBook("A", 1, 100)))
            emitForLibrary("S2", "lib1", listOf(annotatedBook("X", 3, 999)))
        }
        val sourceRepository = FakeSourceRepository("S1")
        val vm = AnnotationsListViewModel("lib1", sourceRepository, repo, fakeTokenStorage())
        vm.state.first { it.books.singleOrNull()?.itemId == "A" }
        sourceRepository.setActiveServerId("S2")
        vm.state.first { it.books.singleOrNull()?.itemId == "X" }
    }

    @Test
    fun emitsOnlyBooksScopedToTheGivenLibrary() = runTest(dispatcher) {
        val repo = FakeAnnotationsLibraryRepository().apply {
            emitForLibrary("S1", "lib1", listOf(annotatedBook("A", 1, 100)))
            emitForLibrary("S1", "lib2", listOf(annotatedBook("B", 2, 200)))
        }
        val vm = AnnotationsListViewModel("lib1", FakeSourceRepository("S1"), repo, fakeTokenStorage())
        val state = vm.state.first { !it.loading }
        assertEquals(listOf("A"), state.books.map { it.itemId })
    }

    @Test
    fun switchingLibraryCleanlySwitchesEmittedListWithNoStaleMerge() = runTest(dispatcher) {
        val repo = FakeAnnotationsLibraryRepository().apply {
            emitForLibrary("S1", "lib1", listOf(annotatedBook("A", 1, 100)))
            emitForLibrary("S1", "lib2", listOf(annotatedBook("B", 2, 200)))
        }
        val sourceRepository = FakeSourceRepository("S1")
        val vmLib1 = AnnotationsListViewModel("lib1", sourceRepository, repo, fakeTokenStorage())
        val vmLib2 = AnnotationsListViewModel("lib2", sourceRepository, repo, fakeTokenStorage())
        val stateLib1 = vmLib1.state.first { !it.loading }
        val stateLib2 = vmLib2.state.first { !it.loading }
        assertEquals(listOf("A"), stateLib1.books.map { it.itemId })
        assertEquals(listOf("B"), stateLib2.books.map { it.itemId })
    }
}

private fun fakeTokenStorage(): TokenStorage = object : TokenStorage {
    override suspend fun saveToken(sourceId: String, token: String) {}
    override suspend fun getToken(sourceId: String): String? = null
    override suspend fun deleteToken(sourceId: String) {}
}

private class FakeSourceRepository(activeServerId: String?) : SourceRepository {
    private val servers = MutableStateFlow(listOfNotNull(activeServerId?.let { server(it, active = true) }))

    fun setActiveServerId(sourceId: String) {
        servers.update { current ->
            val existing = current.firstOrNull { it.id == sourceId }
            val updated = current.map { it.copy(isActive = it.id == sourceId) }
            if (existing != null) updated else updated + server(sourceId, active = true)
        }
    }

    private fun server(id: String, active: Boolean) = Source(
        id = id,
        url = SourceUrl.parse("https://$id.example.com")!!,
        isActive = active,
        insecureConnectionAllowed = false,
        username = "",
        serverType = ServerType.AUDIOBOOKSHELF,
    )

    override fun observeAll(): Flow<List<Source>> = servers
    override suspend fun getActive(): Source? = servers.value.firstOrNull { it.isActive }
    override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
        CommitSourceResult.Failure(RuntimeException())
    override suspend fun setActive(sourceId: String) { setActiveServerId(sourceId) }
    override suspend fun remove(sourceId: String) {}
    override suspend fun getSourceVersion(sourceId: String): String? = null
}

private class FakeAnnotationsLibraryRepository : AnnotationsLibraryRepository {
    private val booksByServer = mutableMapOf<String, MutableStateFlow<List<AnnotatedBook>>>()
    private val booksByServerAndLibrary = mutableMapOf<Pair<String, String>, MutableStateFlow<List<AnnotatedBook>>>()

    fun emitForLibrary(sourceId: String, libraryId: String, books: List<AnnotatedBook>) {
        flowForLibrary(sourceId, libraryId).value = books
    }

    private fun flowFor(sourceId: String) =
        booksByServer.getOrPut(sourceId) { MutableStateFlow(emptyList()) }

    private fun flowForLibrary(sourceId: String, libraryId: String) =
        booksByServerAndLibrary.getOrPut(sourceId to libraryId) { MutableStateFlow(emptyList()) }

    override fun observeAnnotatedBooks(sourceId: String): Flow<List<AnnotatedBook>> = flowFor(sourceId)

    override fun observeAnnotatedBooks(sourceId: String, libraryId: String): Flow<List<AnnotatedBook>> =
        flowForLibrary(sourceId, libraryId)
}
