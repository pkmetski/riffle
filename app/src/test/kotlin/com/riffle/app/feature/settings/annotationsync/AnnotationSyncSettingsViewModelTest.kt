package com.riffle.app.feature.settings.annotationsync

import com.riffle.core.data.WebDavAnnotationSyncTargetFactory
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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

    private fun newViewModel() = AnnotationSyncSettingsViewModel(configStore, factory)

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
}

private class FakeAnnotationSyncConfigStore : AnnotationSyncConfigStore {
    private val state = MutableStateFlow<AnnotationSyncConfig?>(null)
    override fun observe() = state
    override suspend fun save(config: AnnotationSyncConfig) { state.value = config }
    override suspend fun clear() { state.value = null }
}
