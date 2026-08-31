package com.riffle.core.data.dictionary

import com.riffle.core.common.Clock
import com.riffle.core.database.DictionaryPackDao
import com.riffle.core.database.DictionaryPackEntity
import com.riffle.core.database.LookupHistoryDao
import com.riffle.core.database.LookupHistoryEntity
import com.riffle.core.dictionary.DictionaryEntry
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.InstalledPack
import com.riffle.core.dictionary.PackEntryReader
import com.riffle.core.domain.DispatcherProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val testDispatchers = object : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
}

private val testClock = object : Clock {
    override fun nowMs(): Long = 1000L
    override fun nowNs(): Long = 1_000_000L
}

class WordLookupRepositoryImplTest {

    private val installedEntity = DictionaryPackEntity(
        languageTag = "fr",
        packVersion = "2026-08-01",
        installedAt = 1000L,
        sizeBytes = 100L,
        attributionHtml = "<a>Wiktionary</a>",
        licenseUrl = "https://cc.org",
        state = DictionaryPackState.INSTALLED.name,
    )

    @Test
    fun `lookup returns entries when pack is installed`() = runTest {
        val entries = listOf(DictionaryEntry("chat", "noun", listOf("a cat")))
        val repo = repo(packEntries = entries, packEntity = installedEntity)
        val result = repo.lookup("chat", "fr")
        assertEquals(entries, result)
    }

    @Test
    fun `lookup returns empty list when no reader available`() = runTest {
        val repo = repo(packEntries = emptyList(), packEntity = null)
        val result = repo.lookup("chat", "fr")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `observePackState emits INSTALLED when entity has INSTALLED state`() = runTest {
        val repo = repo(packEntity = installedEntity)
        val state = repo.observePackState("fr").first()
        assertEquals(DictionaryPackState.INSTALLED, state)
    }

    @Test
    fun `observePackState emits NOT_INSTALLED when no entity`() = runTest {
        val repo = repo(packEntity = null)
        val state = repo.observePackState("fr").first()
        assertEquals(DictionaryPackState.NOT_INSTALLED, state)
    }

    @Test
    fun `observeInstalledPacks maps INSTALLED entities to InstalledPack`() = runTest {
        val repo = repo(packEntity = installedEntity)
        val packs = repo.observeInstalledPacks().first()
        assertEquals(1, packs.size)
        assertEquals("fr", packs[0].languageTag)
        assertEquals("<a>Wiktionary</a>", packs[0].attributionHtml)
    }

    @Test
    fun `observeInstalledPacks excludes non-INSTALLED entities`() = runTest {
        val downloadingEntity = installedEntity.copy(state = DictionaryPackState.DOWNLOADING.name)
        val repo = repo(packEntity = downloadingEntity)
        val packs = repo.observeInstalledPacks().first()
        assertTrue(packs.isEmpty())
    }

    // --- Helpers ---

    private fun repo(
        packEntries: List<DictionaryEntry> = emptyList(),
        packEntity: DictionaryPackEntity? = null,
    ): WordLookupRepositoryImpl {
        val packDao = FakeDictionaryPackDao(packEntity)
        val historyDao = FakeLookupHistoryDao()
        val sqliteStore = mockk<DictionaryPackSqliteStore>()
        if (packEntity != null && packEntity.state == DictionaryPackState.INSTALLED.name) {
            every { sqliteStore.readerForLanguage(any()) } returns object : PackEntryReader {
                override fun query(form: String): List<DictionaryEntry> = packEntries
            }
        } else {
            every { sqliteStore.readerForLanguage(any()) } returns null
        }
        every { sqliteStore.deletePackFile(any()) } returns Unit
        return WordLookupRepositoryImpl(packDao, historyDao, sqliteStore, testDispatchers, testClock)
    }
}

// --- Fakes ---

private class FakeDictionaryPackDao(private val entity: DictionaryPackEntity?) : DictionaryPackDao {
    private val state = MutableStateFlow(entity)
    override fun observeForLanguage(languageTag: String): Flow<DictionaryPackEntity?> = state
    override fun observeAll(): Flow<List<DictionaryPackEntity>> = flowOf(listOfNotNull(entity))
    override suspend fun upsert(entity: DictionaryPackEntity) { state.value = entity }
    override suspend fun updateState(languageTag: String, state: String) {
        this.state.value = this.state.value?.copy(state = state)
    }
    override suspend fun delete(languageTag: String) { state.value = null }
}

private class FakeLookupHistoryDao : LookupHistoryDao {
    private val history = mutableListOf<LookupHistoryEntity>()
    override fun observeRecent(languageTag: String, limit: Int): Flow<List<String>> =
        flowOf(history.filter { it.languageTag == languageTag }.takeLast(limit).map { it.form })
    override suspend fun insert(entity: LookupHistoryEntity) { history.add(entity) }
    override suspend fun pruneOldest(languageTag: String) {
        val kept = history.filter { it.languageTag == languageTag }.takeLast(50)
        history.removeIf { it.languageTag == languageTag }
        history.addAll(kept)
    }
}
