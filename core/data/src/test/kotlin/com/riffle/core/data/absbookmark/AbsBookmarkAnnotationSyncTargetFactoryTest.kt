package com.riffle.core.data.absbookmark

import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.network.AbsBookmarkApi
import com.riffle.core.network.NetworkAbsBookmark
import com.riffle.core.network.NetworkResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AbsBookmarkAnnotationSyncTargetFactoryTest {

    private val tokenStorage = InMemoryTokenStorage().apply {
        savedTokens["source-1"] = "token-abc"
    }
    private val api = NoopApi
    private val factory = AbsBookmarkAnnotationSyncTargetFactory(api, tokenStorage)

    private fun source(
        username: String,
        type: SourceType = SourceType.ABS,
        serverType: ServerType = ServerType.AUDIOBOOKSHELF,
        absUserId: String? = "abs-user-1",
        id: String = "source-1",
    ) = Source(
        id = id,
        url = SourceUrl.parse("http://abs.local")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = username,
        type = type,
        serverType = serverType,
        absUserId = absUserId,
    )

    @Test
    fun `create returns a target for allow-listed username 'plamen'`() = runTest {
        assertNotNull(factory.create(source(username = "plamen")))
    }

    @Test
    fun `create returns a target for allow-listed username 'test'`() = runTest {
        assertNotNull(factory.create(source(username = "test")))
    }

    @Test
    fun `create returns null for other usernames — temporary rollout gate`() = runTest {
        assertNull(factory.create(source(username = "alice")))
        assertNull(factory.create(source(username = "bob")))
        assertNull(factory.create(source(username = "")))
    }

    @Test
    fun `allow-list is case-insensitive and trims whitespace`() = runTest {
        assertNotNull(factory.create(source(username = "Plamen")))
        assertNotNull(factory.create(source(username = "TEST")))
        assertNotNull(factory.create(source(username = "  plamen  ")))
    }

    @Test
    fun `create returns null for non-ABS source even with allow-listed username`() = runTest {
        assertNull(factory.create(source(username = "plamen", type = SourceType.LOCAL_FILES)))
    }

    @Test
    fun `create returns null when absUserId is missing`() = runTest {
        assertNull(factory.create(source(username = "plamen", absUserId = null)))
        assertNull(factory.create(source(username = "plamen", absUserId = "  ")))
    }

    @Test
    fun `create returns null when the source has no stored token`() = runTest {
        assertNull(factory.create(source(username = "plamen", id = "no-token-source")))
    }
}

private class InMemoryTokenStorage : TokenStorage {
    val savedTokens: MutableMap<String, String> = mutableMapOf()
    override suspend fun saveToken(sourceId: String, token: String) { savedTokens[sourceId] = token }
    override suspend fun getToken(sourceId: String): String? = savedTokens[sourceId]
    override suspend fun deleteToken(sourceId: String) { savedTokens.remove(sourceId) }
}

private object NoopApi : AbsBookmarkApi {
    override suspend fun createBookmark(
        baseUrl: String, itemId: String, timeSec: Int, title: String, token: String, insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> = NetworkResult.Success(NetworkAbsBookmark(itemId, title, timeSec, 0L))
    override suspend fun updateBookmark(
        baseUrl: String, itemId: String, timeSec: Int, title: String, token: String, insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> = NetworkResult.Success(NetworkAbsBookmark(itemId, title, timeSec, 0L))
    override suspend fun deleteBookmark(
        baseUrl: String, itemId: String, timeSec: Int, token: String, insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> = NetworkResult.Success(NetworkAbsBookmark(itemId, "", timeSec, 0L))
    override suspend fun listBookmarks(
        baseUrl: String, token: String, insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkAbsBookmark>> = NetworkResult.Success(emptyList())
}
