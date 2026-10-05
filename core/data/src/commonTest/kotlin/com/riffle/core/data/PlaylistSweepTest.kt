package com.riffle.core.data

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.sources.webdav.WebDavPlaylist
import com.riffle.core.sources.webdav.WebDavPlaylistSyncer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistSweepTest {

    private val webDavConfig = AnnotationSyncConfig("https://dav.example.com/", "user", "pass")

    private fun configStore(config: AnnotationSyncConfig? = webDavConfig) = object : AnnotationSyncConfigStore {
        override fun observe(): StateFlow<AnnotationSyncConfig?> = MutableStateFlow(config)
        override suspend fun save(config: AnnotationSyncConfig) = Unit
        override suspend fun clear() = Unit
    }

    private fun sourceRepo(vararg sources: Source) = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(sources.toList())
        override suspend fun getActive(): Source? = null
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            throw NotImplementedError()
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private fun chitankaSource() = Source(
        id = "chitanka-1",
        url = SourceUrl.parse("https://chitanka.invalid")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
        type = SourceType.CHITANKA,
    )

    private fun absSource() = Source(
        id = "abs-1",
        url = SourceUrl.parse("http://abs.invalid")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
        type = SourceType.ABS,
    )

    private fun libraryDao(vararg ids: String) = object : LibraryDao {
        override fun observeBySourceId(sourceId: String): Flow<List<LibraryEntity>> = flowOf(emptyList())
        override suspend fun libraryIdsForSource(sourceId: String): List<String> = ids.toList()
        override suspend fun getById(sourceId: String, libraryId: String): LibraryEntity? = null
        override suspend fun upsertAll(libraries: List<LibraryEntity>) = Unit
        override suspend fun deleteBySourceId(sourceId: String) = Unit
        override suspend fun deleteById(sourceId: String, libraryId: String) = Unit
        override suspend fun setUnsupported(sourceId: String, libraryId: String, isUnsupported: Boolean) = Unit
    }

    private class FakeLocalToReadStore : LocalToReadStore {
        val items = mutableMapOf<String, MutableSet<String>>()
        val timestamps = mutableMapOf<String, Long>()

        override fun observeItemIds(libraryId: String): Flow<Set<String>> =
            flowOf(items[libraryId] ?: emptySet())

        override suspend fun isInToRead(libraryId: String, libraryItemId: String): Boolean =
            items[libraryId]?.contains(libraryItemId) == true

        override suspend fun add(libraryId: String, libraryItemId: String) {
            items.getOrPut(libraryId) { mutableSetOf() }.add(libraryItemId)
            // Use a fixed non-zero timestamp so tests can reason about "was written".
            // Deliberately avoid System.currentTimeMillis() (JVM-only).
            timestamps[libraryId] = timestamps.getOrElse(libraryId) { 0L } + 1L
        }

        override suspend fun remove(libraryId: String, libraryItemId: String) {
            items[libraryId]?.remove(libraryItemId)
        }

        override suspend fun lastUpdateMs(libraryId: String): Long = timestamps[libraryId] ?: 0L

        override suspend fun setAll(libraryId: String, itemIds: Set<String>, lastUpdateMs: Long) {
            items[libraryId] = itemIds.toMutableSet()
            timestamps[libraryId] = lastUpdateMs
        }
    }

    private class FakeSyncer(
        val pullResponse: WebDavPlaylist? = null,
        val pushTimestamp: Long = 1_000L,
    ) : WebDavPlaylistSyncer(
        config = AnnotationSyncConfig("https://x/", "u", "p"),
        httpClient = HttpClient(MockEngine { respond(ByteArray(0), HttpStatusCode.OK, headersOf()) }),
    ) {
        val pushes = mutableListOf<Pair<String, WebDavPlaylist>>()
        var pullCount = 0

        override suspend fun pull(namespace: String, playlistId: String): WebDavPlaylist? {
            pullCount++
            return pullResponse
        }

        override suspend fun push(namespace: String, playlist: WebDavPlaylist): Long {
            pushes += namespace to playlist
            return pushTimestamp
        }
    }

    /** Builds a [PlaylistSweep] that uses [syncer] regardless of config. */
    private fun sweep(
        syncer: FakeSyncer,
        sources: Array<out com.riffle.core.models.Source> = arrayOf(chitankaSource()),
        libraryIds: Array<out String> = arrayOf("books"),
        store: FakeLocalToReadStore = FakeLocalToReadStore(),
        config: AnnotationSyncConfig? = webDavConfig,
    ) = PlaylistSweep(
        sourceRepository = sourceRepo(*sources),
        libraryDao = libraryDao(*libraryIds),
        localToReadStore = store,
        httpClient = HttpClient(MockEngine { respond(ByteArray(0), HttpStatusCode.OK, headersOf()) }),
        configStore = configStore(config),
        syncerFactory = { _, _ -> syncer },
    )

    // Note: raw library IDs are used as store keys; different sources with the same library id
    // (e.g. two web sources both serving "books") can collide. This is a known v1 limitation.
    private val libraryKey = "books"

    @Test
    fun noOpWhenWebdavNotConfigured() = runTest {
        val store = FakeLocalToReadStore().also { it.add(libraryKey, "item1") }
        val syncer = FakeSyncer()
        sweep(syncer = syncer, store = store, config = null).run()
        assertEquals(0, syncer.pushes.size)
    }

    @Test
    fun localNewerPushesToRemote() = runTest {
        val store = FakeLocalToReadStore().also {
            it.setAll(libraryKey, setOf("item1"), 2_000L) // local ts=2000
        }
        val syncer = FakeSyncer(
            pullResponse = WebDavPlaylist("toread-books", "To Read", "books", listOf("old"), 1_000L), // remote ts=1000
        )
        sweep(syncer = syncer, store = store).run()
        assertEquals(1, syncer.pushes.size)
        assertEquals(setOf("item1"), syncer.pushes[0].second.itemIds.toSet())
        // After a push the local store must NOT be updated (avoids perpetual re-push if server
        // clock differs from local clock).
        assertEquals(2_000L, store.timestamps[libraryKey], "local timestamp must stay unchanged after push")
    }

    @Test
    fun remoteNewerAdoptsRemoteItems() = runTest {
        val store = FakeLocalToReadStore().also {
            it.setAll(libraryKey, setOf("old"), 1_000L) // local ts=1000
        }
        val syncer = FakeSyncer(
            pullResponse = WebDavPlaylist("toread-books", "To Read", "books", listOf("new1"), 2_000L), // remote ts=2000
        )
        sweep(syncer = syncer, store = store).run()
        assertEquals<Set<String>?>(setOf("new1"), store.items[libraryKey])
        assertEquals(0, syncer.pushes.size)
    }

    @Test
    fun equalTimestampsNoOp() = runTest {
        val store = FakeLocalToReadStore().also {
            it.setAll(libraryKey, setOf("item1"), 1_000L)
        }
        val syncer = FakeSyncer(
            pullResponse = WebDavPlaylist("toread-books", "To Read", "books", listOf("item1"), 1_000L),
        )
        sweep(syncer = syncer, store = store).run()
        assertEquals(0, syncer.pushes.size)
    }

    @Test
    fun noLibrariesInRoomIsGraceful() = runTest {
        val syncer = FakeSyncer()
        sweep(syncer = syncer, libraryIds = emptyArray()).run()
        assertEquals(0, syncer.pushes.size)
    }

    @Test
    fun nonWebSourceIsSkipped() = runTest {
        val store = FakeLocalToReadStore().also { it.add(libraryKey, "item1") }
        val syncer = FakeSyncer()
        sweep(syncer = syncer, sources = arrayOf(absSource()), store = store).run()
        assertEquals(0, syncer.pushes.size)
    }

    @Test
    fun secondRunSkipsPullWhenInSync() = runTest {
        // First run: local is newer → push. Cache records that remote now holds localTs.
        val store = FakeLocalToReadStore().also {
            it.setAll(libraryKey, setOf("item1"), 2_000L)
        }
        val syncer = FakeSyncer(
            pullResponse = WebDavPlaylist("toread-books", "To Read", "books", listOf("old"), 1_000L),
        )
        val s = sweep(syncer = syncer, store = store)
        s.run()
        assertEquals(1, syncer.pullCount)
        assertEquals(1, syncer.pushes.size)

        // Second run: localTs is unchanged (2000). Cache says remote was updated to 2000 after push.
        // Sweep must skip the GET entirely.
        s.run()
        assertEquals(1, syncer.pullCount) // no new pull
        assertEquals(1, syncer.pushes.size) // no new push
    }

    @Test
    fun cacheInvalidatedAfterLocalUpdate() = runTest {
        // First run establishes cache at ts=2000.
        val store = FakeLocalToReadStore().also {
            it.setAll(libraryKey, setOf("item1"), 2_000L)
        }
        val syncer = FakeSyncer(
            pullResponse = WebDavPlaylist("toread-books", "To Read", "books", listOf("item1"), 2_000L),
        )
        val s = sweep(syncer = syncer, store = store)
        s.run()
        assertEquals(1, syncer.pullCount) // first pull happened
        assertEquals(0, syncer.pushes.size) // equal ts → no push

        // User adds an item → localTs bumps to 3000.
        store.setAll(libraryKey, setOf("item1", "item2"), 3_000L)

        // Second run: localTs (3000) ≠ cached remote (2000) → pull again, then push.
        s.run()
        assertEquals(2, syncer.pullCount) // cache miss → GET fired
        assertEquals(1, syncer.pushes.size) // local newer → pushed
    }
}
