package com.riffle.core.database.dao

import com.riffle.core.database.CoverGridScaleEntity
import com.riffle.core.database.LocalFileMetadataOverrideEntity
import com.riffle.core.database.PublicationMetricsCacheEntity
import com.riffle.core.database.RemoteItemFreshnessEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Behavioural coverage for the local-file, freshness, metrics and cover-grid DAOs on iOS
 * (`IosLocalFileMetadataOverrideDao`, `IosRemoteItemFreshnessDao`, `IosPublicationMetricsCacheDao`,
 * `IosCoverGridScaleDao`).
 *
 * Every one of these tables is dominated by nullable columns or composite keys, which is exactly
 * where the hand-written iOS cursor mapping can silently diverge from Room's generated mapping —
 * a null read back as `0` or `""` looks like a value the user set.
 */
class IosLocalAndCacheDaoTest : IosDaoTestBase() {

    private val bucket = "Compact_Medium"
    private val otherBucket = "Expanded_Medium"
    private val libraryId = "lib-1"
    private val otherLibraryId = "lib-2"

    // ── IosLocalFileMetadataOverrideDao ───────────────────────────────────────

    @Test
    fun localFileMetadataOverrideRoundTripsAllNullFields() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.localFileMetadataOverrideDao()
        // Every override field is nullable and null means "use whatever the scanner extracted".
        // A null that reads back as "" would permanently blank the scanner's value in the UI.
        val entity = LocalFileMetadataOverrideEntity(
            sourceId = SOURCE_ID,
            sourceItemId = ITEM_ID,
            title = null,
            author = null,
            seriesName = null,
            seriesIndex = null,
            coverUrl = null,
        )

        dao.upsert(entity)

        val stored = dao.getForItem(SOURCE_ID, ITEM_ID)
        assertNotNull(stored, "Row must be readable after upsert")
        assertEquals(entity, stored)
        assertNull(stored.title)
        assertNull(stored.author)
        assertNull(stored.seriesName)
        assertNull(stored.seriesIndex, "A null seriesIndex must not be coerced to 0.0")
        assertNull(stored.coverUrl)
    }

    @Test
    fun localFileMetadataOverridePersistsAPartialOverride() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.localFileMetadataOverrideDao()

        dao.upsert(
            LocalFileMetadataOverrideEntity(
                sourceId = SOURCE_ID,
                sourceItemId = ITEM_ID,
                title = "Corrected Title",
                author = null,
                seriesName = "A Series",
                seriesIndex = 2.5,
                coverUrl = "file:///covers/1.jpg",
            ),
        )

        val stored = dao.getForItem(SOURCE_ID, ITEM_ID)
        assertNotNull(stored)
        assertEquals("Corrected Title", stored.title)
        assertNull(stored.author, "Overriding the title must not blank the untouched author")
        assertEquals(2.5, stored.seriesIndex)
        assertEquals("file:///covers/1.jpg", stored.coverUrl)
    }

    @Test
    fun localFileMetadataOverrideGetForItemsReturnsEmptyForAnEmptyIdList() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.localFileMetadataOverrideDao()
        dao.upsert(LocalFileMetadataOverrideEntity(SOURCE_ID, ITEM_ID, "T", null, null, null))

        // The DAO builds its IN-clause placeholders from the list. Without the early return an
        // empty list produces `IN ()`, which is a SQLite syntax error, not an empty result.
        assertEquals(emptyList<LocalFileMetadataOverrideEntity>(), dao.getForItems(SOURCE_ID, emptyList()))
    }

    @Test
    fun localFileMetadataOverrideGetForItemsIsScopedToItsSource() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.localFileMetadataOverrideDao()
        dao.upsert(LocalFileMetadataOverrideEntity(SOURCE_ID, ITEM_ID, "mine", null, null, null))
        dao.upsert(LocalFileMetadataOverrideEntity(OTHER_SOURCE_ID, ITEM_ID, "theirs", null, null, null))

        val rows = dao.getForItems(SOURCE_ID, listOf(ITEM_ID, OTHER_ITEM_ID))

        assertEquals(
            listOf("mine"),
            rows.map { it.title },
            "Two local-file sources may use the same item id; the batch read must not cross them",
        )
    }

    @Test
    fun localFileMetadataOverrideDeleteLeavesOtherItemsIntact() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.localFileMetadataOverrideDao()
        dao.upsert(LocalFileMetadataOverrideEntity(SOURCE_ID, ITEM_ID, "one", null, null, null))
        dao.upsert(LocalFileMetadataOverrideEntity(SOURCE_ID, OTHER_ITEM_ID, "two", null, null, null))

        dao.delete(SOURCE_ID, ITEM_ID)

        assertNull(dao.getForItem(SOURCE_ID, ITEM_ID))
        assertEquals("two", dao.getForItem(SOURCE_ID, OTHER_ITEM_ID)?.title)
    }

    @Test
    fun localFileMetadataOverrideObserveEmitsAgainOnWriteAndOnDelete() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.localFileMetadataOverrideDao()
        val emissions = recordEmissions(dao.observe(SOURCE_ID, ITEM_ID))
        assertEquals(1, emissions.size, "A new subscriber must get the current contents immediately")
        assertNull(emissions.last(), "An item with no override must observe as null")

        dao.upsert(LocalFileMetadataOverrideEntity(SOURCE_ID, ITEM_ID, "edited", null, null, null))
        settleEmissions()
        assertEquals(2, emissions.size, "Saving an override must refresh the open detail screen")
        assertEquals("edited", emissions.last()?.title)

        dao.delete(SOURCE_ID, ITEM_ID)
        settleEmissions()
        assertEquals(3, emissions.size, "Reverting an override must refresh the open detail screen")
        assertNull(emissions.last())
    }

    // ── IosRemoteItemFreshnessDao ─────────────────────────────────────────────

    @Test
    fun remoteItemFreshnessLastFetchedAtIsNullForAnUntrackedItem() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.remoteItemFreshnessDao()
        dao.upsert(RemoteItemFreshnessEntity(SOURCE_ID, ITEM_ID, 1_000L))

        // ADR 0052: a missing stamp is "never fetched", which must force a refetch rather than
        // reading as epoch-zero and looking like an ancient-but-known fetch.
        assertNull(dao.lastFetchedAt(SOURCE_ID, OTHER_ITEM_ID))
    }

    @Test
    fun remoteItemFreshnessUpsertMovesTheStampForwardForTheSameItem() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.remoteItemFreshnessDao()
        dao.upsert(RemoteItemFreshnessEntity(SOURCE_ID, ITEM_ID, 1_000L))

        dao.upsert(RemoteItemFreshnessEntity(SOURCE_ID, ITEM_ID, 2_000L))

        assertEquals(2_000L, dao.lastFetchedAt(SOURCE_ID, ITEM_ID))
    }

    @Test
    fun remoteItemFreshnessClearLeavesOtherItemsIntact() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.remoteItemFreshnessDao()
        dao.upsert(RemoteItemFreshnessEntity(SOURCE_ID, ITEM_ID, 1_000L))
        dao.upsert(RemoteItemFreshnessEntity(SOURCE_ID, OTHER_ITEM_ID, 2_000L))

        dao.clear(SOURCE_ID, ITEM_ID)

        assertNull(dao.lastFetchedAt(SOURCE_ID, ITEM_ID))
        assertEquals(
            2_000L,
            dao.lastFetchedAt(SOURCE_ID, OTHER_ITEM_ID),
            "Expiring one item must not force a refetch of the whole source",
        )
    }

    // ── IosPublicationMetricsCacheDao ─────────────────────────────────────────

    @Test
    fun publicationMetricsCacheRoundTripsNullCountsAndEpubVersion() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.publicationMetricsCacheDao()
        val entity = PublicationMetricsCacheEntity(
            sourceId = SOURCE_ID,
            itemId = ITEM_ID,
            ebookFileIno = "ino-1",
            totalPositions = null,
            pageCount = null,
            cachedAt = 1_000L,
            epubVersion = null,
        )

        dao.upsert(entity)

        val stored = dao.get(SOURCE_ID, ITEM_ID)
        assertNotNull(stored, "Row must be readable after upsert")
        assertEquals(entity, stored)
        assertNull(stored.totalPositions, "An unknown position count must not read back as 0")
        assertNull(stored.pageCount, "An unknown page count must not read back as 0")
        assertNull(stored.epubVersion)
    }

    @Test
    fun publicationMetricsCacheUpsertReplacesTheRowForTheSameItem() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.publicationMetricsCacheDao()
        dao.upsert(PublicationMetricsCacheEntity(SOURCE_ID, ITEM_ID, "ino-1", 100, 40, 1_000L, "3.0"))

        dao.upsert(PublicationMetricsCacheEntity(SOURCE_ID, ITEM_ID, "ino-2", 500, 200, 2_000L, "2.0"))

        val stored = dao.get(SOURCE_ID, ITEM_ID)
        assertNotNull(stored)
        assertEquals("ino-2", stored.ebookFileIno, "A replaced EPUB file must replace its cached metrics")
        assertEquals(500, stored.totalPositions)
        assertEquals(200, stored.pageCount)
        assertEquals("2.0", stored.epubVersion)
    }

    @Test
    fun publicationMetricsCacheRowsAreRemovedByTheSourceDeleteGraph() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.publicationMetricsCacheDao()
        dao.upsert(PublicationMetricsCacheEntity(SOURCE_ID, ITEM_ID, "ino-1", 100, 40, 1_000L, null))
        dao.upsert(PublicationMetricsCacheEntity(OTHER_SOURCE_ID, ITEM_ID, "ino-2", 100, 40, 1_000L, null))

        db.sourceDao().deleteSourceGraph(SOURCE_ID)

        assertNull(dao.get(SOURCE_ID, ITEM_ID), "Removing a source must drop its cached metrics")
        assertNotNull(dao.get(OTHER_SOURCE_ID, ITEM_ID), "Another source's cache must survive")
    }

    // ── IosCoverGridScaleDao ──────────────────────────────────────────────────

    @Test
    fun coverGridScaleIsKeyedByLibraryAndScreenBucketTogether() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.coverGridScaleDao()
        dao.upsert(CoverGridScaleEntity(SOURCE_ID, libraryId, bucket, 1.4f))
        dao.upsert(CoverGridScaleEntity(SOURCE_ID, otherLibraryId, bucket, 0.8f))
        dao.upsert(CoverGridScaleEntity(SOURCE_ID, libraryId, otherBucket, 1.1f))

        assertEquals(1.4f, dao.observeScale(SOURCE_ID, libraryId, bucket).first())
        assertEquals(
            0.8f,
            dao.observeScale(SOURCE_ID, otherLibraryId, bucket).first(),
            "Zooming one library must not zoom its sibling library on the same screen size",
        )
        assertEquals(
            1.1f,
            dao.observeScale(SOURCE_ID, libraryId, otherBucket).first(),
            "Phone and tablet zoom levels for one library are separate rows (ADR 0029)",
        )
    }

    @Test
    fun coverGridScaleUpsertReplacesTheScaleForTheSameKey() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.coverGridScaleDao()
        dao.upsert(CoverGridScaleEntity(SOURCE_ID, libraryId, bucket, 1.4f))

        dao.upsert(CoverGridScaleEntity(SOURCE_ID, libraryId, bucket, 0.7f))

        assertEquals(0.7f, dao.observeScale(SOURCE_ID, libraryId, bucket).first())
    }

    @Test
    fun coverGridScaleObserveEmitsAgainOnWrite() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.coverGridScaleDao()
        val emissions = recordEmissions(dao.observeScale(SOURCE_ID, libraryId, bucket))
        assertEquals(1, emissions.size, "A new subscriber must get the current contents immediately")
        assertNull(emissions.last(), "A library the user never pinched must observe as null")

        dao.upsert(CoverGridScaleEntity(SOURCE_ID, libraryId, bucket, 1.6f))
        settleEmissions()

        assertEquals(2, emissions.size, "A pinch must resize the open grid without a re-subscribe")
        assertEquals(1.6f, emissions.last())
    }

    @Test
    fun coverGridScaleObserveIsScopedToItsSource() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.coverGridScaleDao()
        dao.upsert(CoverGridScaleEntity(SOURCE_ID, libraryId, bucket, 1.4f))

        assertNull(
            dao.observeScale(OTHER_SOURCE_ID, libraryId, bucket).first(),
            "Two sources may use the same library id; their zoom levels must not cross",
        )
    }

    @Test
    fun coverGridScaleRowsAreRemovedByTheSourceDeleteGraph() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.coverGridScaleDao()
        dao.upsert(CoverGridScaleEntity(SOURCE_ID, libraryId, bucket, 1.4f))
        dao.upsert(CoverGridScaleEntity(OTHER_SOURCE_ID, libraryId, bucket, 0.9f))

        db.sourceDao().deleteSourceGraph(SOURCE_ID)

        // Android leans on CoverGridScaleEntity's ON DELETE CASCADE, but the iOS driver runs with
        // foreign-key enforcement off, so this row outlived its source: re-adding a source with
        // the same id silently resurrected its old pinch-zoom, and every add/remove cycle leaked
        // another row. deleteSourceGraph now deletes it explicitly.
        assertNull(
            dao.observeScale(SOURCE_ID, libraryId, bucket).first(),
            "Removing a source must drop its cover-grid zoom, not leave it to be inherited",
        )
        assertEquals(
            0.9f,
            dao.observeScale(OTHER_SOURCE_ID, libraryId, bucket).first(),
            "Another source's zoom must survive",
        )
    }
}
