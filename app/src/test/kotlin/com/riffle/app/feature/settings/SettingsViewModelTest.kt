package com.riffle.app.feature.settings

import com.riffle.core.data.AnnotationSyncStatusStore
import com.riffle.core.data.CycleOutcome
import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.ReadaloudPreferences
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.Collection
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.PendingSource
import java.io.IOException
import com.riffle.core.domain.CrashReport
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryOrderPreferencesStore
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.AbsPickerItem
import com.riffle.core.domain.ConfirmedReadaloud
import com.riffle.core.domain.PendingReadaloud
import com.riffle.core.domain.ReadaloudReview
import com.riffle.core.domain.ReadaloudReviewRepository
import com.riffle.core.domain.Series
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.SourceUrl
import com.riffle.core.domain.UnmatchedReadaloud
import com.riffle.core.domain.ListeningPreferencesStore
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
        override suspend fun setCadencePlatformSupported(supported: Boolean) {}
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

    private val fakeListeningPreferencesStore = object : ListeningPreferencesStore {
        override val defaultPlaybackSpeed = MutableStateFlow(ListeningPreferencesStore.DEFAULT_PLAYBACK_SPEED)
        override val skipIntervalSeconds = MutableStateFlow(ListeningPreferencesStore.DEFAULT_SKIP_INTERVAL_SECONDS)
        override val rewindIntervalSeconds = MutableStateFlow(ListeningPreferencesStore.DEFAULT_REWIND_INTERVAL_SECONDS)
        override val rewindOnResumeSeconds = MutableStateFlow(ListeningPreferencesStore.DEFAULT_REWIND_ON_RESUME_SECONDS)
        override suspend fun setDefaultPlaybackSpeed(speed: Float) { defaultPlaybackSpeed.value = speed }
        override suspend fun setSkipIntervalSeconds(seconds: Int) { skipIntervalSeconds.value = seconds }
        override suspend fun setRewindIntervalSeconds(seconds: Int) { rewindIntervalSeconds.value = seconds }
        override suspend fun setRewindOnResumeSeconds(seconds: Int) { rewindOnResumeSeconds.value = seconds }
    }

    private fun server(
        id: String,
        active: Boolean = false,
        serverType: ServerType = ServerType.AUDIOBOOKSHELF,
    ) = Source(
        id = id,
        url = SourceUrl.parse("https://$id.example.com")!!,
        isActive = active,
        insecureConnectionAllowed = false,
        username = "",
        serverType = serverType,
    )

    private fun library(id: String) = Library(id = id, name = id, mediaType = "book", isUnsupported = false)

    private val serversFlow = MutableStateFlow<List<Source>>(emptyList())
    private val librariesFlow = MutableStateFlow<List<Library>>(emptyList())
    private val hiddenFlow = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val orderFlow = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    private fun fakeServerRepo(): SourceRepository = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = serversFlow
        override suspend fun getActive(): Source? = serversFlow.value.firstOrNull { it.isActive }
        override suspend fun authenticate(url: SourceUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType): AuthenticateResult =
            AuthenticateResult.WrongCredentials()
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(IOException())
        override suspend fun setActive(sourceId: String) {
            serversFlow.update { list -> list.map { it.copy(isActive = it.id == sourceId) } }
        }
        override suspend fun remove(sourceId: String) {
            serversFlow.update { list -> list.filter { it.id != sourceId } }
        }
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private fun fakeLibraryRepo(): LibraryObserver = object : LibraryObserver {
        override fun observeLibraries(): Flow<List<Library>> = librariesFlow
        override fun observeLibraries(sourceId: String): Flow<List<Library>> = observeLibraries()
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
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = getItem(itemId)
        override suspend fun getLibrary(libraryId: String): com.riffle.core.domain.Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private fun fakeVisibilityStore(): LibraryVisibilityPreferencesStore = object : LibraryVisibilityPreferencesStore {
        override fun hiddenLibraryIds(sourceId: String): Flow<Set<String>> =
            hiddenFlow.map { it[sourceId].orEmpty() }
        override suspend fun hideLibrary(sourceId: String, libraryId: String) {
            hiddenFlow.update { it + (sourceId to (it[sourceId].orEmpty() + libraryId)) }
        }
        override suspend fun showLibrary(sourceId: String, libraryId: String) {
            hiddenFlow.update { it + (sourceId to (it[sourceId].orEmpty() - libraryId)) }
        }
    }

    private fun fakeOrderStore(): LibraryOrderPreferencesStore = object : LibraryOrderPreferencesStore {
        override fun libraryOrder(sourceId: String): Flow<List<String>> =
            orderFlow.map { it[sourceId].orEmpty() }
        override suspend fun setLibraryOrder(sourceId: String, orderedIds: List<String>) {
            orderFlow.update { it + (sourceId to orderedIds) }
        }
    }

    private val isOnlineFlow = MutableStateFlow(true)
    private val fakeConnectivity = object : ConnectivityObserver {
        override val isOnline: kotlinx.coroutines.flow.StateFlow<Boolean> = isOnlineFlow
    }

    // LocalFiles dependencies are unused by these settings tests — they still need to satisfy the
    // constructor's non-null parameters, so route them through relaxed mockk mocks.
    private val fakeLocalFilesFolderDao = io.mockk.mockk<com.riffle.core.database.LocalFilesFolderDao>(relaxed = true).also {
        io.mockk.every { it.observeForSource(any()) } returns flowOf(emptyList())
    }
    private val fakeLocalFilesFolderRepository =
        io.mockk.mockk<com.riffle.core.data.localfiles.LocalFilesFolderRepository>(relaxed = true)
    private val fakeLocalFilesScanner =
        io.mockk.mockk<com.riffle.core.data.localfiles.LocalFilesScanner>(relaxed = true)
    private val fakeLocalFilesSourceInstaller =
        io.mockk.mockk<com.riffle.core.data.localfiles.LocalFilesSourceInstaller>(relaxed = true)
    private val fakeLocalFilesFolderHealthChecker =
        io.mockk.mockk<com.riffle.core.data.localfiles.LocalFilesFolderHealthChecker>(relaxed = true).also {
            io.mockk.every { it.healthFor(any()) } returns emptyMap()
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
        override fun observeReview(storytellerSourceId: String, absSourceId: String?): Flow<ReadaloudReview> =
            reviewsFlow.map { it[storytellerSourceId] ?: ReadaloudReview(emptyList(), emptyList(), emptyList()) }
        override suspend fun searchAbsItems(absSourceId: String, query: String, filter: com.riffle.core.domain.AbsFormatFilter): List<AbsPickerItem> = emptyList()
    }

    private fun makeViewModel(
        reports: List<CrashReport> = emptyList(),
        annotationSyncStatusStore: AnnotationSyncStatusStore = AnnotationSyncStatusStore(),
        annotationDao: AnnotationDao = stubAnnotationDao(pendingBookCount = 0),
    ) = SettingsViewModel(
        crashReportRepository = object : CrashReportRepository {
            private val current = reports.toMutableList()
            override fun listCrashReports(): List<CrashReport> = current.toList()
            override fun resolveReportFiles(ids: List<String>) = emptyList<java.io.File>()
            override fun clearAllCrashReports() { current.clear() }
        },
        formattingPreferencesStore = noOpFormattingStore,
        sourceRepository = fakeServerRepo(),
        libraryObserver = fakeLibraryRepo(),
        visibilityStore = fakeVisibilityStore(),
        orderStore = fakeOrderStore(),
        wakeLockPreferencesStore = noOpWakeLockStore,
        volumeKeyPreferencesStore = fakeVolumeKeyStore,
        listeningPreferencesStore = fakeListeningPreferencesStore,
        appThemeStore = fakeAppThemeStore,
        readaloudReviewRepository = fakeReviewRepo,
        connectivityObserver = fakeConnectivity,
        appUpdateRepository = fakeAppUpdateRepo,
        readaloudPreferencesStore = fakeReadaloudStore,
        annotationSyncConfigStore = object : AnnotationSyncConfigStore {
            override fun observe() = MutableStateFlow<AnnotationSyncConfig?>(null)
            override suspend fun save(config: AnnotationSyncConfig) = Unit
            override suspend fun clear() = Unit
        },
        annotationSyncStatusStore = annotationSyncStatusStore,
        annotationDao = annotationDao,
        localFilesFolderDao = fakeLocalFilesFolderDao,
        localFilesFolderRepository = fakeLocalFilesFolderRepository,
        localFilesScanner = fakeLocalFilesScanner,
        localFilesSourceInstaller = fakeLocalFilesSourceInstaller,
        localFilesFolderHealthChecker = fakeLocalFilesFolderHealthChecker,
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
        override fun observeBooksWithHighlights(sourceId: String) =
            flowOf(emptyList<com.riffle.core.database.BookHighlightSummary>())
    }

    private fun stubConfigStore(flow: MutableStateFlow<AnnotationSyncConfig?>) = object : AnnotationSyncConfigStore {
        override fun observe() = flow
        override suspend fun save(config: AnnotationSyncConfig) { flow.value = config }
        override suspend fun clear() { flow.value = null }
    }

    private fun newSettingsViewModel(
        configStore: AnnotationSyncConfigStore,
        statusStore: AnnotationSyncStatusStore,
        annotationDao: AnnotationDao,
    ) = SettingsViewModel(
        crashReportRepository = object : CrashReportRepository {
            override fun listCrashReports(): List<CrashReport> = emptyList()
            override fun resolveReportFiles(ids: List<String>) = emptyList<java.io.File>()
            override fun clearAllCrashReports() = Unit
        },
        formattingPreferencesStore = noOpFormattingStore,
        sourceRepository = fakeServerRepo(),
        libraryObserver = fakeLibraryRepo(),
        visibilityStore = fakeVisibilityStore(),
        orderStore = fakeOrderStore(),
        wakeLockPreferencesStore = noOpWakeLockStore,
        volumeKeyPreferencesStore = fakeVolumeKeyStore,
        listeningPreferencesStore = fakeListeningPreferencesStore,
        appThemeStore = fakeAppThemeStore,
        readaloudReviewRepository = fakeReviewRepo,
        connectivityObserver = fakeConnectivity,
        appUpdateRepository = fakeAppUpdateRepo,
        readaloudPreferencesStore = fakeReadaloudStore,
        annotationSyncConfigStore = configStore,
        annotationSyncStatusStore = statusStore,
        annotationDao = annotationDao,
        localFilesFolderDao = fakeLocalFilesFolderDao,
        localFilesFolderRepository = fakeLocalFilesFolderRepository,
        localFilesScanner = fakeLocalFilesScanner,
        localFilesSourceInstaller = fakeLocalFilesSourceInstaller,
        localFilesFolderHealthChecker = fakeLocalFilesFolderHealthChecker,
    )

    // --- crash report list tests ---

    @Test
    fun `crashReports surfaces every report from the repository, newest first`() {
        val older = CrashReport(id = "100", content = "older crash", timestampMillis = 1_000L)
        val newer = CrashReport(id = "200", content = "newer crash", timestampMillis = 2_000L)
        val vm = makeViewModel(listOf(newer, older))
        assertEquals(listOf(newer, older), vm.crashReports.value)
    }

    @Test
    fun `crashReports is empty when repository has no reports`() {
        val vm = makeViewModel(emptyList())
        assertTrue(vm.crashReports.value.isEmpty())
    }

    @Test
    fun `clearCrashReports clears both repository and the exposed state`() {
        val vm = makeViewModel(listOf(CrashReport("1", "boom", 1L)))
        assertEquals(1, vm.crashReports.value.size)
        vm.clearCrashReports()
        assertTrue(vm.crashReports.value.isEmpty())
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
        // ADR 0026: a Storyteller Source can never become the active browsable Source, so removing
        // the active ABS server must skip the Storyteller and promote the next ABS server.
        serversFlow.value = listOf(
            server("abs-1", active = true),
            server("st-1", serverType = ServerType.STORYTELLER_SERVICE),
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
    fun `removeServer last server emits NavigateToAddSource event`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        val vm = makeViewModel()
        backgroundScope.launch { vm.servers.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.removeServer("srv-1")
        testDispatcher.scheduler.advanceUntilIdle()

        val event = vm.navigationEvents.first()
        assertTrue(event is SettingsNavEvent.NavigateToAddSource)
    }

    // --- library visibility ---

    @Test
    fun `setLibraryVisible false calls hideLibrary on the store`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"))
        val vm = makeViewModel()
        backgroundScope.launch { vm.libraryUiItemsByServer.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setLibraryVisible(sourceId = "srv-1", libraryId = "lib-1", visible = false)
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

        vm.setLibraryVisible(sourceId = "srv-1", libraryId = "lib-1", visible = true)
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
        serversFlow.value = listOf(server("st-1", active = true, serverType = ServerType.STORYTELLER_SERVICE))
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
    fun `readaloudSummaries is empty when there are no Storyteller services`() = runTest {
        serversFlow.value = listOf(server("abs-1", active = true))
        val vm = makeViewModel()
        backgroundScope.launch { vm.readaloudSummaries.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.readaloudSummaries.value.isEmpty())
    }

    private fun pending(bookId: String) = PendingReadaloud(
        storytellerSourceId = "st-1", storytellerBookId = bookId,
        title = bookId, author = "", coverUrl = null, candidates = emptyList(),
    )

    private fun unmatched(bookId: String) = UnmatchedReadaloud(
        storytellerSourceId = "st-1", storytellerBookId = bookId,
        title = bookId, author = "", coverUrl = null,
    )

    private fun confirmed(bookId: String, hasEbook: Boolean = true, hasAudio: Boolean = true) = ConfirmedReadaloud(
        storytellerSourceId = "st-1", storytellerBookId = bookId, title = bookId,
        targets = listOf(
            ConfirmedReadaloud.ConfirmedTarget(
                absSourceId = "abs", absLibraryItemId = bookId, absTitle = bookId,
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
    fun `updateHighlightColor persists new color to store and updates StateFlow`() = runTest {
        val vm = makeViewModel()
        val emitted = mutableListOf<HighlightColor>()
        val job = launch { vm.readaloudPreferences.collect { emitted.add(it.highlightColor) } }
        testDispatcher.scheduler.advanceUntilIdle()
        vm.updateHighlightColor(HighlightColor.YELLOW)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(HighlightColor.YELLOW, readaloudPrefsFlow.value.highlightColor)
        assertEquals(HighlightColor.YELLOW, emitted.last())
        job.cancel()
    }

    // --- listening preferences ---

    @Test
    fun `setDefaultPlaybackSpeed updates the store`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.defaultPlaybackSpeed.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setDefaultPlaybackSpeed(1.5f)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1.5f, fakeListeningPreferencesStore.defaultPlaybackSpeed.first())
    }

    @Test
    fun `setSkipIntervalSeconds updates the store`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.skipIntervalSeconds.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setSkipIntervalSeconds(15)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(15, fakeListeningPreferencesStore.skipIntervalSeconds.first())
    }

    @Test
    fun `setRewindOnResumeSeconds updates the store`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.rewindOnResumeSeconds.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setRewindOnResumeSeconds(10)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(10, fakeListeningPreferencesStore.rewindOnResumeSeconds.first())
    }

    // --- annotationSyncRow ---

    @Test
    fun `annotationSyncRow is Local when unconfigured`() = runTest {
        val config = MutableStateFlow<AnnotationSyncConfig?>(null)
        val status = AnnotationSyncStatusStore()
        val dao = stubAnnotationDao(pendingBookCount = 0)
        val vm = newSettingsViewModel(configStore = stubConfigStore(config), statusStore = status, annotationDao = dao)
        backgroundScope.launch { vm.annotationSyncRow.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val row = vm.annotationSyncRow.value
        assertEquals(AnnotationSyncRowState.Badge.Local, row.badge)
        assertEquals("WebDAV", row.headline)
        assertTrue(row.sub.startsWith("Not configured"))
        assertEquals(AnnotationSyncRowState.Tone.Normal, row.subTone)
    }

    @Test
    fun `annotationSyncRow is Pending when configured + NeverRun + no pending (not prematurely Synced)`() = runTest {
        val config = MutableStateFlow<AnnotationSyncConfig?>(AnnotationSyncConfig("https://srv.example/dav/", "alice", "pw"))
        val status = AnnotationSyncStatusStore() // NeverRun by default
        val vm = newSettingsViewModel(
            configStore = stubConfigStore(config), statusStore = status,
            annotationDao = stubAnnotationDao(pendingBookCount = 0),
        )
        backgroundScope.launch { vm.annotationSyncRow.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val row = vm.annotationSyncRow.value
        assertEquals(AnnotationSyncRowState.Badge.Pending, row.badge)
        assertEquals(AnnotationSyncRowState.Tone.Pending, row.subTone)
        // Should NOT show the Synced badge before any cycle has completed.
    }

    // Regression: NeverRun must win over a positive pending count in the sub-text — a
    // freshly-installed device with un-pushed local highlights should say "Waiting for first
    // sync…", not "N book(s) pending · will sync when online". Introduced when the row's
    // when-branches were re-ordered around the shared kind derivation; without this test a
    // future refactor could re-invert the ordering and no assertion would flip.
    @Test
    fun `annotationSyncRow shows Waiting for first sync when NeverRun even with pending books`() = runTest {
        val config = MutableStateFlow<AnnotationSyncConfig?>(AnnotationSyncConfig("https://srv.example/dav/", "alice", "pw"))
        val status = AnnotationSyncStatusStore() // NeverRun
        val vm = newSettingsViewModel(
            configStore = stubConfigStore(config), statusStore = status,
            annotationDao = stubAnnotationDao(pendingBookCount = 3),
        )
        backgroundScope.launch { vm.annotationSyncRow.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val row = vm.annotationSyncRow.value
        assertEquals(AnnotationSyncRowState.Badge.Pending, row.badge)
        assertTrue("sub should be 'Waiting for first sync…', got: ${row.sub}", row.sub.contains("Waiting for first sync"))
    }

    @Test
    fun `annotationSyncRow is Synced when configured + Success + no pending`() = runTest {
        val config = MutableStateFlow<AnnotationSyncConfig?>(AnnotationSyncConfig("https://srv.example/dav/", "alice", "pw"))
        val status = AnnotationSyncStatusStore().apply { report(CycleOutcome.Success(1_000L)) }
        val vm = newSettingsViewModel(
            configStore = stubConfigStore(config), statusStore = status,
            annotationDao = stubAnnotationDao(pendingBookCount = 0),
        )
        backgroundScope.launch { vm.annotationSyncRow.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val row = vm.annotationSyncRow.value
        assertEquals(AnnotationSyncRowState.Badge.Synced, row.badge)
        assertTrue(row.sub.contains("Synced"))
        assertTrue(row.sub.contains("alice"))
    }

    @Test
    fun `annotationSyncRow is Pending when configured + pending count gt 0`() = runTest {
        val config = MutableStateFlow<AnnotationSyncConfig?>(AnnotationSyncConfig("https://srv.example/dav/", "alice", "pw"))
        val status = AnnotationSyncStatusStore().apply { report(CycleOutcome.Failed.Network(1_000L, "offline")) }
        val vm = newSettingsViewModel(
            configStore = stubConfigStore(config), statusStore = status,
            annotationDao = stubAnnotationDao(pendingBookCount = 2),
        )
        backgroundScope.launch { vm.annotationSyncRow.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val row = vm.annotationSyncRow.value
        assertEquals(AnnotationSyncRowState.Badge.Pending, row.badge)
        assertTrue(row.sub.contains("2 book"))
        assertEquals(AnnotationSyncRowState.Tone.Pending, row.subTone)
    }

    @Test
    fun `annotationSyncRow is Error when Auth failed`() = runTest {
        val config = MutableStateFlow<AnnotationSyncConfig?>(AnnotationSyncConfig("https://srv.example/dav/", "alice", "pw"))
        val status = AnnotationSyncStatusStore().apply { report(CycleOutcome.Failed.Auth(1_000L, 401)) }
        val vm = newSettingsViewModel(
            configStore = stubConfigStore(config), statusStore = status,
            annotationDao = stubAnnotationDao(pendingBookCount = 0),
        )
        backgroundScope.launch { vm.annotationSyncRow.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val row = vm.annotationSyncRow.value
        assertEquals(AnnotationSyncRowState.Badge.Error, row.badge)
        assertTrue(row.sub.contains("Authentication failed"))
        assertEquals(AnnotationSyncRowState.Tone.Error, row.subTone)
    }

    @Test
    fun `annotationSyncRow is Error with Tls copy when Tls failure`() = runTest {
        val config = MutableStateFlow<AnnotationSyncConfig?>(AnnotationSyncConfig("https://srv.example/dav/", "alice", "pw"))
        val status = AnnotationSyncStatusStore().apply { report(CycleOutcome.Failed.Tls(1_000L, "cert untrusted")) }
        val vm = newSettingsViewModel(
            configStore = stubConfigStore(config), statusStore = status,
            annotationDao = stubAnnotationDao(pendingBookCount = 0),
        )
        backgroundScope.launch { vm.annotationSyncRow.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val row = vm.annotationSyncRow.value
        assertEquals(AnnotationSyncRowState.Badge.Error, row.badge)
        assertTrue(row.sub.contains("TLS error"))
    }

    // Regression: the WebDAV row copy is "$N book(s) pending" and N must reflect *books*, not
    // dirty annotation rows. Wired through AnnotationDao.observePendingBookCountAcrossAll, which
    // counts distinct (sourceId, itemId). This test pins the wording; AnnotationDaoTest pins the
    // SQL. Together they prevent the "8 highlights on 1 book showing as '8 books pending'" bug.
    @Test
    fun `annotationSyncRow reads book count into 'N book(s) pending' copy`() = runTest {
        val config = MutableStateFlow<AnnotationSyncConfig?>(AnnotationSyncConfig("https://srv.example/dav/", "alice", "pw"))
        val status = AnnotationSyncStatusStore().apply { report(CycleOutcome.Success(1_000L)) }
        val vm = newSettingsViewModel(
            configStore = stubConfigStore(config), statusStore = status,
            annotationDao = stubAnnotationDao(pendingBookCount = 3),
        )
        backgroundScope.launch { vm.annotationSyncRow.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val row = vm.annotationSyncRow.value
        assertEquals(AnnotationSyncRowState.Badge.Pending, row.badge)
        assertTrue("sub should read '3 book(s) pending' — got: ${row.sub}", row.sub.contains("3 book(s) pending"))
    }

    @Test
    fun `annotationSyncRow is Pending when Network failure with no pending books`() = runTest {
        val config = MutableStateFlow<AnnotationSyncConfig?>(AnnotationSyncConfig("https://srv.example/dav/", "alice", "pw"))
        val status = AnnotationSyncStatusStore().apply { report(CycleOutcome.Failed.Network(1_000L, "timeout")) }
        val vm = newSettingsViewModel(
            configStore = stubConfigStore(config), statusStore = status,
            annotationDao = stubAnnotationDao(pendingBookCount = 0),
        )
        backgroundScope.launch { vm.annotationSyncRow.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val row = vm.annotationSyncRow.value
        assertEquals(AnnotationSyncRowState.Badge.Pending, row.badge)
        assertTrue("sub should mention Offline", row.sub.contains("Offline"))
        assertEquals(AnnotationSyncRowState.Tone.Pending, row.subTone)
    }
}
