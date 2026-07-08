package com.riffle.core.data

import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.BookFormattingPreferencesEntity
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.FormattingScope
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression pins for the annotations-view separate-preferences change: a save under one
 * [FormattingScope] must not leak into a read under the other scope for the same book, and clear
 * must be scope-scoped. Revert the DAO's `scope` PK column or the [BookFormattingPreferencesStoreImpl]
 * threading of `scope.name` into DAO calls and these assertions flip red.
 */
class BookFormattingPreferencesStoreScopeIsolationTest {

    private class InMemoryDao : BookFormattingPreferencesDao {
        val rows = mutableMapOf<Triple<String, String, String>, BookFormattingPreferencesEntity>()
        override suspend fun upsert(entity: BookFormattingPreferencesEntity) {
            rows[Triple(entity.sourceId, entity.itemId, entity.scope)] = entity
        }
        override suspend fun getByItemId(
            sourceId: String,
            itemId: String,
            scope: String,
        ): BookFormattingPreferencesEntity? = rows[Triple(sourceId, itemId, scope)]
        override suspend fun deleteByItemId(sourceId: String, itemId: String, scope: String) {
            rows.remove(Triple(sourceId, itemId, scope))
        }
    }

    private class FixedActiveSourceRepository(private val active: Source) : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = MutableStateFlow(listOf(active))
        override suspend fun getActive(): Source? = active
        override suspend fun getById(sourceId: String): Source? =
            active.takeIf { it.id == sourceId }
        override suspend fun authenticate(
            url: SourceUrl,
            username: String,
            password: String,
            insecureAllowed: Boolean,
            serverType: ServerType,
        ): AuthenticateResult = throw UnsupportedOperationException()
        override suspend fun commit(
            pending: PendingSource,
            hiddenLibraryIds: Set<String>,
        ): CommitSourceResult = throw UnsupportedOperationException()
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private fun newStore(): BookFormattingPreferencesStoreImpl = BookFormattingPreferencesStoreImpl(
        dao = InMemoryDao(),
        sourceRepository = FixedActiveSourceRepository(
            Source(
                id = "srv-A",
                url = SourceUrl.parse("http://localhost")!!,
                isActive = true,
                insecureConnectionAllowed = false,
                username = "",
                serverType = ServerType.AUDIOBOOKSHELF,
            ),
        ),
    )

    @Test
    fun `save under FullBook does not affect read under Highlights`() = runTest {
        val store = newStore()
        store.save("item-1", FormattingScope.FullBook, BookFormattingOverrides(fontSize = 2.0f))

        assertEquals(
            "FullBook read must return what FullBook wrote",
            2.0f,
            store.load("item-1", FormattingScope.FullBook).fontSize,
        )
        assertNull(
            "Highlights read must not see FullBook's write",
            store.load("item-1", FormattingScope.Highlights).fontSize,
        )
    }

    @Test
    fun `save under Highlights does not affect read under FullBook`() = runTest {
        val store = newStore()
        store.save("item-1", FormattingScope.Highlights, BookFormattingOverrides(theme = ReaderTheme.Dark))

        assertEquals(
            ReaderTheme.Dark,
            store.load("item-1", FormattingScope.Highlights).theme,
        )
        assertNull(
            "FullBook read must not see Highlights' write",
            store.load("item-1", FormattingScope.FullBook).theme,
        )
    }

    @Test
    fun `both scopes hold independent values for the same book`() = runTest {
        val store = newStore()
        store.save("item-1", FormattingScope.FullBook, BookFormattingOverrides(fontSize = 1.4f))
        store.save("item-1", FormattingScope.Highlights, BookFormattingOverrides(fontSize = 1.8f))

        assertEquals(1.4f, store.load("item-1", FormattingScope.FullBook).fontSize)
        assertEquals(1.8f, store.load("item-1", FormattingScope.Highlights).fontSize)
    }

    @Test
    fun `clear only removes the targeted scope`() = runTest {
        val store = newStore()
        store.save("item-1", FormattingScope.FullBook, BookFormattingOverrides(fontSize = 1.4f))
        store.save("item-1", FormattingScope.Highlights, BookFormattingOverrides(fontSize = 1.8f))

        store.clear("item-1", FormattingScope.Highlights)

        assertEquals(
            "FullBook value must survive a Highlights-scoped clear",
            1.4f,
            store.load("item-1", FormattingScope.FullBook).fontSize,
        )
        assertNull(
            "Highlights value must be gone after a Highlights-scoped clear",
            store.load("item-1", FormattingScope.Highlights).fontSize,
        )
    }
}
