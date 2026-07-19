package com.riffle.core.data

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogAudioFingerprint
import com.riffle.core.catalog.CatalogAudioTrack
import com.riffle.core.catalog.CatalogAudiobookChapter
import com.riffle.core.catalog.CatalogAudiobookStream
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.common.Clock
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudiobookRepositoryImplTest {

    private fun repo(catalog: Catalog?) = AudiobookRepositoryImpl(
        catalogRegistry = FakeRegistry(catalog),
        clock = object : Clock { override fun nowMs() = 0L; override fun nowNs() = 0L },
    )

    @Test
    fun `openSession maps stream tracks, chapters and timeline`() = runTest {
        val stream = CatalogAudiobookStream(
            trackUrls = listOf(
                "http://host:13378/api/items/it/file/1?token=TKN",
                "http://host:13378/api/items/it/file/2?token=TKN",
            ),
            tracks = listOf(
                CatalogAudioTrack(ino = "1", index = 0, startOffsetSec = 0.0, durationSec = 100.0, contentUrl = ""),
                CatalogAudioTrack(ino = "2", index = 1, startOffsetSec = 100.0, durationSec = 200.0, contentUrl = ""),
            ),
            chapters = listOf(
                CatalogAudiobookChapter(0, 0.0, 100.0, "One"),
                CatalogAudiobookChapter(1, 100.0, 300.0, "Two"),
            ),
            totalDurationSec = 300.0,
            serverCurrentTimeSec = 42.0,
            serverLastUpdate = 1_700_000_000_000L,
        )

        val s = repo(FakeAudioCatalog(stream)).openSession("srv", "it")!!

        assertEquals(
            listOf(
                "http://host:13378/api/items/it/file/1?token=TKN",
                "http://host:13378/api/items/it/file/2?token=TKN",
            ),
            s.trackUrls,
        )
        assertEquals(2, s.tracks.size)
        assertEquals(100.0, s.tracks[1].startOffsetSec, 0.0)
        assertEquals(300.0, s.timeline.durationSec, 0.0)
        assertEquals(listOf("One", "Two"), s.timeline.chapters.map { it.title })
        assertEquals(42.0, s.serverCurrentTimeSec, 0.0)
        assertEquals(1_700_000_000_000L, s.serverLastUpdate)
    }

    @Test
    fun `openSession returns null when the stream cannot be opened`() = runTest {
        assertNull(repo(FakeAudioCatalog(stream = null)).openSession("srv", "it"))
    }

    @Test
    fun `openSession returns null when there is no catalog for the source`() = runTest {
        assertNull(repo(catalog = null).openSession("srv", "it"))
    }

    @Test
    fun `openSession returns null when the catalog has no audiobook capability`() = runTest {
        assertNull(repo(PlainCatalog).openSession("srv", "it"))
    }

    private class FakeRegistry(private val catalog: Catalog?) : CatalogRegistry {
        override suspend fun forActive(): Catalog? = catalog
        override suspend fun forSource(source: Source): Catalog? = catalog
        override suspend fun forSourceId(sourceId: String): Catalog? = catalog
    }

    private class FakeAudioCatalog(private val stream: CatalogAudiobookStream?) : Catalog, AudiobookMediaCapability {
        override val sourceType = SourceType.ABS
        override suspend fun listRoots() = emptyList<CatalogRoot>()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?) = emptyList<CatalogItem>()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int) = emptyList<CatalogItem>()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = throw UnsupportedOperationException()
        override suspend fun openFile(itemId: String, format: BookFormat, handleHint: String?): CatalogFileStream = throw UnsupportedOperationException()
        override suspend fun connectivityCheck() = CatalogHealth(isReachable = true)
        override suspend fun getTracks(itemId: String): List<CatalogAudioTrack> = emptyList()
        override suspend fun getFingerprint(itemId: String): CatalogAudioFingerprint? = CatalogAudioFingerprint(itemId, 0L, 0.0, emptyList())
        override fun buildStreamUrl(itemId: String, trackIno: String) = ""
        override suspend fun openAudiobook(itemId: String, deviceLabel: String) = stream
        override suspend fun getAudiobookChapters(itemId: String) = emptyList<CatalogAudiobookChapter>()
    }

    /** A Catalog without AudiobookMediaCapability. */
    private object PlainCatalog : Catalog {
        override val sourceType = SourceType.ABS
        override suspend fun listRoots() = emptyList<CatalogRoot>()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?) = emptyList<CatalogItem>()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int) = emptyList<CatalogItem>()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = throw UnsupportedOperationException()
        override suspend fun openFile(itemId: String, format: BookFormat, handleHint: String?): CatalogFileStream = throw UnsupportedOperationException()
        override suspend fun connectivityCheck() = CatalogHealth(isReachable = true)
    }
}
