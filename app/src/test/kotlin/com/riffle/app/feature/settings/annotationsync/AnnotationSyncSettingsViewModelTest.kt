package com.riffle.app.feature.settings.annotationsync

import com.riffle.core.data.AnnotationSyncStatusStore
import com.riffle.core.data.CycleOutcome
import com.riffle.core.data.WebDavAnnotationSyncTargetFactory
import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationSyncSettingsViewModelTest {

    private val configStore = FakeAnnotationSyncConfigStore()
    private lateinit var server: MockWebServer
    private val factory by lazy { WebDavAnnotationSyncTargetFactory(OkHttpClient()) }

    @Before
    fun setUp() {
        // Run viewModelScope.launch{} inline so test assertions don't race with init {}.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
    }

    private fun newViewModel() = AnnotationSyncSettingsViewModel(
        configStore = configStore,
        targetFactory = factory,
        statusStore = AnnotationSyncStatusStore(),
        sweepEnqueuer = RecordingEnqueuer(),
    )

    private fun newViewModel(
        configFlow: MutableStateFlow<AnnotationSyncConfig?>,
        outcome: CycleOutcome,
        enqueuer: AnnotationSweepEnqueuer = RecordingEnqueuer(),
    ): AnnotationSyncSettingsViewModel {
        val store = object : AnnotationSyncConfigStore {
            override fun observe() = configFlow
            override suspend fun save(config: AnnotationSyncConfig) { configFlow.value = config }
            override suspend fun clear() { configFlow.value = null }
        }
        val statusStore = AnnotationSyncStatusStore().apply { report(outcome) }
        return AnnotationSyncSettingsViewModel(
            configStore = store,
            targetFactory = factory,
            statusStore = statusStore,
            sweepEnqueuer = enqueuer,
        )
    }

    /** Awaits the next state that is NOT in the Testing phase, with a real-time bound. */
    private fun awaitSettled(vm: AnnotationSyncSettingsViewModel) = runBlocking {
        withTimeout(3_000L) {
            vm.state.first { it.testResult !is TestConnectionUiState.Testing }
        }
    }

    @Test
    fun `initial form is empty when no config saved`() {
        val vm = newViewModel()

        assertEquals("", vm.state.value.baseUrl)
        assertEquals("", vm.state.value.username)
        assertEquals("", vm.state.value.password)
        assertEquals(TestConnectionUiState.Idle, vm.state.value.testResult)
    }

    @Test
    fun `loads existing config into form on init`() = runBlocking {
        configStore.save(
            AnnotationSyncConfig("https://x.example.org/dav", "alice", "secret"),
        )
        val vm = newViewModel()

        assertEquals("https://x.example.org/dav", vm.state.value.baseUrl)
        assertEquals("alice", vm.state.value.username)
        assertEquals("secret", vm.state.value.password)
    }

    @Test
    fun `testConnection on PROPFIND 207 marks Success`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"/>"))
        val vm = newViewModel()
        vm.onBaseUrlChanged(server.url("/dav").toString())
        vm.onUsernameChanged("u")
        vm.onPasswordChanged("p")

        vm.onTestConnection()

        assertEquals(TestConnectionUiState.Success, awaitSettled(vm).testResult)
    }

    @Test
    fun `testConnection on 401 marks AuthFailed`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val vm = newViewModel()
        vm.onBaseUrlChanged(server.url("/dav").toString())
        vm.onUsernameChanged("u")
        vm.onPasswordChanged("wrong")

        vm.onTestConnection()

        assertEquals(TestConnectionUiState.AuthFailed, awaitSettled(vm).testResult)
    }

    @Test
    fun `testConnection with malformed URL marks InvalidUrl without hitting network`() {
        val vm = newViewModel()
        vm.onBaseUrlChanged("::nope::")
        vm.onUsernameChanged("u")
        vm.onPasswordChanged("p")

        vm.onTestConnection()

        assertTrue(vm.state.value.testResult is TestConnectionUiState.InvalidUrl)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `save persists the current form to the config store`() = runBlocking {
        val vm = newViewModel()
        vm.onBaseUrlChanged("https://x")
        vm.onUsernameChanged("u")
        vm.onPasswordChanged("p")

        vm.onSave()

        // saving runs on viewModelScope (Main = Unconfined), so the launch resolves inline.
        assertEquals(
            AnnotationSyncConfig("https://x", "u", "p"),
            configStore.observe().first(),
        )
    }

    @Test
    fun `save emits a closeRequests event so the screen can pop back`() = runBlocking {
        val vm = newViewModel()
        vm.onBaseUrlChanged("https://x")
        vm.onUsernameChanged("u")
        vm.onPasswordChanged("p")

        vm.onSave()

        withTimeout(1_000L) { vm.closeRequests.first() }
        // and the config was persisted
        assertEquals(
            AnnotationSyncConfig("https://x", "u", "p"),
            configStore.observe().first(),
        )
    }

    @Test
    fun `clear wipes both the form and the store`() = runBlocking {
        configStore.save(AnnotationSyncConfig("https://x", "u", "p"))
        val vm = newViewModel()

        vm.onClear()

        assertEquals("", vm.state.value.baseUrl)
        assertNull(configStore.observe().first())
    }

    @Test
    fun `screenState is Unconfigured when configStore is null`() = runTest {
        val vm = newViewModel(
            configFlow = MutableStateFlow(null),
            outcome = CycleOutcome.NeverRun,
        )
        advanceUntilIdle()
        assertTrue(vm.screenState.value is AnnotationSyncScreenState.Unconfigured)
    }

    @Test
    fun `screenState is Configured Synced when configured + Success`() = runTest {
        val vm = newViewModel(
            configFlow = MutableStateFlow(AnnotationSyncConfig("https://srv.example/dav/", "alice", "pw")),
            outcome = CycleOutcome.Success(1_000L),
        )
        advanceUntilIdle()
        val state = vm.screenState.value
        assertTrue(state is AnnotationSyncScreenState.Configured)
        assertTrue((state as AnnotationSyncScreenState.Configured).status is AnnotationSyncScreenState.StatusBadge.Synced)
        assertEquals("https://srv.example/dav/", state.baseUrl)
        assertEquals("alice", state.username)
    }

    @Test
    fun `screenState is Configured Error Auth when Auth failure`() = runTest {
        val vm = newViewModel(
            configFlow = MutableStateFlow(AnnotationSyncConfig("https://srv.example/dav/", "alice", "pw")),
            outcome = CycleOutcome.Failed.Auth(1_000L, 401),
        )
        advanceUntilIdle()
        val state = vm.screenState.value
        assertTrue(state is AnnotationSyncScreenState.Configured)
        assertTrue((state as AnnotationSyncScreenState.Configured).status is AnnotationSyncScreenState.StatusBadge.Error.Auth)
    }

    @Test
    fun `onSave persists config and enqueues a sweep`() = runTest {
        val flow = MutableStateFlow<AnnotationSyncConfig?>(null)
        val enqueuer = RecordingEnqueuer()
        val vm = newViewModel(configFlow = flow, outcome = CycleOutcome.NeverRun, enqueuer = enqueuer)
        vm.onBaseUrlChanged("https://srv.example/dav/")
        vm.onUsernameChanged("alice")
        vm.onPasswordChanged("pw")
        vm.onSave()
        advanceUntilIdle()

        assertEquals(AnnotationSyncConfig("https://srv.example/dav/", "alice", "pw"), flow.value)
        assertEquals(1, enqueuer.enqueueCalls)
    }
}

private class RecordingEnqueuer : AnnotationSweepEnqueuer {
    var enqueueCalls = 0
    override fun enqueue() { enqueueCalls++ }
}

private class FakeAnnotationSyncConfigStore : AnnotationSyncConfigStore {
    private val state = MutableStateFlow<AnnotationSyncConfig?>(null)
    override fun observe() = state
    override suspend fun save(config: AnnotationSyncConfig) { state.value = config }
    override suspend fun clear() { state.value = null }
}

