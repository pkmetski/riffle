package com.riffle.app.feature.library

import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(testDispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private val librariesFlow = MutableStateFlow<List<Library>>(emptyList())

    private fun fakeRepo(
        refreshResult: LibraryRefreshResult = LibraryRefreshResult.Success,
    ): LibraryRepository = object : LibraryRepository {
        override fun observeLibraries(): Flow<List<Library>> = librariesFlow
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun refreshLibraries() = refreshResult
        override suspend fun refreshLibraryItems(libraryId: String) = refreshResult
    }

    @Test
    fun `libraries state reflects repository flow`() = runTest {
        val vm = LibraryListViewModel(fakeRepo())
        val lib = Library("lib-1", "Books", "book")
        // A subscriber is required to activate stateIn(WhileSubscribed) collection
        backgroundScope.launch { vm.libraries.collect {} }
        librariesFlow.value = listOf(lib)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(lib), vm.libraries.value)
    }

    @Test
    fun `isOffline is false after successful refresh`() = runTest {
        val vm = LibraryListViewModel(fakeRepo(LibraryRefreshResult.Success))
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.isOffline)
    }

    @Test
    fun `isOffline is true when refresh returns NetworkError`() = runTest {
        val vm = LibraryListViewModel(fakeRepo(LibraryRefreshResult.NetworkError(IOException("no network"))))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.isOffline)
    }

    @Test
    fun `isOffline resets to false on subsequent successful refresh`() = runTest {
        var callCount = 0
        val repo = object : LibraryRepository {
            override fun observeLibraries(): Flow<List<Library>> = librariesFlow
            override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
            override suspend fun refreshLibraries(): LibraryRefreshResult {
                return if (callCount++ == 0) LibraryRefreshResult.NetworkError(IOException())
                else LibraryRefreshResult.Success
            }
            override suspend fun refreshLibraryItems(libraryId: String) = LibraryRefreshResult.Success
        }
        val vm = LibraryListViewModel(repo)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.isOffline)
        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.isOffline)
    }
}
