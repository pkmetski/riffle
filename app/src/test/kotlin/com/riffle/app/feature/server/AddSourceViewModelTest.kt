package com.riffle.app.feature.server

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.data.AnnotationSyncStatusStore
import com.riffle.core.data.CycleOutcome
import com.riffle.core.data.WebDavAnnotationSyncTargetFactory
import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.domain.Library
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddSourceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeServer() = Source(
        id = "s1",
        url = SourceUrl.parse("https://abs.example.com")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
    )

    private fun fakePending(
        libraries: List<Library> = listOf(
            Library("lib-1", "Books", "book", false),
            Library("lib-2", "Comics", "book", false),
        ),
    ) = PendingSource(
        url = SourceUrl.parse("https://abs.example.com")!!,
        username = "admin",
        userId = "uid",
        token = "tok",
        password = "",
        insecureConnectionAllowed = false,
        libraries = libraries,
    )

    private fun singleLibraryPending() =
        fakePending(libraries = listOf(Library("lib-1", "Books", "book", false)))

    private class RecordingRepository(
        private val authResult: AuthenticateResult,
        private val commitResult: CommitSourceResult = CommitSourceResult.Success(
            Source(
                id = "s1",
                url = SourceUrl.parse("https://abs.example.com")!!,
                isActive = true,
                insecureConnectionAllowed = false,
                username = "",
            )
        ),
        private val storedById: Map<String, Source> = emptyMap(),
    ) : SourceRepository {
        var commitCallCount = 0
        var lastInsecureAllowed: Boolean? = null
        var lastServerType: com.riffle.core.domain.ServerType? = null
        val removedIds = mutableListOf<String>()
        override fun observeAll(): Flow<List<Source>> = emptyFlow()
        override suspend fun getActive(): Source? = null
        override suspend fun getById(sourceId: String): Source? = storedById[sourceId]
        override suspend fun authenticate(
            url: SourceUrl,
            username: String,
            password: String,
            insecureAllowed: Boolean,
            serverType: com.riffle.core.domain.ServerType,
            sourceType: com.riffle.core.domain.SourceType,
        ): AuthenticateResult {
            lastInsecureAllowed = insecureAllowed
            lastServerType = serverType
            return authResult
        }
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult {
            commitCallCount += 1
            return commitResult
        }
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) { removedIds += sourceId }
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private class RecordingConfigStore(
        initial: AnnotationSyncConfig? = null,
    ) : AnnotationSyncConfigStore {
        private val state = MutableStateFlow(initial)
        var saved: AnnotationSyncConfig? = null
        var clearCount = 0
        override fun observe(): StateFlow<AnnotationSyncConfig?> = state
        override suspend fun save(config: AnnotationSyncConfig) { saved = config; state.value = config }
        override suspend fun clear() { clearCount += 1; state.value = null }
    }

    private class CountingTokenStorage(
        private val passwords: MutableMap<String, String> = mutableMapOf(),
    ) : com.riffle.core.domain.TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String? = null
        override suspend fun deleteToken(sourceId: String) {}
        override suspend fun savePassword(sourceId: String, password: String) { passwords[sourceId] = password }
        override suspend fun getPassword(sourceId: String): String? = passwords[sourceId]
        override suspend fun deletePassword(sourceId: String) { passwords.remove(sourceId) }
    }

    private fun fakeRepo(authResult: AuthenticateResult): SourceRepository =
        RecordingRepository(authResult)

    private object NullConfigStore : AnnotationSyncConfigStore {
        override fun observe(): StateFlow<AnnotationSyncConfig?> = MutableStateFlow(null)
        override suspend fun save(config: AnnotationSyncConfig) {}
        override suspend fun clear() {}
    }

    private class MutableClock(var nowMs: Long = 0L) : com.riffle.core.domain.Clock {
        override fun nowMs(): Long = nowMs
        override fun nowNs(): Long = nowMs * 1_000_000L
    }

    private fun makeVm(
        repository: SourceRepository,
        savedState: SavedStateHandle = SavedStateHandle(),
        configStore: AnnotationSyncConfigStore = NullConfigStore,
        tokenStorage: com.riffle.core.domain.TokenStorage = io.mockk.mockk(relaxed = true),
        statusStore: AnnotationSyncStatusStore = AnnotationSyncStatusStore(),
        clock: com.riffle.core.domain.Clock = MutableClock(),
        annotationDao: AnnotationDao = stubAnnotationDao(pendingBookCount = 0),
        bannerTicker: Flow<Unit> = flowOf(Unit),
    ): AddSourceViewModel = AddSourceViewModel(
        repository = repository,
        webdavConfigStore = configStore,
        webdavTargetFactory = WebDavAnnotationSyncTargetFactory(OkHttpClient(), com.riffle.core.domain.DefaultDispatcherProvider),
        webdavStatusStore = statusStore,
        sweepEnqueuer = AnnotationSweepEnqueuer { },
        storytellerSyncer = io.mockk.mockk(relaxed = true),
        readaloudMatcher = io.mockk.mockk(relaxed = true),
        tokenStorage = tokenStorage,
        clock = clock,
        annotationDao = annotationDao,
        bannerTicker = bannerTicker,
        savedStateHandle = savedState,
    )

    private fun stubAnnotationDao(pendingBookCount: Int): AnnotationDao = object : AnnotationDao {
        override fun observeForItem(sourceId: String, itemId: String) = flowOf(emptyList<AnnotationEntity>())
        override fun observeForSource(sourceId: String) = flowOf(emptyList<AnnotationEntity>())
        override suspend fun getForItem(sourceId: String, itemId: String) = emptyList<AnnotationEntity>()
        override suspend fun getAllForItemIncludingDeleted(sourceId: String, itemId: String) = emptyList<AnnotationEntity>()
        override suspend fun getById(id: String): AnnotationEntity? = null
        override suspend fun getByItemAndCfi(sourceId: String, itemId: String, cfi: String): AnnotationEntity? = null
        override suspend fun findImageForFigure(
            sourceId: String,
            itemId: String,
            chapterHref: String,
            imageHref: String?,
            imageSvg: String?,
        ): AnnotationEntity? = null
        override suspend fun upsert(entity: AnnotationEntity) {}
        override suspend fun upsertAll(annotations: List<AnnotationEntity>) {}
        override suspend fun tombstone(id: String, updatedAt: Long, deviceId: String) {}
        override suspend fun recolor(id: String, color: String, updatedAt: Long, deviceId: String) {}
        override suspend fun updateNote(id: String, note: String?, updatedAt: Long, deviceId: String) {}
        override fun observeAnnotationsByPosition(sourceId: String, itemId: String) = flowOf(emptyList<AnnotationEntity>())
        override suspend fun renameBookmark(id: String, title: String, updatedAt: Long, deviceId: String) {}
        override fun observePendingCountForBook(sourceId: String, itemId: String) = flowOf(0)
        override fun observePendingBookCountAcrossAll() = flowOf(pendingBookCount)
        override suspend fun dirtySourceItems() = emptyList<AnnotationDao.DirtySourceItem>()
        override suspend fun markSynced(ids: List<String>, syncedAt: Long) {}
        override suspend fun purgeAgedTombstones(sourceId: String, itemId: String, cutoff: Long): Int = 0
        override suspend fun backfillNullOriginFontFamily(
            sourceId: String,
            itemId: String,
            fontFamily: String,
            updatedAt: Long,
            deviceId: String,
        ): Int = 0
        override fun observeBooksWithHighlights(sourceId: String) =
            flowOf(emptyList<com.riffle.core.database.BookHighlightSummary>())
    }

    @Test
    fun `updateHost auto-splits a pasted http url into scheme and host`() {
        val vm = makeVm(fakeRepo(AuthenticateResult.Success(fakePending())))
        vm.updateHost("http://abs.example.com")
        assertEquals("http://", vm.scheme)
        assertEquals("abs.example.com", vm.host)
    }

    @Test
    fun `updateScheme ignores values that are not http or https`() {
        val vm = makeVm(fakeRepo(AuthenticateResult.Success(fakePending())))
        val before = vm.scheme
        vm.updateScheme("ftp://")
        assertEquals(before, vm.scheme)
    }

    @Test
    fun `onConnect with http url shows insecure warning`() = runTest {
        val vm = makeVm(fakeRepo(AuthenticateResult.Success(fakePending())))
        vm.updateScheme("http://")
        vm.updateHost("abs.example.com")
        vm.username = "admin"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(InsecureConnectionType.HTTP, vm.insecureWarning)
        assertNull(vm.error)
    }

    @Test
    fun `onConnect success emits navigateToSelectLibraries with pending server and does not commit`() = runTest {
        val pending = fakePending()
        val repo = RecordingRepository(AuthenticateResult.Success(pending))
        val vm = makeVm(repo)
        vm.updateScheme("https://"); vm.updateHost("abs.example.com")
        vm.username = "admin"
        vm.password = "pass"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()
        val emitted = vm.navigateToSelectLibraries.first()
        assertSame(pending, emitted)
        assertEquals(0, repo.commitCallCount)
        assertNull(vm.error)
    }

    @Test
    fun `onConnect success with single library auto-commits and emits navigateHome`() = runTest {
        val pending = singleLibraryPending()
        val repo = RecordingRepository(AuthenticateResult.Success(pending))
        val vm = makeVm(repo)
        vm.updateScheme("https://"); vm.updateHost("abs.example.com")
        vm.username = "admin"
        vm.password = "pass"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.navigateHome.first()
        assertEquals(1, repo.commitCallCount)
        assertNull(vm.error)
    }

    @Test
    fun `onConnect success with single library surfaces commit failure as error`() = runTest {
        val pending = singleLibraryPending()
        val repo = RecordingRepository(
            authResult = AuthenticateResult.Success(pending),
            commitResult = CommitSourceResult.Failure(RuntimeException("disk full")),
        )
        val vm = makeVm(repo)
        vm.updateScheme("https://"); vm.updateHost("abs.example.com")
        vm.username = "admin"
        vm.password = "pass"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, repo.commitCallCount)
        assertNotNull(vm.error)
        assertTrue(vm.error?.contains("disk full") == true)
        assertFalse(vm.isLoading)
    }

    @Test
    fun `onConnect wrong credentials sets error`() = runTest {
        val vm = makeVm(fakeRepo(AuthenticateResult.WrongCredentials("Bad creds")))
        vm.updateScheme("https://"); vm.updateHost("abs.example.com")
        vm.username = "admin"
        vm.password = "wrong"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Bad creds", vm.error)
    }

    @Test
    fun `onConnect network error sets connection-failed message`() = runTest {
        val vm = makeVm(fakeRepo(AuthenticateResult.NetworkError(Exception("timeout"))))
        vm.updateScheme("https://"); vm.updateHost("abs.example.com")
        vm.username = "admin"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.error?.contains("Connection failed") == true)
    }

    @Test
    fun `onInsecureWarningDismissed clears warning`() = runTest {
        val vm = makeVm(fakeRepo(AuthenticateResult.Success(fakePending())))
        vm.updateScheme("http://"); vm.updateHost("abs.example.com")
        vm.username = "admin"
        vm.onConnect()
        vm.onInsecureWarningDismissed()
        assertNull(vm.insecureWarning)
    }

    @Test
    fun `onInsecureWarningAccepted calls authenticate with insecureAllowed true`() = runTest {
        val repo = RecordingRepository(AuthenticateResult.Success(fakePending()))
        val vm = makeVm(repo)
        vm.updateScheme("http://"); vm.updateHost("abs.example.com")
        vm.username = "admin"
        vm.onInsecureWarningAccepted()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue("insecureAllowed must be true", repo.lastInsecureAllowed == true)
    }

    @Test
    fun `isLoading is false after login completes`() = runTest {
        val vm = makeVm(fakeRepo(AuthenticateResult.WrongCredentials("x")))
        vm.updateScheme("https://"); vm.updateHost("abs.example.com")
        vm.username = "admin"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.isLoading)
    }

    @Test
    fun `init with type=storyteller and editId prefills url, username, password from repo and token storage`() = runTest {
        val storyteller = Source(
            id = "st-1",
            url = SourceUrl.parse("http://media-server:8001")!!,
            isActive = false,
            insecureConnectionAllowed = true,
            username = "plamen",
            serverType = com.riffle.core.domain.ServerType.STORYTELLER_SERVICE,
        )
        val repo = RecordingRepository(
            authResult = AuthenticateResult.WrongCredentials("x"),
            storedById = mapOf("st-1" to storyteller),
        )
        val tokens = CountingTokenStorage(mutableMapOf("st-1" to "remembered"))
        val savedState = SavedStateHandle(mapOf("type" to "storyteller", "editId" to "st-1"))

        val vm = makeVm(repo, savedState = savedState, tokenStorage = tokens)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AddSourceBackend.STORYTELLER, vm.backend)
        assertTrue(vm.isEditing)
        assertEquals("http://", vm.scheme)
        assertEquals("media-server:8001", vm.host)
        assertEquals("plamen", vm.username)
        assertEquals("remembered", vm.password)
    }

    @Test
    fun `init with type=webdav and existing config enters edit mode with all fields prefilled`() = runTest {
        val existing = com.riffle.core.domain.AnnotationSyncConfig(
            baseUrl = "https://dav.example.com/store",
            username = "syncuser",
            password = "syncpass",
        )
        val store = RecordingConfigStore(initial = existing)
        val savedState = SavedStateHandle(mapOf("type" to "webdav"))

        val vm = makeVm(
            fakeRepo(AuthenticateResult.WrongCredentials("x")),
            savedState = savedState,
            configStore = store,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AddSourceBackend.WEBDAV, vm.backend)
        assertTrue(vm.isEditingWebdav)
        assertEquals("https://", vm.scheme)
        assertEquals("dav.example.com/store", vm.host)
        assertEquals("syncuser", vm.username)
        assertEquals("syncpass", vm.password)
        assertNotNull(vm.webdavBanner.value)
    }

    @Test
    fun `init with type=webdav and no config stays in add mode and blanks the form`() = runTest {
        val savedState = SavedStateHandle(mapOf("type" to "webdav"))
        val vm = makeVm(
            fakeRepo(AuthenticateResult.WrongCredentials("x")),
            savedState = savedState,
            configStore = RecordingConfigStore(initial = null),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AddSourceBackend.WEBDAV, vm.backend)
        assertFalse(vm.isEditingWebdav)
        assertEquals("", vm.host)
        assertEquals("", vm.username)
        assertEquals("", vm.password)
        assertNull(vm.webdavBanner.value)
    }

    @Test
    fun `webdavBanner re-evaluates when the status store reports a new outcome`() = runTest {
        val existing = com.riffle.core.domain.AnnotationSyncConfig(
            baseUrl = "https://dav.example.com/store",
            username = "syncuser",
            password = "syncpass",
        )
        val store = RecordingConfigStore(initial = existing)
        val statusStore = AnnotationSyncStatusStore()
        // Seed an offline failure — UI should land on Pending.
        statusStore.report(CycleOutcome.Failed.Network(1_000L, "offline"))

        val vm = makeVm(
            fakeRepo(AuthenticateResult.WrongCredentials("x")),
            savedState = SavedStateHandle(mapOf("type" to "webdav")),
            configStore = store,
            statusStore = statusStore,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val pending = vm.webdavBanner.value
        assertNotNull(pending)
        assertEquals(WebdavBannerKind.Pending, pending!!.kind)
        // No successful sync yet → "Never", NOT "just now"/"1 d ago" derived from the failed attempt.
        assertEquals("Never", pending.lastSyncRelative)

        // Connectivity returns, sweep succeeds. Banner must flip Pending → Synced without the
        // screen being re-entered.
        statusStore.report(CycleOutcome.Success(System.currentTimeMillis()))
        testDispatcher.scheduler.advanceUntilIdle()

        val synced = vm.webdavBanner.value
        assertNotNull(synced)
        assertEquals(WebdavBannerKind.Synced, synced!!.kind)
        assertEquals("just now", synced.lastSyncRelative)
    }

    /**
     * Regression: the AddServer WebDAV banner and the main Settings row derive their kind from the
     * same [com.riffle.app.feature.annotationsync.deriveAnnotationSyncKind] function, so a Success
     * outcome with un-pushed books must downgrade to Pending on the banner too — otherwise the
     * banner would say "Synced" while Settings said "N book(s) pending". Pin the banner side here;
     * `SettingsViewModelTest` pins the Settings side; both would flip if the shared rule regressed.
     */
    @Test
    fun `webdavBanner is Pending when Success outcome has pending books unsynced`() = runTest {
        val existing = com.riffle.core.domain.AnnotationSyncConfig(
            baseUrl = "https://dav.example.com/store",
            username = "syncuser",
            password = "syncpass",
        )
        val statusStore = AnnotationSyncStatusStore().apply {
            report(CycleOutcome.Success(1_000L))
        }
        val vm = makeVm(
            fakeRepo(AuthenticateResult.WrongCredentials("x")),
            savedState = SavedStateHandle(mapOf("type" to "webdav")),
            configStore = RecordingConfigStore(initial = existing),
            statusStore = statusStore,
            annotationDao = stubAnnotationDao(pendingBookCount = 2),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val banner = vm.webdavBanner.value
        assertNotNull(banner)
        assertEquals(WebdavBannerKind.Pending, banner!!.kind)
        assertTrue(
            "prescription should mention pending books, got: ${banner.prescription}",
            banner.prescription?.contains("2 book") == true,
        )
    }

    /**
     * Complements the previous test: when the pending count reactively drops to 0 (books get
     * pushed), the banner must flip Pending → Synced without navigating away and back. This is the
     * "update in realtime" half of the invariant — the Settings row also observes the same
     * pending-book count Flow, so both surfaces move together.
     */
    @Test
    fun `webdavBanner flips Pending to Synced when pending book count reactively drops to 0`() = runTest {
        val existing = com.riffle.core.domain.AnnotationSyncConfig(
            baseUrl = "https://dav.example.com/store",
            username = "syncuser",
            password = "syncpass",
        )
        val statusStore = AnnotationSyncStatusStore().apply {
            report(CycleOutcome.Success(1_000L))
        }
        val pending = MutableStateFlow(2)
        val dao = object : AnnotationDao {
            override fun observeForItem(sourceId: String, itemId: String) = flowOf(emptyList<AnnotationEntity>())
            override fun observeForSource(sourceId: String) = flowOf(emptyList<AnnotationEntity>())
            override suspend fun getForItem(sourceId: String, itemId: String) = emptyList<AnnotationEntity>()
            override suspend fun getAllForItemIncludingDeleted(sourceId: String, itemId: String) = emptyList<AnnotationEntity>()
            override suspend fun getById(id: String): AnnotationEntity? = null
            override suspend fun getByItemAndCfi(sourceId: String, itemId: String, cfi: String): AnnotationEntity? = null
            override suspend fun findImageForFigure(
                sourceId: String,
                itemId: String,
                chapterHref: String,
                imageHref: String?,
                imageSvg: String?,
            ): AnnotationEntity? = null
            override suspend fun upsert(entity: AnnotationEntity) {}
            override suspend fun upsertAll(annotations: List<AnnotationEntity>) {}
            override suspend fun tombstone(id: String, updatedAt: Long, deviceId: String) {}
            override suspend fun recolor(id: String, color: String, updatedAt: Long, deviceId: String) {}
            override suspend fun updateNote(id: String, note: String?, updatedAt: Long, deviceId: String) {}
            override fun observeAnnotationsByPosition(sourceId: String, itemId: String) = flowOf(emptyList<AnnotationEntity>())
            override suspend fun renameBookmark(id: String, title: String, updatedAt: Long, deviceId: String) {}
            override fun observePendingCountForBook(sourceId: String, itemId: String) = flowOf(0)
            override fun observePendingBookCountAcrossAll() = pending
            override suspend fun dirtySourceItems() = emptyList<AnnotationDao.DirtySourceItem>()
            override suspend fun markSynced(ids: List<String>, syncedAt: Long) {}
            override suspend fun purgeAgedTombstones(sourceId: String, itemId: String, cutoff: Long): Int = 0
            override suspend fun backfillNullOriginFontFamily(
                sourceId: String,
                itemId: String,
                fontFamily: String,
                updatedAt: Long,
                deviceId: String,
            ): Int = 0
            override fun observeBooksWithHighlights(sourceId: String) =
                flowOf(emptyList<com.riffle.core.database.BookHighlightSummary>())
        }
        val vm = makeVm(
            fakeRepo(AuthenticateResult.WrongCredentials("x")),
            savedState = SavedStateHandle(mapOf("type" to "webdav")),
            configStore = RecordingConfigStore(initial = existing),
            statusStore = statusStore,
            annotationDao = dao,
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(WebdavBannerKind.Pending, vm.webdavBanner.value!!.kind)

        pending.value = 0
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(WebdavBannerKind.Synced, vm.webdavBanner.value!!.kind)
    }

    /**
     * Regression: when offline, WorkManager gates the sweep on CONNECTED and no CycleOutcome
     * reports fire. Without a wall-clock ticker in the combine, the "Last sync N ago" string
     * froze at whatever elapsed value was computed when connectivity dropped and never advanced.
     * The banner must re-emit on ticker signals alone so "1 h ago" becomes "2 h ago" without any
     * upstream state change. In production the ticker fires once a minute
     * ([WebdavBannerTickerModule]); here the test drives it explicitly.
     */
    @Test
    fun `webdavBanner lastSyncRelative advances with wall-clock time when no cycle outcomes fire`() = runTest {
        val existing = com.riffle.core.domain.AnnotationSyncConfig(
            baseUrl = "https://dav.example.com/store",
            username = "syncuser",
            password = "syncpass",
        )
        val statusStore = AnnotationSyncStatusStore()
        val clock = MutableClock(nowMs = 0L)
        // Successful sync 1 hour ago.
        val oneHourMs = 60L * 60L * 1_000L
        clock.nowMs = oneHourMs
        statusStore.report(CycleOutcome.Success(atMs = 0L))
        val ticker = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

        val vm = makeVm(
            fakeRepo(AuthenticateResult.WrongCredentials("x")),
            savedState = SavedStateHandle(mapOf("type" to "webdav")),
            configStore = RecordingConfigStore(initial = existing),
            statusStore = statusStore,
            clock = clock,
            bannerTicker = ticker,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("1 h ago", vm.webdavBanner.value!!.lastSyncRelative)

        // Simulate going offline for another hour: only wall-clock advances. No config change,
        // no new CycleOutcome. Banner must still update because the ticker fires the combine,
        // which re-reads clock.nowMs().
        clock.nowMs = 2 * oneHourMs
        ticker.emit(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("2 h ago", vm.webdavBanner.value!!.lastSyncRelative)
    }

    @Test
    fun `onRemove for WebDAV clears the config store and emits navigateHome`() = runTest {
        val existing = com.riffle.core.domain.AnnotationSyncConfig("https://dav.example.com", "u", "p")
        val store = RecordingConfigStore(initial = existing)
        val savedState = SavedStateHandle(mapOf("type" to "webdav"))
        val vm = makeVm(
            fakeRepo(AuthenticateResult.WrongCredentials("x")),
            savedState = savedState,
            configStore = store,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.onRemove()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, store.clearCount)
        vm.navigateHome.first()
    }

    @Test
    fun `edit mode with wrong credentials does NOT remove the existing server`() = runTest {
        val storyteller = Source(
            id = "st-1",
            url = SourceUrl.parse("https://story.example.com")!!,
            isActive = false,
            insecureConnectionAllowed = false,
            username = "plamen",
            serverType = com.riffle.core.domain.ServerType.STORYTELLER_SERVICE,
        )
        val repo = RecordingRepository(
            authResult = AuthenticateResult.WrongCredentials("Bad creds"),
            storedById = mapOf("st-1" to storyteller),
        )
        val savedState = SavedStateHandle(mapOf("type" to "storyteller", "editId" to "st-1"))
        val vm = makeVm(repo, savedState = savedState)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.password = "wrong-password"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Bad creds", vm.error)
        assertTrue("existing server must survive a failed edit attempt", repo.removedIds.isEmpty())
    }

    @Test
    fun `onRemove for Storyteller calls repository remove with the editing id and navigates home`() = runTest {
        val storyteller = Source(
            id = "st-1",
            url = SourceUrl.parse("http://media-server:8001")!!,
            isActive = false,
            insecureConnectionAllowed = true,
            username = "plamen",
            serverType = com.riffle.core.domain.ServerType.STORYTELLER_SERVICE,
        )
        val repo = RecordingRepository(
            authResult = AuthenticateResult.WrongCredentials("x"),
            storedById = mapOf("st-1" to storyteller),
        )
        val savedState = SavedStateHandle(mapOf("type" to "storyteller", "editId" to "st-1"))
        val vm = makeVm(repo, savedState = savedState)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.onRemove()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("st-1"), repo.removedIds)
        vm.navigateHome.first()
    }
}
