package com.riffle.core.data

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.MatchableItemRow
import com.riffle.core.database.ReadaloudCandidateDao
import com.riffle.core.database.ReadaloudCandidateEntity
import com.riffle.core.database.ReadaloudDismissalDao
import com.riffle.core.database.ReadaloudDismissalEntity
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudLinkEntity
import com.riffle.core.domain.AbsFormatFilter
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadaloudReviewRepositoryTest {

    @Test
    fun `confirmCandidate creates a userConfirmed link and clears the book's candidates`() = runTest {
        val links = RecordingLinkDao()
        val candidates = RecordingCandidateDao().apply {
            seed(ReadaloudCandidateEntity("st-1", "42", "abs-1", "pick", 0.9))
            seed(ReadaloudCandidateEntity("st-1", "42", "abs-1", "other", 0.86))
        }
        val repo = repo(links, candidates)

        repo.confirmCandidate("st-1", "42", "abs-1", "pick")

        val link = links.findByAbsItem("abs-1", "pick")!!
        assertEquals("42", link.storytellerBookId)
        assertTrue("confirmed link must be sticky", link.userConfirmed)
        assertTrue("all of the book's candidates are cleared", candidates.rows.isEmpty())
    }

    @Test
    fun `dismissCandidate persists a candidate-scope dismissal and removes only that candidate`() = runTest {
        val candidates = RecordingCandidateDao().apply {
            seed(ReadaloudCandidateEntity("st-1", "42", "abs-1", "a", 0.9))
            seed(ReadaloudCandidateEntity("st-1", "42", "abs-1", "b", 0.86))
        }
        val dismissals = RecordingDismissalDao()
        val repo = repo(RecordingLinkDao(), candidates, dismissals)

        repo.dismissCandidate("st-1", "42", "abs-1", "a")

        assertEquals(setOf("b"), candidates.rows.map { it.absLibraryItemId }.toSet())
        val d = dismissals.rows.single()
        assertEquals(ReadaloudDismissalEntity.SCOPE_CANDIDATE, d.scope)
        assertEquals("a", d.absLibraryItemId)
    }

    @Test
    fun `dismissBook persists a book-scope dismissal and clears all candidates`() = runTest {
        val candidates = RecordingCandidateDao().apply {
            seed(ReadaloudCandidateEntity("st-1", "42", "abs-1", "a", 0.9))
        }
        val dismissals = RecordingDismissalDao()
        val repo = repo(RecordingLinkDao(), candidates, dismissals)

        repo.dismissBook("st-1", "42")

        assertTrue(candidates.rows.isEmpty())
        assertEquals(ReadaloudDismissalEntity.SCOPE_BOOK, dismissals.rows.single().scope)
        assertTrue(dismissals.isBookDismissed("st-1", "42"))
    }

    @Test
    fun `unlinkBook removes every ABS row paired with the readaloud in one action`() = runTest {
        // Regression: a readaloud links to both an ebook and an audiobook stub. Unlinking the
        // match must detach BOTH so the book returns to Unmatched immediately — not require a
        // second unlink for the audiobook before the "Match manually" button reappears.
        val links = RecordingLinkDao().apply {
            seed(link("abs-1", "ebook", "st-1", "42", userConfirmed = true))
            seed(link("abs-1", "audiobook", "st-1", "42", userConfirmed = true))
        }
        val repo = repo(links, RecordingCandidateDao())

        repo.unlinkBook("st-1", "42")

        assertNull(links.findByAbsItem("abs-1", "ebook"))
        assertNull(links.findByAbsItem("abs-1", "audiobook"))
    }

    @Test
    fun `pairManually can attach several ABS items to one readaloud`() = runTest {
        // A readaloud links to both an ebook and an audiobook. Picking the second must NOT
        // remove the first — the picker stays open so the user can attach more than one.
        val links = RecordingLinkDao()
        val repo = repo(links, RecordingCandidateDao())

        repo.pairManually("st-1", "42", "abs-1", "ebook")
        repo.pairManually("st-1", "42", "abs-1", "audiobook")

        assertEquals(
            setOf("ebook", "audiobook"),
            links.findByStorytellerBook("st-1", "42").map { it.absLibraryItemId }.toSet(),
        )
    }

    @Test
    fun `unlinkAbsItem detaches one ABS item and leaves siblings linked`() = runTest {
        val links = RecordingLinkDao().apply {
            seed(link("abs-1", "ebook", "st-1", "42", userConfirmed = true))
            seed(link("abs-1", "audiobook", "st-1", "42", userConfirmed = true))
        }
        val repo = repo(links, RecordingCandidateDao())

        repo.unlinkAbsItem("abs-1", "ebook")

        assertNull(links.findByAbsItem("abs-1", "ebook"))
        assertEquals(
            setOf("audiobook"),
            links.findByStorytellerBook("st-1", "42").map { it.absLibraryItemId }.toSet(),
        )
    }

    @Test
    fun `pairManually creates a sticky link and clears prior don't-ask-again`() = runTest {
        val links = RecordingLinkDao()
        val candidates = RecordingCandidateDao()
        val dismissals = RecordingDismissalDao().apply {
            store += ReadaloudDismissalEntity("st-1", "42", ReadaloudDismissalEntity.SCOPE_BOOK)
        }
        val repo = repo(links, candidates, dismissals)

        repo.pairManually("st-1", "42", "abs-2", "chosen")

        val link = links.findByAbsItem("abs-2", "chosen")!!
        assertTrue(link.userConfirmed)
        assertTrue("manual pairing overrides 'don't ask again'", !dismissals.isBookDismissed("st-1", "42"))
    }

    @Test
    fun `linking an audiobook migrates the saved speed onto the audiobook id`() = runTest {
        // Speed was set during readaloud (no audiobook yet) → keyed by the Storyteller id.
        val links = RecordingLinkDao()
        val items = RecordingLibraryItemDao().apply { seed(audiobook("abs-1", "audio")) }
        val audio = FakeAudioPlaybackPreferencesStore().apply {
            store[AudioIdentity("st-1", "42")] = 1.5f
        }
        val repo = repo(links, RecordingCandidateDao(), libraryItemDao = items, audioPrefs = audio)

        repo.confirmCandidate("st-1", "42", "abs-1", "audio")

        assertNull(audio.store[AudioIdentity("st-1", "42")])
        assertEquals(1.5f, audio.store[AudioIdentity("abs-1", "audio")])
    }

    @Test
    fun `unlinking the audiobook migrates the speed back to the readaloud id`() = runTest {
        val links = RecordingLinkDao().apply { seed(link("abs-1", "audio", "st-1", "42", userConfirmed = true)) }
        val items = RecordingLibraryItemDao().apply { seed(audiobook("abs-1", "audio")) }
        val audio = FakeAudioPlaybackPreferencesStore().apply {
            store[AudioIdentity("abs-1", "audio")] = 2f
        }
        val repo = repo(links, RecordingCandidateDao(), libraryItemDao = items, audioPrefs = audio)

        repo.unlinkBook("st-1", "42")

        assertNull(audio.store[AudioIdentity("abs-1", "audio")])
        assertEquals(2f, audio.store[AudioIdentity("st-1", "42")])
    }

    @Test
    fun `linking an ebook does not move the speed`() = runTest {
        val links = RecordingLinkDao()
        val items = RecordingLibraryItemDao().apply { seed(ebook("abs-1", "ebook")) }
        val audio = FakeAudioPlaybackPreferencesStore().apply {
            store[AudioIdentity("st-1", "42")] = 1.25f
        }
        val repo = repo(links, RecordingCandidateDao(), libraryItemDao = items, audioPrefs = audio)

        repo.confirmCandidate("st-1", "42", "abs-1", "ebook")

        assertEquals(1.25f, audio.store[AudioIdentity("st-1", "42")])
    }

    // ── format slots: missing-side detection (Confirmed two-slot UI) ─────────

    @Test
    fun `confirmed targets carry ebook and audio flags so a missing side is detectable`() = runTest {
        // An ebook entry + an audiobook stub linked to the same readaloud. Each target must report
        // which slot it fills so the UI can leave the other slot empty when one is absent.
        val items = MatchableLibraryItemDao(
            listOf(absEbook("ebook"), absAudiobook("audio"), storytellerBook("42")),
            absServerIds = setOf("abs"),
            storytellerServerIds = setOf("st"),
        )
        val links = RecordingLinkDao().apply {
            seed(link("abs", "ebook", "st", "42", userConfirmed = true))
            seed(link("abs", "audio", "st", "42", userConfirmed = true))
        }
        val repo = repo(links, RecordingCandidateDao(), libraryItemDao = items)

        val match = repo.observeReview("st").first().confirmed.single { it.storytellerBookId == "42" }

        val ebookTarget = match.targets.single { it.absLibraryItemId == "ebook" }
        assertTrue("ebook entry fills the ebook slot", ebookTarget.hasEbook)
        assertTrue("ebook entry is not audio", !ebookTarget.hasAudio)
        val audioTarget = match.targets.single { it.absLibraryItemId == "audio" }
        assertTrue("audiobook stub fills the audio slot", audioTarget.hasAudio)
        assertTrue("audiobook stub is not an ebook", !audioTarget.hasEbook)
    }

    @Test
    fun `a combined item reports both slots filled`() = runTest {
        val items = MatchableLibraryItemDao(
            listOf(absCombined("both"), storytellerBook("7")),
            absServerIds = setOf("abs"),
            storytellerServerIds = setOf("st"),
        )
        val links = RecordingLinkDao().apply { seed(link("abs", "both", "st", "7", userConfirmed = true)) }
        val repo = repo(links, RecordingCandidateDao(), libraryItemDao = items)

        val target = repo.observeReview("st").first().confirmed.single().targets.single()

        assertTrue(target.hasEbook)
        assertTrue(target.hasAudio)
    }

    @Test
    fun `confirmed matches are alphabetical and isIncomplete partitions them for the UI sections`() = runTest {
        // The screen splits confirmed into "Partially matched" (isIncomplete) and "Matched". The repo
        // returns one alphabetical list; partitioning by isIncomplete must keep each section in order.
        val items = MatchableLibraryItemDao(
            listOf(
                absCombined("alpha"), storytellerBook("a").copy(title = "Alpha"),
                absCombined("mango"), storytellerBook("m").copy(title = "Mango"),
                absAudiobook("zebra"), storytellerBook("z").copy(title = "Zebra"),
            ),
            absServerIds = setOf("abs"),
            storytellerServerIds = setOf("st"),
        )
        val links = RecordingLinkDao().apply {
            seed(link("abs", "alpha", "st", "a", userConfirmed = true))
            seed(link("abs", "mango", "st", "m", userConfirmed = true))
            seed(link("abs", "zebra", "st", "z", userConfirmed = true))
        }
        val repo = repo(links, RecordingCandidateDao(), libraryItemDao = items)

        val confirmed = repo.observeReview("st").first().confirmed

        assertEquals(listOf("Alpha", "Mango", "Zebra"), confirmed.map { it.title })
        val (incomplete, complete) = confirmed.partition { it.isIncomplete }
        assertEquals(listOf("Zebra"), incomplete.map { it.title })
        assertEquals(listOf("Alpha", "Mango"), complete.map { it.title })
    }

    @Test
    fun `searchAbsItems EBOOK filter keeps ebook and combined, drops audio-only`() = runTest {
        val repo = repo(RecordingLinkDao(), RecordingCandidateDao(), libraryItemDao = absSearchDao())

        val result = repo.searchAbsItems("", AbsFormatFilter.EBOOK)

        assertEquals(setOf("ebook", "both"), result.map { it.absLibraryItemId }.toSet())
        assertTrue("every offered item can supply an ebook", result.all { it.hasEbook })
    }

    @Test
    fun `searchAbsItems AUDIO filter keeps audiobook and combined, drops ebook-only`() = runTest {
        val repo = repo(RecordingLinkDao(), RecordingCandidateDao(), libraryItemDao = absSearchDao())

        val result = repo.searchAbsItems("", AbsFormatFilter.AUDIO)

        assertEquals(setOf("audio", "both"), result.map { it.absLibraryItemId }.toSet())
        assertTrue("every offered item can supply audio", result.all { it.hasAudio })
    }

    @Test
    fun `searchAbsItems ANY returns all with correct format flags`() = runTest {
        val repo = repo(RecordingLinkDao(), RecordingCandidateDao(), libraryItemDao = absSearchDao())

        val byId = repo.searchAbsItems("").associateBy { it.absLibraryItemId }

        assertEquals(setOf("ebook", "audio", "both"), byId.keys)
        assertEquals(true to false, byId.getValue("ebook").let { it.hasEbook to it.hasAudio })
        assertEquals(false to true, byId.getValue("audio").let { it.hasEbook to it.hasAudio })
        assertEquals(true to true, byId.getValue("both").let { it.hasEbook to it.hasAudio })
    }

    private fun absSearchDao() = MatchableLibraryItemDao(
        listOf(absEbook("ebook"), absAudiobook("audio"), absCombined("both")),
        absServerIds = setOf("abs"),
        storytellerServerIds = emptySet(),
    )

    private fun absEbook(id: String) =
        LibraryItemEntity("abs", id, "lib", id, "Author", null, 0f, ebookFormat = "epub", hasAudio = false)

    private fun absAudiobook(id: String) =
        LibraryItemEntity("abs", id, "lib", id, "Author", null, 0f, ebookFormat = "unsupported", hasAudio = true)

    private fun absCombined(id: String) =
        LibraryItemEntity("abs", id, "lib", id, "Author", null, 0f, ebookFormat = "epub", hasAudio = true)

    private fun storytellerBook(id: String) =
        LibraryItemEntity("st", id, "lib", "Book $id", "Author", null, 0f)

    /** A [LibraryItemDao] backing both `getById` lookups and `listMatchableByServerType` scans. */
    private class MatchableLibraryItemDao(
        private val items: List<LibraryItemEntity>,
        private val absServerIds: Set<String>,
        private val storytellerServerIds: Set<String>,
    ) : LibraryItemDao by ThrowingLibraryItemDao {
        override suspend fun getById(serverId: String, itemId: String) =
            items.firstOrNull { it.serverId == serverId && it.id == itemId }
        override suspend fun listMatchableByServerType(serverType: String): List<MatchableItemRow> {
            val serverIds = when (serverType) {
                ServerType.AUDIOBOOKSHELF.name -> absServerIds
                ServerType.STORYTELLER.name -> storytellerServerIds
                else -> emptySet()
            }
            return items.filter { it.serverId in serverIds }
                .map { MatchableItemRow(it.id, it.serverId, it.title, it.author, null, null) }
        }
    }

    private fun repo(
        links: RecordingLinkDao,
        candidates: RecordingCandidateDao,
        dismissals: RecordingDismissalDao = RecordingDismissalDao(),
        libraryItemDao: LibraryItemDao = ThrowingLibraryItemDao,
        audioPrefs: FakeAudioPlaybackPreferencesStore = FakeAudioPlaybackPreferencesStore(),
    ) = ReadaloudReviewRepositoryImpl(
        libraryItemDao = libraryItemDao,
        libraryDao = ThrowingLibraryDao,
        linkDao = links,
        candidateDao = candidates,
        dismissalDao = dismissals,
        audioIdentityResolver = AudioIdentityResolverImpl(links, libraryItemDao),
        audioPlaybackPreferencesStore = audioPrefs,
        clock = { 1000L },
    )

    private fun audiobook(serverId: String, id: String) =
        LibraryItemEntity(serverId, id, "lib", "Title", "Author", null, 0f, hasAudio = true)

    private fun ebook(serverId: String, id: String) =
        LibraryItemEntity(serverId, id, "lib", "Title", "Author", null, 0f, hasAudio = false)

    private class RecordingLibraryItemDao : LibraryItemDao by ThrowingLibraryItemDao {
        private val items = mutableMapOf<Pair<String, String>, LibraryItemEntity>()
        fun seed(e: LibraryItemEntity) { items[e.serverId to e.id] = e }
        override suspend fun getById(serverId: String, itemId: String) = items[serverId to itemId]
    }

    private class FakeAudioPlaybackPreferencesStore : AudioPlaybackPreferencesStore {
        val store = mutableMapOf<AudioIdentity, Float>()
        override suspend fun load(identity: AudioIdentity) = store[identity]
        override suspend fun save(identity: AudioIdentity, speed: Float) {
            if (speed == AudioPlaybackPreferencesStore.DEFAULT_PLAYBACK_SPEED) store.remove(identity) else store[identity] = speed
        }
        override suspend fun clear(identity: AudioIdentity) { store.remove(identity) }
        override suspend fun rekey(old: AudioIdentity, new: AudioIdentity) {
            val v = store.remove(old) ?: return
            store[if (old == new) old else new] = v
        }
    }

    private fun link(
        absServerId: String,
        absLibraryItemId: String,
        storytellerServerId: String,
        storytellerBookId: String,
        userConfirmed: Boolean,
    ) = ReadaloudLinkEntity(
        absServerId, absLibraryItemId, storytellerServerId, storytellerBookId,
        ReadaloudLinkEntity.STATE_CONFIRMED, userConfirmed, 1L, 1L,
    )

    private class RecordingLinkDao : ReadaloudLinkDao {
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

    private class RecordingCandidateDao : ReadaloudCandidateDao {
        private val store = mutableListOf<ReadaloudCandidateEntity>()
        val rows: List<ReadaloudCandidateEntity> get() = store.toList()
        fun seed(e: ReadaloudCandidateEntity) { store += e }
        override suspend fun upsert(entity: ReadaloudCandidateEntity) { store += entity }
        override suspend fun upsertAll(entities: List<ReadaloudCandidateEntity>) { store += entities }
        override suspend fun allRows() = store.toList()
        override suspend fun clearAll() { store.clear() }
        override fun observeAll(): Flow<List<ReadaloudCandidateEntity>> = flowOf(store.toList())
        override fun observeForStorytellerServer(storytellerServerId: String): Flow<List<ReadaloudCandidateEntity>> =
            flowOf(store.filter { it.storytellerServerId == storytellerServerId })
        override suspend fun deleteByStorytellerBook(storytellerServerId: String, storytellerBookId: String) {
            store.removeAll { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId }
        }
        override suspend fun deleteCandidate(storytellerServerId: String, storytellerBookId: String, absServerId: String, absLibraryItemId: String) {
            store.removeAll {
                it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId &&
                    it.absServerId == absServerId && it.absLibraryItemId == absLibraryItemId
            }
        }
    }

    private class RecordingDismissalDao : ReadaloudDismissalDao {
        val store = mutableListOf<ReadaloudDismissalEntity>()
        val rows: List<ReadaloudDismissalEntity> get() = store.toList()
        override suspend fun upsert(entity: ReadaloudDismissalEntity) { store += entity }
        override suspend fun allRows() = store.toList()
        override fun observeAll(): Flow<List<ReadaloudDismissalEntity>> = flowOf(store.toList())
        override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String) =
            store.filter { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId }
        override suspend fun isBookDismissed(storytellerServerId: String, storytellerBookId: String) =
            store.any { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId && it.scope == ReadaloudDismissalEntity.SCOPE_BOOK }
        override suspend fun clearBookDismissal(storytellerServerId: String, storytellerBookId: String) {
            store.removeAll { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId && it.scope == ReadaloudDismissalEntity.SCOPE_BOOK }
        }
    }
}
