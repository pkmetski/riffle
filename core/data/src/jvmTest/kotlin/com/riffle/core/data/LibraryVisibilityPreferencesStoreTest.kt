package com.riffle.core.data

import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeLibraryVisibilityPreferencesStore : LibraryVisibilityPreferencesStore {
    private val hidden = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    override fun hiddenLibraryIds(sourceId: String): Flow<Set<String>> =
        hidden.map { it[sourceId].orEmpty() }

    override suspend fun hideLibrary(sourceId: String, libraryId: String) {
        hidden.update { it + (sourceId to (it[sourceId].orEmpty() + libraryId)) }
    }

    override suspend fun showLibrary(sourceId: String, libraryId: String) {
        hidden.update { it + (sourceId to (it[sourceId].orEmpty() - libraryId)) }
    }
}

class LibraryVisibilityPreferencesStoreTest {

    private fun makeStore() = FakeLibraryVisibilityPreferencesStore()

    @Test
    fun `hidden set is empty by default - all libraries are visible`() = runTest {
        val store = makeStore()
        val hidden = store.hiddenLibraryIds("source-1").first()
        assertTrue(hidden.isEmpty())
    }

    @Test
    fun `hideLibrary adds the library to the hidden set`() = runTest {
        val store = makeStore()
        store.hideLibrary("source-1", "lib-1")
        val hidden = store.hiddenLibraryIds("source-1").first()
        assertTrue("lib-1" in hidden)
    }

    @Test
    fun `showLibrary removes the library from the hidden set`() = runTest {
        val store = makeStore()
        store.hideLibrary("source-1", "lib-1")
        store.showLibrary("source-1", "lib-1")
        val hidden = store.hiddenLibraryIds("source-1").first()
        assertFalse("lib-1" in hidden)
    }

    @Test
    fun `hiding an already-hidden library is idempotent`() = runTest {
        val store = makeStore()
        store.hideLibrary("source-1", "lib-1")
        store.hideLibrary("source-1", "lib-1")
        val hidden = store.hiddenLibraryIds("source-1").first()
        assertEquals(1, hidden.size)
        assertTrue("lib-1" in hidden)
    }

    @Test
    fun `store does not enforce minimum-one-visible - all libraries can be hidden`() = runTest {
        val store = makeStore()
        store.hideLibrary("source-1", "lib-1")
        store.hideLibrary("source-1", "lib-2")
        val hidden = store.hiddenLibraryIds("source-1").first()
        assertEquals(setOf("lib-1", "lib-2"), hidden)
    }
}
