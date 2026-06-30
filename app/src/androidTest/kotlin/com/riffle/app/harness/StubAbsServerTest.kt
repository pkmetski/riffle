package com.riffle.app.harness

import com.riffle.core.domain.DefaultDispatcherProvider

import com.riffle.core.network.NetworkResult

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.network.AbsApiClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StubAbsServerTest {

    private lateinit var stub: StubAbsServer
    private lateinit var client: AbsApiClient

    @Before
    fun setUp() {
        stub = StubAbsServer()
        stub.start()
        client = AbsApiClient(OkHttpClient(), DefaultDispatcherProvider)
    }

    @After
    fun tearDown() = stub.shutdown()

    @Test
    fun stubServerStartsAndServesSuccessfulLoginResponse() = runTest {
        val result = client.login(stub.baseUrl, "testuser", "testpass", false)
        assertTrue(result is NetworkResult.Success)
        val success = result as NetworkResult.Success
        assertEquals(StubAbsServer.TEST_USER_ID, success.value.userId)
        assertEquals(StubAbsServer.TEST_TOKEN, success.value.token)
    }

    @Test
    fun stubServerServesLibraryListWithOneBookLibrary() = runTest {
        val result = client.getLibraries(stub.baseUrl, StubAbsServer.TEST_TOKEN, false)
        assertTrue(result is NetworkResult.Success)
        val libraries = (result as NetworkResult.Success).value
        assertEquals(1, libraries.size)
        assertEquals(StubAbsServer.TEST_LIBRARY_ID, libraries[0].id)
        assertEquals("book", libraries[0].mediaType)
    }
}
