package com.riffle.app.feature.server

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
import kotlinx.coroutines.flow.emptyFlow
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
        displayName = "abs.example.com",
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
    )

    private fun fakePending() = PendingServer(
        url = ServerUrl.parse("https://abs.example.com")!!,
        displayName = "abs.example.com",
        username = "admin",
        userId = "uid",
        token = "tok",
        insecureConnectionAllowed = false,
        libraries = listOf(Library("lib-1", "Books", "book", false)),
    )

    private class RecordingRepository(
        private val authResult: AuthenticateResult,
        private val commitResult: CommitServerResult = CommitServerResult.Success(
            Server(
                id = "s1",
                url = ServerUrl.parse("https://abs.example.com")!!,
                displayName = "abs.example.com",
                isActive = true,
                insecureConnectionAllowed = false,
                username = "",
            )
        ),
    ) : ServerRepository {
        var commitCallCount = 0
        var lastInsecureAllowed: Boolean? = null
        override fun observeAll(): Flow<List<Server>> = emptyFlow()
        override suspend fun getActive(): Server? = null
        override suspend fun authenticate(
            url: ServerUrl,
            username: String,
            password: String,
            insecureAllowed: Boolean,
        ): AuthenticateResult {
            lastInsecureAllowed = insecureAllowed
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

    @Test
    fun `onConnect with invalid url sets error`() = runTest {
        val vm = AddServerViewModel(fakeRepo(AuthenticateResult.Success(fakePending())))
        vm.url = "not-a-url"
        vm.username = "admin"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.error)
        assertNull(vm.insecureWarning)
    }

    @Test
    fun `onConnect with http url shows insecure warning`() = runTest {
        val vm = AddServerViewModel(fakeRepo(AuthenticateResult.Success(fakePending())))
        vm.url = "http://abs.example.com"
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
        val vm = AddServerViewModel(repo)
        vm.url = "https://abs.example.com"
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
    fun `onConnect wrong credentials sets error`() = runTest {
        val vm = AddServerViewModel(fakeRepo(AuthenticateResult.WrongCredentials("Bad creds")))
        vm.url = "https://abs.example.com"
        vm.username = "admin"
        vm.password = "wrong"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Bad creds", vm.error)
    }

    @Test
    fun `onConnect network error sets connection-failed message`() = runTest {
        val vm = AddServerViewModel(fakeRepo(AuthenticateResult.NetworkError(Exception("timeout"))))
        vm.url = "https://abs.example.com"
        vm.username = "admin"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.error?.contains("Connection failed") == true)
    }

    @Test
    fun `onInsecureWarningDismissed clears warning`() = runTest {
        val vm = AddServerViewModel(fakeRepo(AuthenticateResult.Success(fakePending())))
        vm.url = "http://abs.example.com"
        vm.username = "admin"
        vm.onConnect()
        vm.onInsecureWarningDismissed()
        assertNull(vm.insecureWarning)
    }

    @Test
    fun `onInsecureWarningAccepted calls authenticate with insecureAllowed true`() = runTest {
        val repo = RecordingRepository(AuthenticateResult.Success(fakePending()))
        val vm = AddServerViewModel(repo)
        vm.url = "http://abs.example.com"
        vm.username = "admin"
        vm.onInsecureWarningAccepted()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue("insecureAllowed must be true", repo.lastInsecureAllowed == true)
    }

    @Test
    fun `isLoading is false after login completes`() = runTest {
        val vm = AddServerViewModel(fakeRepo(AuthenticateResult.WrongCredentials("x")))
        vm.url = "https://abs.example.com"
        vm.username = "admin"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.isLoading)
    }
}
