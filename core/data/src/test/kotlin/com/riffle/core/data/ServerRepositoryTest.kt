package com.riffle.core.data

import com.riffle.core.database.ServerDao
import com.riffle.core.database.ServerEntity
import com.riffle.core.domain.AddServerResult
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsApi
import com.riffle.core.network.NetworkLoginResult
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRepositoryTest {

    private val fakeTokenStorage = object : TokenStorage {
        val tokens = mutableMapOf<String, String>()
        override suspend fun saveToken(serverId: String, token: String) { tokens[serverId] = token }
        override suspend fun getToken(serverId: String) = tokens[serverId]
        override suspend fun deleteToken(serverId: String) { tokens.remove(serverId) }
    }

    private fun fakeDao(active: ServerEntity? = null): ServerDao = object : ServerDao {
        val store = mutableListOf<ServerEntity>()
        init { active?.let { store.add(it) } }
        override fun observeAll() = flowOf(store.toList())
        override suspend fun getActive() = store.firstOrNull { it.isActive }
        override suspend fun upsert(server: ServerEntity) { store.add(server) }
        override suspend fun clearActiveFlag() { store.replaceAll { it.copy(isActive = false) } }
        override suspend fun setActive(id: String) { store.replaceAll { if (it.id == id) it.copy(isActive = true) else it } }
        override suspend fun deleteById(id: String) { store.removeAll { it.id == id } }
    }

    @Test
    fun `addServer success stores token and returns Success`() = runTest {
        val fakeApi = AbsApi { _, _, _, _ -> NetworkLoginResult.Success("uid-1", "tok-xyz") }
        val repo = ServerRepositoryImpl(fakeDao(), fakeTokenStorage, fakeApi)
        val url = ServerUrl.parse("https://abs.example.com")!!
        val result = repo.addServer(url, "admin", "pass")
        assertTrue(result is AddServerResult.Success)
        assertEquals("tok-xyz", fakeTokenStorage.tokens.values.first())
    }

    @Test
    fun `addServer wrong password returns WrongCredentials`() = runTest {
        val fakeApi = AbsApi { _, _, _, _ -> NetworkLoginResult.WrongCredentials("Invalid username or password") }
        val repo = ServerRepositoryImpl(fakeDao(), fakeTokenStorage, fakeApi)
        val url = ServerUrl.parse("https://abs.example.com")!!
        val result = repo.addServer(url, "admin", "wrongpass")
        assertTrue(result is AddServerResult.WrongCredentials)
    }

    @Test
    fun `remove deletes server entity and token`() = runTest {
        val entity = ServerEntity("srv-1", "https://abs.example.com", "abs.example.com", true, false)
        val dao = fakeDao(active = entity)
        fakeTokenStorage.tokens["srv-1"] = "tok"
        val fakeApi = AbsApi { _, _, _, _ -> NetworkLoginResult.WrongCredentials("") }
        val repo = ServerRepositoryImpl(dao, fakeTokenStorage, fakeApi)
        repo.remove("srv-1")
        assertTrue(fakeTokenStorage.tokens.isEmpty())
    }

    @Test
    fun `first added server is set as active`() = runTest {
        val dao = fakeDao()
        val fakeApi = AbsApi { _, _, _, _ -> NetworkLoginResult.Success("uid-1", "tok") }
        val repo = ServerRepositoryImpl(dao, fakeTokenStorage, fakeApi)
        val url = ServerUrl.parse("https://abs.example.com")!!
        val result = repo.addServer(url, "admin", "pass")
        assertTrue(result is AddServerResult.Success)
        val server = (result as AddServerResult.Success).server
        assertTrue(server.isActive)
    }
}
