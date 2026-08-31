package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.riffle.core.domain.LibraryFilterPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryFilterPreferencesStoreImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun buildStore(fileName: String = "library_filter_preferences.preferences_pb") =
        LibraryFilterPreferencesStoreImpl(
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { tmp.newFile(fileName) },
            ),
        )

    @Test
    fun `defaults are returned when no filters were saved`() = testScope.runTest {
        assertEquals(LibraryFilterPreferences(), buildStore().preferences("src-1", "lib-1").first())
    }

    @Test
    fun `facet not-started and sort round-trip per source library`() = testScope.runTest {
        val store = buildStore()

        store.setSelectedFacetKey("src-1", "lib-1", "language:fr")
        store.setNotStartedFilterActive("src-1", "lib-1", true)
        store.setSortModeName("src-1", "lib-1", "TITLE_DESC")

        assertEquals(
            LibraryFilterPreferences(
                selectedFacetKey = "language:fr",
                notStartedFilterActive = true,
                sortModeName = "TITLE_DESC",
            ),
            store.preferences("src-1", "lib-1").first(),
        )
        assertEquals(LibraryFilterPreferences(), store.preferences("src-1", "lib-2").first())
    }

    @Test
    fun `clearing selected facet removes only that field`() = testScope.runTest {
        val store = buildStore()

        store.setSelectedFacetKey("src-1", "lib-1", "topic:fiction")
        store.setNotStartedFilterActive("src-1", "lib-1", true)
        store.setSortModeName("src-1", "lib-1", "AUTHOR_ASC")
        store.setSelectedFacetKey("src-1", "lib-1", null)

        assertEquals(
            LibraryFilterPreferences(
                selectedFacetKey = null,
                notStartedFilterActive = true,
                sortModeName = "AUTHOR_ASC",
            ),
            store.preferences("src-1", "lib-1").first(),
        )
    }

    @Test
    fun `preferences persist across store instances`() {
        val file = tmp.newFile("library_filter_preferences_round_trip.preferences_pb")

        val writeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        runBlocking {
            LibraryFilterPreferencesStoreImpl(
                PreferenceDataStoreFactory.create(scope = writeScope, produceFile = { file }),
            ).setSelectedFacetKey("src-1", "lib-1", "genre:audio")
        }
        writeScope.cancel()

        val readScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val store = LibraryFilterPreferencesStoreImpl(
            PreferenceDataStoreFactory.create(scope = readScope, produceFile = { file }),
        )
        assertEquals("genre:audio", runBlocking { store.preferences("src-1", "lib-1").first().selectedFacetKey })
        readScope.cancel()
    }
}
