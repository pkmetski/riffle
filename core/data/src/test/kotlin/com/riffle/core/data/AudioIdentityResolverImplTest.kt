package com.riffle.core.data

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudLinkEntity
import com.riffle.core.domain.AudioIdentity
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

    private fun link(absServerId: String, absItemId: String, stServerId: String, stBookId: String) =
        ReadaloudLinkEntity(absServerId, absItemId, stServerId, stBookId, ReadaloudLinkEntity.STATE_CONFIRMED, true, 1L, 1L)

    private fun item(serverId: String, id: String, hasAudio: Boolean) =
        LibraryItemEntity(serverId, id, "lib", "Title", "Author", null, 0f, hasAudio = hasAudio)

    private class FakeLinkDao : ReadaloudLinkDao {
        override suspend fun updateIdentityResult(absServerId: String, absLibraryItemId: String, result: String) = Unit
        private val store = mutableMapOf<Pair<String, String>, ReadaloudLinkEntity>()
        fun seed(e: ReadaloudLinkEntity) { store[e.absServerId to e.absLibraryItemId] = e }
        override suspend fun upsert(entity: ReadaloudLinkEntity) { store[entity.absServerId to entity.absLibraryItemId] = entity }
        override suspend fun findByAbsItem(absServerId: String, absLibraryItemId: String) = store[absServerId to absLibraryItemId]
        override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String) =
            store.values.filter { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId }
        override fun observeAll(): Flow<List<ReadaloudLinkEntity>> = flowOf(store.values.toList())
        override suspend fun allRows() = store.values.toList()
        override fun observeLinkedAbsItemIds(): Flow<List<String>> = flowOf(store.values.map { it.absLibraryItemId })
        override suspend fun countForServer(serverId: String) = 0
        override suspend fun deleteByAbsItem(absServerId: String, absLibraryItemId: String) { store.remove(absServerId to absLibraryItemId) }
        override suspend fun deleteByStorytellerBook(storytellerServerId: String, storytellerBookId: String) {
            store.values.filter { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId }
                .forEach { store.remove(it.absServerId to it.absLibraryItemId) }
        }
    }

    private class FakeLibraryItemDao : LibraryItemDao by ThrowingLibraryItemDao {
        private val items = mutableMapOf<Pair<String, String>, LibraryItemEntity>()
        fun seed(e: LibraryItemEntity) { items[e.serverId to e.id] = e }
        override suspend fun getById(serverId: String, itemId: String) = items[serverId to itemId]
    }
}
