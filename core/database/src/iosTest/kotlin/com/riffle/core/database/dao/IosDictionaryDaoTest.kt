package com.riffle.core.database.dao

import com.riffle.core.database.DictionaryPackEntity
import com.riffle.core.database.LookupHistoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Behavioural coverage for the dictionary DAOs on iOS (`IosDictionaryPackDao`,
 * `IosLookupHistoryDao`).
 *
 * `state` is stored as opaque TEXT: the enum it mirrors (`DictionaryPackState`) lives in
 * `core:dictionary`, which `core:database` deliberately does not depend on, so the values below
 * are fixtures for a column the DAO never interprets — the assertions are about persistence and
 * the scope of each statement, never about a particular state's meaning.
 */
class IosDictionaryDaoTest : IosDaoTestBase() {

    private fun pack(
        languageTag: String,
        packVersion: String = "1.0",
        installedAt: Long = 1_000L,
        sizeBytes: Long = 2_048L,
        state: String = "INSTALLED",
    ) = DictionaryPackEntity(
        languageTag = languageTag,
        packVersion = packVersion,
        installedAt = installedAt,
        sizeBytes = sizeBytes,
        attributionHtml = "<p>$languageTag attribution</p>",
        licenseUrl = "https://example.invalid/$languageTag/license",
        state = state,
    )

    // ── IosDictionaryPackDao ──────────────────────────────────────────────────

    @Test
    fun dictionaryPackObserveForLanguageIsScopedToItsLanguageTag() = runTest {
        val dao = db.dictionaryPackDao()
        dao.upsert(pack("en"))
        dao.upsert(pack("de"))

        assertEquals("en", dao.observeForLanguage("en").first()?.languageTag)
        assertEquals("de", dao.observeForLanguage("de").first()?.languageTag)
        assertNull(dao.observeForLanguage("fr").first(), "An uninstalled language must observe as null")
    }

    @Test
    fun dictionaryPackObserveAllReturnsEveryInstalledPack() = runTest {
        val dao = db.dictionaryPackDao()
        dao.upsert(pack("en"))
        dao.upsert(pack("de"))

        assertEquals(
            listOf("de", "en"),
            dao.observeAll().first().map { it.languageTag }.sorted(),
        )
    }

    @Test
    fun dictionaryPackUpdateStateLeavesEveryOtherColumnIntact() = runTest {
        val dao = db.dictionaryPackDao()
        dao.upsert(pack("en", packVersion = "2.1", installedAt = 5_000L, sizeBytes = 9_999L))

        dao.updateState("en", "FAILED")

        val stored = dao.observeForLanguage("en").first()
        assertNotNull(stored, "Row must survive a state update")
        assertEquals("FAILED", stored.state)
        // The DAO issues a targeted UPDATE rather than a read-modify-upsert; if that ever became
        // an upsert built from a partial entity these columns would be silently reset.
        assertEquals("2.1", stored.packVersion)
        assertEquals(5_000L, stored.installedAt)
        assertEquals(9_999L, stored.sizeBytes)
        assertEquals("<p>en attribution</p>", stored.attributionHtml)
        assertEquals("https://example.invalid/en/license", stored.licenseUrl)
    }

    @Test
    fun dictionaryPackUpdateStateIsScopedToItsLanguageTag() = runTest {
        val dao = db.dictionaryPackDao()
        dao.upsert(pack("en"))
        dao.upsert(pack("de"))

        dao.updateState("en", "FAILED")

        assertEquals("INSTALLED", dao.observeForLanguage("de").first()?.state)
    }

    @Test
    fun dictionaryPackDeleteLeavesOtherLanguagesIntact() = runTest {
        val dao = db.dictionaryPackDao()
        dao.upsert(pack("en"))
        dao.upsert(pack("de"))

        dao.delete("en")

        assertNull(dao.observeForLanguage("en").first())
        assertEquals(
            listOf("de"),
            dao.observeAll().first().map { it.languageTag },
            "Uninstalling one pack must not uninstall the rest",
        )
    }

    @Test
    fun dictionaryPackUpsertReplacesTheRowForTheSameLanguageTag() = runTest {
        val dao = db.dictionaryPackDao()
        dao.upsert(pack("en", packVersion = "1.0", sizeBytes = 100L))

        dao.upsert(pack("en", packVersion = "2.0", sizeBytes = 200L))

        assertEquals(1, dao.observeAll().first().size, "Re-installing a language must not duplicate its row")
        val stored = dao.observeForLanguage("en").first()
        assertNotNull(stored)
        assertEquals("2.0", stored.packVersion)
        assertEquals(200L, stored.sizeBytes)
    }

    @Test
    fun dictionaryPackObserveAllEmitsAgainOnInstallStateChangeAndDelete() = runTest {
        val dao = db.dictionaryPackDao()
        val emissions = recordEmissions(dao.observeAll())
        assertEquals(1, emissions.size, "A new subscriber must get the current contents immediately")
        assertEquals(emptyList<DictionaryPackEntity>(), emissions.last())

        dao.upsert(pack("en", state = "DOWNLOADING"))
        settleEmissions()
        assertEquals(2, emissions.size, "An install must refresh the open dictionary settings screen")

        dao.updateState("en", "INSTALLED")
        settleEmissions()
        assertEquals(3, emissions.size, "A download completing must refresh the open screen")
        assertEquals("INSTALLED", emissions.last().single().state)

        dao.delete("en")
        settleEmissions()
        assertEquals(4, emissions.size, "An uninstall must refresh the open screen")
        assertEquals(emptyList<DictionaryPackEntity>(), emissions.last())
    }

    @Test
    fun dictionaryPackObserveForLanguageEmitsAgainOnStateChange() = runTest {
        val dao = db.dictionaryPackDao()
        dao.upsert(pack("en", state = "DOWNLOADING"))
        val emissions = recordEmissions(dao.observeForLanguage("en"))
        assertEquals(1, emissions.size)
        assertEquals("DOWNLOADING", emissions.last()?.state)

        dao.updateState("en", "INSTALLED")
        settleEmissions()

        assertEquals(2, emissions.size)
        assertEquals("INSTALLED", emissions.last()?.state)
    }

    // ── IosLookupHistoryDao ───────────────────────────────────────────────────

    @Test
    fun lookupHistoryObserveRecentOrdersByMostRecentAndHonoursTheLimit() = runTest {
        val dao = db.lookupHistoryDao()
        dao.insert(LookupHistoryEntity(languageTag = "en", form = "oldest", lookedUpAt = 1_000L))
        dao.insert(LookupHistoryEntity(languageTag = "en", form = "middle", lookedUpAt = 2_000L))
        dao.insert(LookupHistoryEntity(languageTag = "en", form = "newest", lookedUpAt = 3_000L))

        assertEquals(listOf("newest", "middle"), dao.observeRecent("en", limit = 2).first())
        assertEquals(listOf("newest", "middle", "oldest"), dao.observeRecent("en", limit = 10).first())
    }

    @Test
    fun lookupHistoryObserveRecentIsScopedToItsLanguageTag() = runTest {
        val dao = db.lookupHistoryDao()
        dao.insert(LookupHistoryEntity(languageTag = "en", form = "run", lookedUpAt = 1_000L))
        dao.insert(LookupHistoryEntity(languageTag = "de", form = "laufen", lookedUpAt = 2_000L))

        assertEquals(listOf("run"), dao.observeRecent("en", limit = 10).first())
        assertEquals(listOf("laufen"), dao.observeRecent("de", limit = 10).first())
    }

    @Test
    fun lookupHistoryInsertAppendsRatherThanReplacingARepeatedLookup() = runTest {
        val dao = db.lookupHistoryDao()
        // The table's primary key is an autoincrement id, not (languageTag, form): looking the
        // same word up twice records two visits, so the recency ordering stays honest.
        dao.insert(LookupHistoryEntity(languageTag = "en", form = "run", lookedUpAt = 1_000L))
        dao.insert(LookupHistoryEntity(languageTag = "en", form = "run", lookedUpAt = 3_000L))
        dao.insert(LookupHistoryEntity(languageTag = "en", form = "walk", lookedUpAt = 2_000L))

        assertEquals(listOf("run", "walk", "run"), dao.observeRecent("en", limit = 10).first())
    }

    @Test
    fun lookupHistoryPruneOldestKeepsTheFiftyMostRecentEntries() = runTest {
        val dao = db.lookupHistoryDao()
        repeat(55) { index ->
            dao.insert(
                LookupHistoryEntity(
                    languageTag = "en",
                    form = "word-${index.toString().padStart(2, '0')}",
                    lookedUpAt = (index + 1).toLong() * 1_000L,
                ),
            )
        }

        dao.pruneOldest("en")

        val retained = dao.observeRecent("en", limit = 100).first()
        assertEquals(50, retained.size, "pruneOldest must cap the history at 50 entries")
        assertEquals("word-54", retained.first(), "The newest lookup must survive the prune")
        assertEquals("word-05", retained.last(), "Exactly the five oldest lookups must be dropped")
    }

    @Test
    fun lookupHistoryPruneOldestLeavesOtherLanguagesIntact() = runTest {
        val dao = db.lookupHistoryDao()
        repeat(55) { index ->
            dao.insert(
                LookupHistoryEntity(languageTag = "en", form = "en-$index", lookedUpAt = (index + 1).toLong() * 1_000L),
            )
        }
        dao.insert(LookupHistoryEntity(languageTag = "de", form = "laufen", lookedUpAt = 1L))

        dao.pruneOldest("en")

        assertEquals(
            listOf("laufen"),
            dao.observeRecent("de", limit = 100).first(),
            "Pruning one language's history must not touch another's, however old its entries are",
        )
    }

    @Test
    fun lookupHistoryObserveRecentEmitsAgainOnInsertAndOnPrune() = runTest {
        val dao = db.lookupHistoryDao()
        val emissions = recordEmissions(dao.observeRecent("en", limit = 10))
        assertEquals(1, emissions.size, "A new subscriber must get the current contents immediately")
        assertEquals(emptyList<String>(), emissions.last())

        dao.insert(LookupHistoryEntity(languageTag = "en", form = "run", lookedUpAt = 1_000L))
        settleEmissions()
        assertEquals(2, emissions.size, "A lookup must refresh the open recent-lookups list")
        assertEquals(listOf("run"), emissions.last())

        dao.pruneOldest("en")
        settleEmissions()
        assertEquals(3, emissions.size, "A prune must refresh the open recent-lookups list")
        assertEquals(listOf("run"), emissions.last(), "A prune below the cap must keep every entry")
    }
}
