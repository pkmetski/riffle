package com.riffle.core.sources.abs

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkLibrary
import com.riffle.core.network.NetworkLoginUser
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.StorytellerApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsSourceAdapterTest {

    private val url = SourceUrl.parse("https://abs.example.com")!!

    private fun absApi(result: NetworkResult<NetworkLoginUser>): AbsApi =
        AbsApi { _, _, _, _ -> result }

    private fun libsApi(libs: List<NetworkLibrary>): AbsLibraryApi =
        object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkResult.Success(libs)
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                throw NotImplementedError()
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                throw NotImplementedError()
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                throw NotImplementedError()
        }

    private val storytellerNotCalled: StorytellerApi = StorytellerApi { _, _, _, _ -> error("unexpected") }

    @Test fun `ABS login success maps to AuthenticateResult Success`() = runTest {
        val adapter = AbsSourceAdapter(
            absApi = absApi(NetworkResult.Success(NetworkLoginUser("uid1", "tok", "alice"))),
            libraryApi = libsApi(listOf(
                NetworkLibrary("L1", "Books", "book", audiobooksOnly = false),
                NetworkLibrary("L2", "Podcasts", "podcast", audiobooksOnly = false),
            )),
            storytellerApi = storytellerNotCalled,
        )

        val result = adapter.authenticate(url, "alice", "secret", insecureAllowed = false, serverType = ServerType.AUDIOBOOKSHELF)

        assertTrue(result is AuthenticateResult.Success)
        val pending = (result as AuthenticateResult.Success).pending
        assertEquals(SourceType.ABS, pending.sourceType)
        assertEquals("alice", pending.username)
        assertEquals("tok", pending.token)
        assertEquals(1, pending.libraries.size)
        assertEquals("L1", pending.libraries[0].id)
        assertEquals("Books", pending.libraries[0].name)
    }

    @Test fun `ABS login filters out non-book libraries`() = runTest {
        val adapter = AbsSourceAdapter(
            absApi = absApi(NetworkResult.Success(NetworkLoginUser("uid1", "tok", "alice"))),
            libraryApi = libsApi(listOf(
                NetworkLibrary("L1", "Books", "book", audiobooksOnly = false),
                NetworkLibrary("L2", "Podcasts", "podcast", audiobooksOnly = false),
                NetworkLibrary("L3", "Music", "music", audiobooksOnly = false),
            )),
            storytellerApi = storytellerNotCalled,
        )

        val result = adapter.authenticate(url, "alice", "secret", insecureAllowed = false, serverType = ServerType.AUDIOBOOKSHELF)

        assertTrue(result is AuthenticateResult.Success)
        assertEquals(1, (result as AuthenticateResult.Success).pending.libraries.size)
    }

    @Test fun `ABS 401 maps to WrongCredentials`() = runTest {
        val adapter = AbsSourceAdapter(
            absApi = absApi(NetworkResult.Auth),
            libraryApi = libsApi(emptyList()),
            storytellerApi = storytellerNotCalled,
        )

        val result = adapter.authenticate(url, "alice", "wrong", insecureAllowed = false, serverType = ServerType.AUDIOBOOKSHELF)

        assertTrue(result is AuthenticateResult.WrongCredentials)
    }

    @Test fun `ABS Offline maps to NetworkError`() = runTest {
        val adapter = AbsSourceAdapter(
            absApi = absApi(NetworkResult.Offline(java.io.IOException("offline"))),
            libraryApi = libsApi(emptyList()),
            storytellerApi = storytellerNotCalled,
        )

        val result = adapter.authenticate(url, "alice", "secret", insecureAllowed = false, serverType = ServerType.AUDIOBOOKSHELF)

        assertTrue(result is AuthenticateResult.NetworkError)
    }

    @Test fun `Storyteller success maps to AuthenticateResult Success with empty libraries`() = runTest {
        val storytellerApi = StorytellerApi { _, _, _, _ -> NetworkResult.Success("storyteller-token") }
        val adapter = AbsSourceAdapter(
            absApi = absApi(NetworkResult.Unknown(RuntimeException("should not be called"))),
            libraryApi = libsApi(emptyList()),
            storytellerApi = storytellerApi,
        )

        val result = adapter.authenticate(url, "alice", "secret", insecureAllowed = false, serverType = ServerType.STORYTELLER_SERVICE)

        assertTrue(result is AuthenticateResult.Success)
        val pending = (result as AuthenticateResult.Success).pending
        assertEquals("storyteller-token", pending.token)
        assertEquals(0, pending.libraries.size)
        assertEquals(ServerType.STORYTELLER_SERVICE, pending.serverType)
    }

    @Test fun `Storyteller 401 maps to WrongCredentials`() = runTest {
        val storytellerApi = StorytellerApi { _, _, _, _ -> NetworkResult.Auth }
        val adapter = AbsSourceAdapter(
            absApi = absApi(NetworkResult.Unknown(RuntimeException("should not be called"))),
            libraryApi = libsApi(emptyList()),
            storytellerApi = storytellerApi,
        )

        val result = adapter.authenticate(url, "alice", "bad", insecureAllowed = false, serverType = ServerType.STORYTELLER_SERVICE)

        assertTrue(result is AuthenticateResult.WrongCredentials)
    }
}
