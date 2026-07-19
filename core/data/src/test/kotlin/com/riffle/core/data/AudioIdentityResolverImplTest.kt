package com.riffle.core.data

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudLinkEntity
import com.riffle.core.models.AudioIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioIdentityResolverImplTest {

    @Test
    fun `prefers the linked audiobook's id`() = runTest {
        val links = FakeLinkDao().apply {
            seed(link("abs-1", "ebook", "st-1", "42"))
            seed(link("abs-1", "audio", "st-1", "42"))
        }
        val items = FakeLibraryItemDao().apply {
            seed(item("abs-1", "ebook", hasAudio = false))
            seed(item("abs-1", "audio", hasAudio = true))
        }
        val resolver = AudioIdentityResolverImpl(links, items)

        assertEquals(AudioIdentity("abs-1", "audio"), resolver.resolveForStorytellerBook("st-1", "42"))
    }

    @Test
    fun `falls back to the Storyteller id when only an ebook is linked`() = runTest {
        val links = FakeLinkDao().apply { seed(link("abs-1", "ebook", "st-1", "42")) }
        val items = FakeLibraryItemDao().apply { seed(item("abs-1", "ebook", hasAudio = false)) }
        val resolver = AudioIdentityResolverImpl(links, items)

        assertEquals(AudioIdentity("st-1", "42"), resolver.resolveForStorytellerBook("st-1", "42"))
    }

    @Test
    fun `falls back to the Storyteller id when nothing is linked`() = runTest {
        val resolver = AudioIdentityResolverImpl(FakeLinkDao(), FakeLibraryItemDao())
        assertEquals(AudioIdentity("st-1", "42"), resolver.resolveForStorytellerBook("st-1", "42"))
    }

    private fun link(absSourceId: String, absItemId: String, stServerId: String, stBookId: String) =
        ReadaloudLinkEntity(absSourceId, absItemId, stServerId, stBookId, ReadaloudLinkEntity.STATE_CONFIRMED, true, 1L, 1L)

    private fun item(sourceId: String, id: String, hasAudio: Boolean) =
        LibraryItemEntity(sourceId, id, "lib", "Title", "Author", null, 0f, hasAudio = hasAudio, addedAt = 0L)

    private class FakeLinkDao : ReadaloudLinkDao {
        override suspend fun updateIdentityResult(absSourceId: String, absLibraryItemId: String, result: String) = Unit
        private val store = mutableMapOf<Pair<String, String>, ReadaloudLinkEntity>()
        fun seed(e: ReadaloudLinkEntity) { store[e.absSourceId to e.absLibraryItemId] = e }
        override suspend fun upsert(entity: ReadaloudLinkEntity) { store[entity.absSourceId to entity.absLibraryItemId] = entity }
        override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String) = store[absSourceId to absLibraryItemId]
        override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String) =
            store.values.filter { it.storytellerSourceId == storytellerSourceId && it.storytellerBookId == storytellerBookId }
        override fun observeAll(): Flow<List<ReadaloudLinkEntity>> = flowOf(store.values.toList())
        override suspend fun allRows() = store.values.toList()
        override fun observeLinkedAbsItemIds(): Flow<List<String>> = flowOf(store.values.map { it.absLibraryItemId })
        override suspend fun countForSource(sourceId: String) = 0
        override suspend fun deleteByAbsItem(absSourceId: String, absLibraryItemId: String) { store.remove(absSourceId to absLibraryItemId) }
        override suspend fun deleteByStorytellerBook(storytellerSourceId: String, storytellerBookId: String) {
            store.values.filter { it.storytellerSourceId == storytellerSourceId && it.storytellerBookId == storytellerBookId }
                .forEach { store.remove(it.absSourceId to it.absLibraryItemId) }
        }
    }

    private class FakeLibraryItemDao : LibraryItemDao by ThrowingLibraryItemDao {
        private val items = mutableMapOf<Pair<String, String>, LibraryItemEntity>()
        fun seed(e: LibraryItemEntity) { items[e.sourceId to e.id] = e }
        override suspend fun getById(sourceId: String, itemId: String) = items[sourceId to itemId]
    }
}
