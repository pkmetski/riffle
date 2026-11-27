package com.riffle.core.domain.localfiles

import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReconcileLocalFilesVisibilityTest {

    private class FakeVisibilityStore(
        initialHidden: Map<String, Set<String>> = emptyMap(),
    ) : LibraryVisibilityPreferencesStore {
        val hidden = MutableStateFlow(initialHidden)
        var showLibraryCalls = 0
        override fun hiddenLibraryIds(sourceId: String): Flow<Set<String>> =
            hidden.map { it[sourceId].orEmpty() }
        override suspend fun hideLibrary(sourceId: String, libraryId: String) {
            hidden.update { it + (sourceId to (it[sourceId].orEmpty() + libraryId)) }
        }
        override suspend fun showLibrary(sourceId: String, libraryId: String) {
            showLibraryCalls++
            hidden.update { it + (sourceId to (it[sourceId].orEmpty() - libraryId)) }
        }
    }

    private fun source(id: String, type: SourceType) = Source(
        id = id,
        url = SourceUrl.parse("https://source.invalid")!!,
        isActive = false,
        insecureConnectionAllowed = false,
        username = "",
        type = type,
    )

    @Test
    fun clearsEveryHiddenLibraryOfTheLocalFilesSource() = runTest {
        val store = FakeVisibilityStore(mapOf("lf" to setOf("a", "b")))
        reconcileLocalFilesVisibility(source("lf", SourceType.LOCAL_FILES), store)
        assertEquals(emptySet(), store.hidden.value["lf"].orEmpty())
    }

    @Test
    fun writesNothingWhenTheLocalFilesSourceHasNoHiddenLibraries() = runTest {
        val store = FakeVisibilityStore(emptyMap())
        reconcileLocalFilesVisibility(source("lf", SourceType.LOCAL_FILES), store)
        assertEquals(0, store.showLibraryCalls)
    }

    @Test
    fun ignoresANonLocalSource() = runTest {
        val store = FakeVisibilityStore(mapOf("abs" to setOf("a")))
        reconcileLocalFilesVisibility(source("abs", SourceType.ABS), store)
        assertEquals(setOf("a"), store.hidden.value["abs"].orEmpty())
        assertEquals(0, store.showLibraryCalls)
    }

    @Test
    fun ignoresANullSource() = runTest {
        val store = FakeVisibilityStore(mapOf("lf" to setOf("a")))
        reconcileLocalFilesVisibility(null, store)
        assertEquals(0, store.showLibraryCalls)
    }
}
