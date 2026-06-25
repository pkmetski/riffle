package com.riffle.app.feature.server

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.data.AnnotationSyncStatusStore
import com.riffle.core.data.WebDavAnnotationSyncTargetFactory
import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.domain.Library
import com.riffle.core.domain.PendingServer
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
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
class AddServerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeServer() = Server(
        id = "s1",
        url = ServerUrl.parse("https://abs.example.com")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
    )

    private fun fakePending(
        libraries: List<Library> = listOf(
            Library("lib-1", "Books", "book", false),
            Library("lib-2", "Comics", "book", false),
        ),
    ) = PendingServer(
        url = ServerUrl.parse("https://abs.example.com")!!,
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
        private val commitResult: CommitServerResult = CommitServerResult.Success(
            Server(
                id = "s1",
                url = ServerUrl.parse("https://abs.example.com")!!,
                isActive = true,
                insecureConnectionAllowed = false,
                username = "",
            )
        ),
    ) : ServerRepository {
        var commitCallCount = 0
        var lastInsecureAllowed: Boolean? = null
        var lastServerType: com.riffle.core.domain.ServerType? = null
        override fun observeAll(): Flow<List<Server>> = emptyFlow()
        override suspend fun getActive(): Server? = null
        override suspend fun authenticate(
            url: ServerUrl,
            username: String,
            password: String,
            insecureAllowed: Boolean,
            serverType: com.riffle.core.domain.ServerType,
        ): AuthenticateResult {
            lastInsecureAllowed = insecureAllowed
            lastServerType = serverType
            return authResult
        }
        override suspend fun commit(pending: PendingServer, hiddenLibraryIds: Set<String>): CommitServerResult {
            commitCallCount += 1
            return commitResult
        }
        override suspend fun setActive(serverId: String) {}
        override suspend fun remove(serverId: String) {}
        override suspend fun getServerVersion(serverId: String): String? = null
    }

    private fun fakeRepo(authResult: AuthenticateResult): ServerRepository =
        RecordingRepository(authResult)

    private object NullConfigStore : AnnotationSyncConfigStore {
        override fun observe(): StateFlow<AnnotationSyncConfig?> = MutableStateFlow(null)
        override suspend fun save(config: AnnotationSyncConfig) {}
        override suspend fun clear() {}
    }

    private fun makeVm(
        repository: ServerRepository,
        savedState: SavedStateHandle = SavedStateHandle(),
        configStore: AnnotationSyncConfigStore = NullConfigStore,
    ): AddServerViewModel = AddServerViewModel(
        repository = repository,
        webdavConfigStore = configStore,
        webdavTargetFactory = WebDavAnnotationSyncTargetFactory(OkHttpClient()),
        webdavStatusStore = AnnotationSyncStatusStore(),
        sweepEnqueuer = AnnotationSweepEnqueuer { },
        storytellerSyncer = io.mockk.mockk(relaxed = true),
        readaloudMatcher = io.mockk.mockk(relaxed = true),
        tokenStorage = io.mockk.mockk(relaxed = true),
        savedStateHandle = savedState,
    )

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
            commitResult = CommitServerResult.Failure(RuntimeException("disk full")),
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
}
