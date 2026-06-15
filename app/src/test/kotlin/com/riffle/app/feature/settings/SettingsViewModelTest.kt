package com.riffle.app.feature.settings

import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReadaloudPreferences
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.Collection
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.PendingServer
import java.io.IOException
import com.riffle.core.domain.CrashReport
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryOrderPreferencesStore
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.AbsPickerItem
import com.riffle.core.domain.ConfirmedReadaloud
import com.riffle.core.domain.PendingReadaloud
import com.riffle.core.domain.ReadaloudReview
import com.riffle.core.domain.ReadaloudReviewRepository
import com.riffle.core.domain.Series
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.UnmatchedReadaloud
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // --- helpers ---

    private val noOpFormattingStore = object : FormattingPreferencesStore {
        override val preferences = flowOf(FormattingPreferences())
        override suspend fun update(preferences: FormattingPreferences) {}
    }

    private val noOpWakeLockStore = object : WakeLockPreferencesStore {
        override val keepScreenOn = flowOf(true)
        override suspend fun setKeepScreenOn(value: Boolean) {}
    }

    private val readaloudPrefsFlow = MutableStateFlow(ReadaloudPreferences())
    private val fakeReadaloudStore = object : ReadaloudPreferencesStore {
        override val preferences = readaloudPrefsFlow
        override suspend fun update(prefs: ReadaloudPreferences) { readaloudPrefsFlow.value = prefs }
    }

    private val volumeNavEnabledFlow = MutableStateFlow(true)
    private val invertVolumeKeysFlow = MutableStateFlow(false)
    private val fakeVolumeKeyStore = object : VolumeKeyPreferencesStore {
        override val volumeKeyNavigationEnabled: Flow<Boolean> = volumeNavEnabledFlow
        override val invertVolumeKeys: Flow<Boolean> = invertVolumeKeysFlow
        override suspend fun setVolumeKeyNavigationEnabled(value: Boolean) { volumeNavEnabledFlow.value = value }
        override suspend fun setInvertVolumeKeys(value: Boolean) { invertVolumeKeysFlow.value = value }
    }

    private val appThemeFlow = MutableStateFlow(AppTheme.System)
    private val fakeAppThemeStore = object : AppThemeStore {
        override val appTheme: Flow<AppTheme> = appThemeFlow
        override suspend fun setAppTheme(value: AppTheme) { appThemeFlow.value = value }
    }

    private fun server(
        id: String,
        active: Boolean = false,
        serverType: ServerType = ServerType.AUDIOBOOKSHELF,
    ) = Server(
        id = id,
        url = ServerUrl.parse("https://$id.example.com")!!,
        isActive = active,
        insecureConnectionAllowed = false,
        username = "",
        serverType = serverType,
    )

    private fun library(id: String) = Library(id = id, name = id, mediaType = "book", isUnsupported = false)

    private val serversFlow = MutableStateFlow<List<Server>>(emptyList())
    private val librariesFlow = MutableStateFlow<List<Library>>(emptyList())
    private val hiddenFlow = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val orderFlow = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    private fun fakeServerRepo(): ServerRepository = object : ServerRepository {
        override fun observeAll(): Flow<List<Server>> = serversFlow
        override suspend fun getActive(): Server? = serversFlow.value.firstOrNull { it.isActive }
        override suspend fun authenticate(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType): AuthenticateResult =
            AuthenticateResult.WrongCredentials()
        override suspend fun commit(pending: PendingServer, hiddenLibraryIds: Set<String>): CommitServerResult =
            CommitServerResult.Failure(IOException())
        override suspend fun setActive(serverId: String) {
            serversFlow.update { list -> list.map { it.copy(isActive = it.id == serverId) } }
        }
        override suspend fun remove(serverId: String) {
            serversFlow.update { list -> list.filter { it.id != serverId } }
        }
        override suspend fun getServerVersion(serverId: String): String? = null
    }

    private fun fakeLibraryRepo(): LibraryRepository = object : LibraryRepository {
        override fun observeLibraries(): Flow<List<Library>> = librariesFlow
        override fun observeLibraries(serverId: String): Flow<List<Library>> = observeLibraries()
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = MutableStateFlow(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = MutableStateFlow(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override fun observeItem(itemId: String): Flow<LibraryItem?> = MutableStateFlow<LibraryItem?>(null)
        override suspend fun getItem(serverId: String, itemId: String): LibraryItem? = getItem(itemId)
        override suspend fun getLibrary(libraryId: String): com.riffle.core.domain.Library? = null
        override suspend fun getSeriesIdForItem(serverId: String, itemId: String): String? = null
        override suspend fun markItemOpened(itemId: String) {}
        override suspend fun updateReadingProgress(itemId: String, progress: Float) {}
        override suspend fun refreshLibraries(): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshCollections(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
    }

    private fun fakeVisibilityStore(): LibraryVisibilityPreferencesStore = object : LibraryVisibilityPreferencesStore {
        override fun hiddenLibraryIds(serverId: String): Flow<Set<String>> =
            hiddenFlow.map { it[serverId].orEmpty() }
        override suspend fun hideLibrary(serverId: String, libraryId: String) {
            hiddenFlow.update { it + (serverId to (it[serverId].orEmpty() + libraryId)) }
        }
        override suspend fun showLibrary(serverId: String, libraryId: String) {
            hiddenFlow.update { it + (serverId to (it[serverId].orEmpty() - libraryId)) }
        }
    }

    private fun fakeOrderStore(): LibraryOrderPreferencesStore = object : LibraryOrderPreferencesStore {
        override fun libraryOrder(serverId: String): Flow<List<String>> =
            orderFlow.map { it[serverId].orEmpty() }
        override suspend fun setLibraryOrder(serverId: String, orderedIds: List<String>) {
            orderFlow.update { it + (serverId to orderedIds) }
        }
    }

    private val isOnlineFlow = MutableStateFlow(true)
    private val fakeConnectivity = object : ConnectivityObserver {
        override val isOnline: kotlinx.coroutines.flow.StateFlow<Boolean> = isOnlineFlow
    }

    private val fakeAppUpdateRepo = object : com.riffle.core.domain.AppUpdateRepository {
        override suspend fun checkForUpdate(currentVersionCode: Int) =
            com.riffle.core.domain.UpdateCheckResult.UpToDate
        override fun downloadAndInstall(update: com.riffle.core.domain.AvailableUpdate):
            Flow<com.riffle.core.domain.UpdateDownloadState> = kotlinx.coroutines.flow.emptyFlow()
        override fun sweepStaleApks() = Unit
    }

    private val reviewsFlow = MutableStateFlow<Map<String, ReadaloudReview>>(emptyMap())
    private val fakeReviewRepo = object : ReadaloudReviewRepository {
        override fun observeReview(storytellerServerId: String): Flow<ReadaloudReview> =
            reviewsFlow.map { it[storytellerServerId] ?: ReadaloudReview(emptyList(), emptyList(), emptyList()) }
        override suspend fun confirmCandidate(storytellerServerId: String, storytellerBookId: String, absServerId: String, absLibraryItemId: String) = Unit
        override suspend fun dismissCandidate(storytellerServerId: String, storytellerBookId: String, absServerId: String, absLibraryItemId: String) = Unit
        override suspend fun dismissBook(storytellerServerId: String, storytellerBookId: String) = Unit
        override suspend fun unlinkBook(storytellerServerId: String, storytellerBookId: String) = Unit
        override suspend fun unlinkAbsItem(absServerId: String, absLibraryItemId: String) = Unit
        override suspend fun pairManually(storytellerServerId: String, storytellerBookId: String, absServerId: String, absLibraryItemId: String) = Unit
        override suspend fun searchAbsItems(query: String, filter: com.riffle.core.domain.AbsFormatFilter): List<AbsPickerItem> = emptyList()
    }

    private fun makeViewModel(report: CrashReport? = null) = SettingsViewModel(
        crashReportRepository = object : CrashReportRepository {
            override fun getLastCrashReport() = report
        },
        formattingPreferencesStore = noOpFormattingStore,
        serverRepository = fakeServerRepo(),
        libraryRepository = fakeLibraryRepo(),
        visibilityStore = fakeVisibilityStore(),
        orderStore = fakeOrderStore(),
        wakeLockPreferencesStore = noOpWakeLockStore,
        volumeKeyPreferencesStore = fakeVolumeKeyStore,
        appThemeStore = fakeAppThemeStore,
        readaloudReviewRepository = fakeReviewRepo,
        connectivityObserver = fakeConnectivity,
        appUpdateRepository = fakeAppUpdateRepo,
        readaloudPreferencesStore = fakeReadaloudStore,
    )

    // --- existing crash report tests (unchanged) ---

    @Test
    fun `lastCrashReport is populated from repository when a crash has been recorded`() {
        val report = CrashReport(content = "stack trace here", timestampMillis = 1_000_000L)
        val vm = makeViewModel(report)
        assertEquals(report, vm.lastCrashReport)
    }

    @Test
    fun `lastCrashReport is null when repository has no report`() {
        val vm = makeViewModel(null)
        assertNull(vm.lastCrashReport)
    }

    // --- server management ---

    @Test
    fun `removeServer removes a non-active server from the list`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true), server("srv-2"))
        val vm = makeViewModel()
        backgroundScope.launch { vm.servers.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.removeServer("srv-2")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(server("srv-1", active = true)), vm.servers.value)
    }

    @Test
    fun `removeServer active server promotes the next server as active`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true), server("srv-2"))
        val vm = makeViewModel()
        backgroundScope.launch { vm.servers.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.removeServer("srv-1")
        testDispatcher.scheduler.advanceUntilIdle()

        val remaining = vm.servers.value
        assertEquals(1, remaining.size)
        assertEquals("srv-2", remaining[0].id)
        assertTrue(remaining[0].isActive)
    }

    @Test
    fun `removeServer active server promotes the next browsable server, never a Storyteller`() = runTest {
        // ADR 0026: a Storyteller Server can never become the active browsable Server, so removing
        // the active ABS server must skip the Storyteller and promote the next ABS server.
        serversFlow.value = listOf(
            server("abs-1", active = true),
            server("st-1", serverType = ServerType.STORYTELLER),
            server("abs-2"),
        )
        val vm = makeViewModel()
        backgroundScope.launch { vm.servers.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.removeServer("abs-1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("abs-2"), serversFlow.value.filter { it.isActive }.map { it.id })
    }

    @Test
    fun `removeServer last server emits NavigateToAddServer event`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        val vm = makeViewModel()
        backgroundScope.launch { vm.servers.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.removeServer("srv-1")
        testDispatcher.scheduler.advanceUntilIdle()

        val event = vm.navigationEvents.first()
        assertTrue(event is SettingsNavEvent.NavigateToAddServer)
    }

    // --- library visibility ---

    @Test
    fun `setLibraryVisible false calls hideLibrary on the store`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"))
        val vm = makeViewModel()
        backgroundScope.launch { vm.libraryUiItemsByServer.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setLibraryVisible(serverId = "srv-1", libraryId = "lib-1", visible = false)
        testDispatcher.scheduler.advanceUntilIdle()

        val hidden = hiddenFlow.value["srv-1"].orEmpty()
        assertTrue("lib-1" in hidden)
    }

    @Test
    fun `setLibraryVisible true calls showLibrary on the store`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"))
        hiddenFlow.value = mapOf("srv-1" to setOf("lib-1"))
        val vm = makeViewModel()
        backgroundScope.launch { vm.libraryUiItemsByServer.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setLibraryVisible(serverId = "srv-1", libraryId = "lib-1", visible = true)
        testDispatcher.scheduler.advanceUntilIdle()

        val hidden = hiddenFlow.value["srv-1"].orEmpty()
        assertFalse("lib-1" in hidden)
    }

    @Test
    fun `setLibraryOrder persists the given order to the store`() = runTest {
        val vm = makeViewModel()
        vm.setLibraryOrder("srv-1", listOf("lib-3", "lib-1", "lib-2"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("lib-3", "lib-1", "lib-2"), orderFlow.value["srv-1"])
    }

    @Test
    fun `libraryUiItemsByServer reflects the saved custom order`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"), library("lib-3"))
        orderFlow.value = mapOf("srv-1" to listOf("lib-2", "lib-3", "lib-1"))
        val vm = makeViewModel()
        backgroundScope.launch { vm.libraryUiItemsByServer.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf("lib-2", "lib-3", "lib-1"),
            vm.libraryUiItemsByServer.value["srv-1"].orEmpty().map { it.library.id },
        )
    }

    @Test
    fun `libraryUiItems switchEnabled is false for the sole visible library`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"))
        hiddenFlow.value = mapOf("srv-1" to setOf("lib-2"))  // only lib-1 is visible
        val vm = makeViewModel()
        backgroundScope.launch { vm.libraryUiItemsByServer.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val items = vm.libraryUiItemsByServer.value["srv-1"].orEmpty()
        val lib1Item = items.first { it.library.id == "lib-1" }
        val lib2Item = items.first { it.library.id == "lib-2" }
        assertFalse("lib-1 is the sole visible library, its switch must be disabled", lib1Item.switchEnabled)
        assertTrue("lib-2 is hidden, its switch must be enabled (to allow un-hiding)", lib2Item.switchEnabled)
    }

    @Test
    fun `libraryUiItems switchEnabled is true for all libraries when multiple are visible`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"))
        // nothing hidden — both visible
        val vm = makeViewModel()
        backgroundScope.launch { vm.libraryUiItemsByServer.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val items = vm.libraryUiItemsByServer.value["srv-1"].orEmpty()
        assertTrue(items.all { it.switchEnabled })
    }

    @Test
    fun `libraryUiItemsByServer exposes libraries for a non-active ABS server`() = runTest {
        serversFlow.value = listOf(
            server("srv-active", active = true),
            server("srv-other", active = false),
        )
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"))
        val vm = makeViewModel()
        backgroundScope.launch { vm.libraryUiItemsByServer.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        // The non-active server still has manageable library items — no activation required.
        val items = vm.libraryUiItemsByServer.value["srv-other"].orEmpty()
        assertEquals(2, items.size)
    }

    // --- readaloud matches summary ---

    @Test
    fun `readaloudSummaries reports per-server unmatched suggested partially-matched and matched counts`() = runTest {
        serversFlow.value = listOf(server("st-1", active = true, serverType = ServerType.STORYTELLER))
        reviewsFlow.value = mapOf(
            "st-1" to ReadaloudReview(
                pending = listOf(pending("b1")),
                unmatched = listOf(unmatched("b2"), unmatched("b3")),
                // b4/b5 fully matched; b6 missing its ebook → partially matched.
                confirmed = listOf(confirmed("b4"), confirmed("b5"), confirmed("b6", hasEbook = false)),
            )
        )
        val vm = makeViewModel()
        backgroundScope.launch { vm.readaloudSummaries.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val summary = vm.readaloudSummaries.value["st-1"]
        assertEquals(
            ReadaloudMatchSummary(unmatchedCount = 2, suggestedCount = 1, partiallyMatchedCount = 1, matchedCount = 2),
            summary,
        )
    }

    @Test
    fun `readaloudSummaries is empty when there are no Storyteller servers`() = runTest {
        serversFlow.value = listOf(server("abs-1", active = true))
        val vm = makeViewModel()
        backgroundScope.launch { vm.readaloudSummaries.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.readaloudSummaries.value.isEmpty())
    }

    private fun pending(bookId: String) = PendingReadaloud(
        storytellerServerId = "st-1", storytellerBookId = bookId,
        title = bookId, author = "", coverUrl = null, candidates = emptyList(),
    )

    private fun unmatched(bookId: String) = UnmatchedReadaloud(
        storytellerServerId = "st-1", storytellerBookId = bookId,
        title = bookId, author = "", coverUrl = null,
    )

    private fun confirmed(bookId: String, hasEbook: Boolean = true, hasAudio: Boolean = true) = ConfirmedReadaloud(
        storytellerServerId = "st-1", storytellerBookId = bookId, title = bookId,
        targets = listOf(
            ConfirmedReadaloud.ConfirmedTarget(
                absServerId = "abs", absLibraryItemId = bookId, absTitle = bookId,
                absLibraryName = "lib", hasEbook = hasEbook, hasAudio = hasAudio,
            )
        ),
    )

    // --- volume key preferences ---

    @Test
    fun `volumeKeyNavigationEnabled StateFlow reflects store default value`() = runTest {
        volumeNavEnabledFlow.value = true
        val vm = makeViewModel()
        backgroundScope.launch { vm.volumeKeyNavigationEnabled.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.volumeKeyNavigationEnabled.value)
    }

    @Test
    fun `setVolumeKeyNavigationEnabled false updates the store`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.volumeKeyNavigationEnabled.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setVolumeKeyNavigationEnabled(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, volumeNavEnabledFlow.first())
    }

    @Test
    fun `invertVolumeKeys StateFlow reflects store default value`() = runTest {
        invertVolumeKeysFlow.value = false
        val vm = makeViewModel()
        backgroundScope.launch { vm.invertVolumeKeys.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.invertVolumeKeys.value)
    }

    @Test
    fun `setInvertVolumeKeys true updates the store`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.invertVolumeKeys.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setInvertVolumeKeys(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, invertVolumeKeysFlow.first())
    }

    // --- app theme ---

    @Test
    fun `appTheme StateFlow reflects store default value`() = runTest {
        appThemeFlow.value = AppTheme.System
        val vm = makeViewModel()
        backgroundScope.launch { vm.appTheme.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AppTheme.System, vm.appTheme.value)
    }

    @Test
    fun `setAppTheme Dark updates the store`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.appTheme.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setAppTheme(AppTheme.Dark)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AppTheme.Dark, appThemeFlow.first())
    }

    // --- readaloud highlight color ---

    @Test
    fun `updateReadaloudHighlightColor persists new color to store and updates StateFlow`() = runTest {
        val vm = makeViewModel()
        val emitted = mutableListOf<ReadaloudHighlightColor>()
        val job = launch { vm.readaloudPreferences.collect { emitted.add(it.highlightColor) } }
        testDispatcher.scheduler.advanceUntilIdle()
        vm.updateReadaloudHighlightColor(ReadaloudHighlightColor.YELLOW)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ReadaloudHighlightColor.YELLOW, readaloudPrefsFlow.value.highlightColor)
        assertEquals(ReadaloudHighlightColor.YELLOW, emitted.last())
        job.cancel()
    }
}
