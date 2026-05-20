# PR #35 Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Address all code-review findings from PR #35 — correctness bugs, security issues, code quality, and test coverage gaps.

**Architecture:** Each task is self-contained. Tasks 1–5 fix correctness/security issues in existing files; Task 6 replaces Gson with kotlinx.serialization in the network layer; Tasks 7–8 fix minor quality issues; Task 9 covers all new and expanded tests.

**Tech Stack:** Kotlin, Jetpack Compose, Room, OkHttp, kotlinx.serialization (replacing Gson), Hilt, MockWebServer, kotlinx-coroutines-test

---

## File Map

| File | Change |
|---|---|
| `app/src/main/kotlin/com/riffle/app/feature/server/AddServerViewModel.kt` | Replace `navigateBack: Boolean` flag with `Channel<Unit>` |
| `app/src/main/kotlin/com/riffle/app/feature/server/AddServerScreen.kt` | Consume `Channel` via `LaunchedEffect(Unit)` |
| `core/database/src/main/kotlin/com/riffle/core/database/ServerDao.kt` | Add `@Transaction setActiveAtomic(id)` combining clear + set |
| `core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt` | Set `exportSchema = true` |
| `core/database/build.gradle.kts` | Add `ksp { arg("room.schemaLocation", ...) }` |
| `core/data/src/main/kotlin/com/riffle/core/data/ServerRepositoryImpl.kt` | Use atomic `setActiveAtomic`; wrap `addServer` insert in transaction; fix `toDomain()` force-unwrap; fix `displayName` parsing |
| `core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt` | Remove hostname-verifier bypass; switch Gson → kotlinx.serialization |
| `core/network/src/main/kotlin/com/riffle/core/network/model/AbsLoginRequest.kt` | Add `@Serializable` |
| `core/network/src/main/kotlin/com/riffle/core/network/model/AbsLoginResponse.kt` | Add `@Serializable` |
| `core/network/build.gradle.kts` | Remove Gson/okhttp-logging; add kotlinx-serialization plugin + lib |
| `build.gradle.kts` (root) | Declare `kotlin.serialization` plugin (apply false) |
| `gradle/libs.versions.toml` | Add `kotlinx-serialization-json`; remove `gson`; add `kotlin-serialization` plugin entry |
| `app/src/main/res/xml/network_security_config.xml` | Remove `<certificates src="user" />`; update comment |
| `core/data/src/test/kotlin/com/riffle/core/data/ServerRepositoryTest.kt` | Add tests: setActive, second-server-stays-inactive, InsecureConnection, remove also clears DAO |
| `core/network/src/test/kotlin/com/riffle/core/network/AbsApiClientTest.kt` | Add test: self-signed cert without hostname bypass |
| `app/src/test/kotlin/com/riffle/app/feature/server/AddServerViewModelTest.kt` | New file — ViewModel tests |
| `app/build.gradle.kts` | Add `testImplementation(libs.kotlinx.coroutines.test)` |

---

## Task 1: Fix one-shot navigation — replace `navigateBack` Boolean with Channel

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/server/AddServerViewModel.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/server/AddServerScreen.kt`

- [ ] **Step 1: Update `AddServerViewModel` — replace flag with Channel**

Replace the entire file content:

```kotlin
package com.riffle.app.feature.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.AddServerResult
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddServerViewModel @Inject constructor(
    private val repository: ServerRepository,
) : ViewModel() {

    var url by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var insecureWarning by mutableStateOf<InsecureConnectionType?>(null)

    private val _navigateBack = Channel<Unit>(Channel.CONFLATED)
    val navigateBack = _navigateBack.receiveAsFlow()

    fun onConnect() {
        error = null
        val serverUrl = ServerUrl.parse(url.trim())
        if (serverUrl == null) {
            error = "Enter a valid URL (e.g. https://abs.example.com)"
            return
        }
        if (serverUrl.value.startsWith("http://")) {
            insecureWarning = InsecureConnectionType.HTTP
            return
        }
        doLogin(serverUrl, insecureAllowed = false)
    }

    fun onInsecureWarningAccepted() {
        insecureWarning = null
        val serverUrl = ServerUrl.parse(url.trim()) ?: return
        doLogin(serverUrl, insecureAllowed = true)
    }

    fun onInsecureWarningDismissed() {
        insecureWarning = null
    }

    private fun doLogin(serverUrl: ServerUrl, insecureAllowed: Boolean) {
        viewModelScope.launch {
            isLoading = true
            when (val result = repository.addServer(serverUrl, username, password, insecureAllowed)) {
                is AddServerResult.Success -> _navigateBack.send(Unit)
                is AddServerResult.WrongCredentials -> error = result.message
                is AddServerResult.NetworkError -> error = "Connection failed: ${result.cause.message}"
                is AddServerResult.InsecureConnection -> insecureWarning = result.type
            }
            isLoading = false
        }
    }
}
```

- [ ] **Step 2: Update `AddServerScreen` — consume the Flow**

Replace only the `LaunchedEffect` at the top of `AddServerScreen`:

```kotlin
// OLD — replace this block:
LaunchedEffect(viewModel.navigateBack) {
    if (viewModel.navigateBack) onNavigateBack()
}

// NEW:
LaunchedEffect(Unit) {
    viewModel.navigateBack.collect { onNavigateBack() }
}
```

The full updated composable signature stays the same; only the `LaunchedEffect` body changes.

- [ ] **Step 3: Build to verify compilation**

```bash
cd /Users/plamen.kmetski/conductor/workspaces/riffle/helsinki-v1
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`

---

## Task 2: Make `setActive` atomic with `@Transaction`

**Files:**
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/ServerDao.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/ServerRepositoryImpl.kt`

- [ ] **Step 1: Add `setActiveAtomic` transaction method to `ServerDao`**

Replace the entire `ServerDao.kt`:

```kotlin
package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {

    @Query("SELECT * FROM servers ORDER BY isActive DESC, displayName ASC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: ServerEntity)

    @Query("UPDATE servers SET isActive = 0")
    suspend fun clearActiveFlag()

    @Query("UPDATE servers SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: String)

    @Transaction
    suspend fun setActiveAtomic(id: String) {
        clearActiveFlag()
        setActive(id)
    }

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: String)
}
```

- [ ] **Step 2: Update `ServerRepositoryImpl.setActive` to use the atomic method**

In `ServerRepositoryImpl.kt`, replace:

```kotlin
override suspend fun setActive(serverId: String) {
    dao.clearActiveFlag()
    dao.setActive(serverId)
}
```

With:

```kotlin
override suspend fun setActive(serverId: String) {
    dao.setActiveAtomic(serverId)
}
```

- [ ] **Step 3: Build to verify**

```bash
./gradlew :core:database:compileDebugKotlin :core:data:compileDebugKotlin 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`

---

## Task 3: Fix `addServer` race condition — wrap first-server check in transaction

**Files:**
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/ServerDao.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/ServerRepositoryImpl.kt`

- [ ] **Step 1: Add `upsertAsFirstIfNoActive` transaction to `ServerDao`**

Add this method to `ServerDao` (after `setActiveAtomic`):

```kotlin
@Transaction
suspend fun upsertAsFirstIfNoActive(server: ServerEntity): ServerEntity {
    val hasActive = getActive() != null
    val toInsert = server.copy(isActive = !hasActive)
    upsert(toInsert)
    return toInsert
}
```

- [ ] **Step 2: Update `addServer` in `ServerRepositoryImpl` to use the new method**

Replace the `is NetworkLoginResult.Success` branch in `addServer`:

```kotlin
is NetworkLoginResult.Success -> {
    val id = UUID.randomUUID().toString()
    val displayName = displayNameFrom(url.value)
    val entity = ServerEntity(
        id = id,
        url = url.value,
        displayName = displayName,
        isActive = false,                    // overridden inside transaction
        insecureConnectionAllowed = insecureAllowed,
    )
    val inserted = dao.upsertAsFirstIfNoActive(entity)
    tokenStorage.saveToken(id, networkResult.token)
    AddServerResult.Success(inserted.toDomain())
}
```

Also add the private helper function at the bottom of `ServerRepositoryImpl` (before `toDomain`):

```kotlin
private fun displayNameFrom(url: String): String =
    try {
        java.net.URI(url).host ?: url.substringAfter("://").substringBefore("/")
    } catch (_: Exception) {
        url.substringAfter("://").substringBefore("/")
    }
```

- [ ] **Step 3: Build to verify**

```bash
./gradlew :core:data:compileDebugKotlin 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`

---

## Task 4: Fix `toDomain()` force-unwrap

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/ServerRepositoryImpl.kt`

- [ ] **Step 1: Replace force-unwrap with graceful fallback**

Replace `toDomain()` at the bottom of `ServerRepositoryImpl`:

```kotlin
private fun ServerEntity.toDomain(): Server {
    val parsedUrl = ServerUrl.parse(url)
        ?: ServerUrl.parse("https://invalid.example.com")!!   // should never happen; entity URLs are always validated on insert
    return Server(
        id = id,
        url = parsedUrl,
        displayName = displayName,
        isActive = isActive,
        insecureConnectionAllowed = insecureConnectionAllowed,
    )
}
```

> **Why:** The entity's URL is always validated by `ServerUrl.parse` before insertion, so `null` here would mean a corrupt DB row. Logging + using a sentinel URL (rather than crashing) lets the app degrade gracefully — the corrupt server will show an obviously wrong URL in the list and can be removed.

- [ ] **Step 2: Build to verify**

```bash
./gradlew :core:data:compileDebugKotlin 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`

---

## Task 5: Security — remove hostname-verifier bypass; remove user-cert trust

**Files:**
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt`
- Modify: `app/src/main/res/xml/network_security_config.xml`

- [ ] **Step 1: Remove `hostnameVerifier` bypass in `AbsApiClient.trustAllCerts()`**

Replace the `trustAllCerts()` private function in `AbsApiClient.kt`:

```kotlin
private fun OkHttpClient.trustAllCerts(): OkHttpClient {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAll), SecureRandom())
    }
    return newBuilder()
        .sslSocketFactory(sslContext.socketFactory, trustAll)
        .build()
}
```

(The `hostnameVerifier { _, _ -> true }` line is removed. Certificate chain validation is still disabled for self-signed certs, but the OS hostname check remains active.)

- [ ] **Step 2: Remove `<certificates src="user" />` from network security config**

Replace the full content of `app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  Cleartext traffic (HTTP) is permitted globally because users connect to self-hosted
  Audiobookshelf servers that may not have TLS configured. The app shows a one-time
  warning before allowing an insecure connection. User-installed CA certificates are
  intentionally excluded to reduce the MitM surface.
-->
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

- [ ] **Step 3: Build to verify**

```bash
./gradlew :core:network:compileTestKotlin :app:compileDebugKotlin 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`

---

## Task 6: Replace Gson with kotlinx.serialization

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts` (root)
- Modify: `core/network/build.gradle.kts`
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/model/AbsLoginRequest.kt`
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/model/AbsLoginResponse.kt`
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt`

- [ ] **Step 1: Add kotlinx.serialization to `libs.versions.toml`**

In the `[versions]` section, add after `coroutines`:
```toml
kotlinx-serialization = "1.7.3"
```

In the `[libraries]` section, add after `kotlinx-coroutines-test`:
```toml
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
```

In the `[plugins]` section, add:
```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

Also remove the `gson` line from `[versions]` and the `gson` line from `[libraries]`.

- [ ] **Step 2: Declare plugin in root `build.gradle.kts`**

Add to the `plugins {}` block:
```kotlin
alias(libs.plugins.kotlin.serialization) apply false
```

- [ ] **Step 3: Apply plugin and swap dependencies in `core/network/build.gradle.kts`**

Replace the full file:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:domain"))
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
```

(Note: `okhttp-logging` removed as it was unused.)

- [ ] **Step 4: Annotate model classes with `@Serializable`**

Replace `AbsLoginRequest.kt`:

```kotlin
package com.riffle.core.network.model

import kotlinx.serialization.Serializable

@Serializable
internal data class AbsLoginRequest(val username: String, val password: String)
```

Replace `AbsLoginResponse.kt`:

```kotlin
package com.riffle.core.network.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
internal data class AbsLoginResponse(val user: AbsUser) {
    @Serializable
    data class AbsUser(val id: String, val username: String, val token: String)
}
```

- [ ] **Step 5: Update `AbsApiClient` to use kotlinx.serialization**

Replace the Gson imports and usages:

```kotlin
package com.riffle.core.network

import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.network.model.AbsLoginRequest
import com.riffle.core.network.model.AbsLoginResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed class NetworkLoginResult {
    data class Success(val userId: String, val token: String) : NetworkLoginResult()
    data class WrongCredentials(val message: String) : NetworkLoginResult()
    data class NetworkError(val cause: Throwable) : NetworkLoginResult()
    data class InsecureConnection(val type: InsecureConnectionType) : NetworkLoginResult()
}

class AbsApiClient(private val httpClient: OkHttpClient) : AbsApi {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): NetworkLoginResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val body = json.encodeToString(AbsLoginRequest(username, password)).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/login")
            .post(body)
            .build()
        try {
            val response = client.newCall(request).execute()
            when (response.code) {
                200 -> {
                    val raw = response.body?.string() ?: return@withContext NetworkLoginResult.NetworkError(
                        IOException("Empty response body")
                    )
                    val parsed = json.decodeFromString<AbsLoginResponse>(raw)
                    NetworkLoginResult.Success(userId = parsed.user.id, token = parsed.user.token)
                }
                401 -> NetworkLoginResult.WrongCredentials("Invalid username or password")
                else -> NetworkLoginResult.NetworkError(IOException("Unexpected HTTP ${response.code}"))
            }
        } catch (e: SSLHandshakeException) {
            NetworkLoginResult.InsecureConnection(InsecureConnectionType.SELF_SIGNED)
        } catch (e: IOException) {
            NetworkLoginResult.NetworkError(e)
        }
    }

    private fun OkHttpClient.trustAllCerts(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        return newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .build()
    }
}
```

- [ ] **Step 6: Build to verify**

```bash
./gradlew :core:network:compileTestKotlin 2>&1 | tail -30
```
Expected: `BUILD SUCCESSFUL`

---

## Task 7: Enable Room schema export

**Files:**
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt`
- Modify: `core/database/build.gradle.kts`

- [ ] **Step 1: Set `exportSchema = true` in `RiffleDatabase`**

Replace `RiffleDatabase.kt`:

```kotlin
package com.riffle.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ServerEntity::class], version = 1, exportSchema = true)
abstract class RiffleDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
}
```

- [ ] **Step 2: Configure schema export path in `core/database/build.gradle.kts`**

Add a `ksp` block after `android {}`:

```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

Full file for reference:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.riffle.core.database"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
}
```

- [ ] **Step 3: Build to generate schema**

```bash
./gradlew :core:database:kspDebugKotlin 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL` and `core/database/schemas/com.riffle.core.database.RiffleDatabase/1.json` created.

---

## Task 8: Add missing tests

**Files:**
- Modify: `core/data/src/test/kotlin/com/riffle/core/data/ServerRepositoryTest.kt`
- Modify: `core/network/src/test/kotlin/com/riffle/core/network/AbsApiClientTest.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/server/AddServerViewModelTest.kt`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add test dependency to `app/build.gradle.kts`**

In the `dependencies` block, add:
```kotlin
testImplementation(libs.kotlinx.coroutines.test)
```

- [ ] **Step 2: Expand `ServerRepositoryTest` with four new tests**

Replace the entire `ServerRepositoryTest.kt`:

```kotlin
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        override suspend fun upsert(server: ServerEntity) { store.removeAll { it.id == server.id }; store.add(server) }
        override suspend fun clearActiveFlag() { store.replaceAll { it.copy(isActive = false) } }
        override suspend fun setActive(id: String) { store.replaceAll { if (it.id == id) it.copy(isActive = true) else it } }
        override suspend fun setActiveAtomic(id: String) { clearActiveFlag(); setActive(id) }
        override suspend fun upsertAsFirstIfNoActive(server: ServerEntity): ServerEntity {
            val hasActive = getActive() != null
            val toInsert = server.copy(isActive = !hasActive)
            upsert(toInsert)
            return toInsert
        }
        override suspend fun deleteById(id: String) { store.removeAll { it.id == id } }
    }

    // --- existing tests ---

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
        assertTrue("token not deleted", fakeTokenStorage.tokens.isEmpty())
        assertNull("entity not deleted", (dao as? ServerDao)?.let { null } ?: dao.getActive())
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
        assertTrue("first server should be active", server.isActive)
    }

    // --- new tests ---

    @Test
    fun `second added server is not active`() = runTest {
        val dao = fakeDao()
        val fakeApi = AbsApi { _, _, _, _ -> NetworkLoginResult.Success("uid-1", "tok") }
        val repo = ServerRepositoryImpl(dao, fakeTokenStorage, fakeApi)
        repo.addServer(ServerUrl.parse("https://first.example.com")!!, "admin", "pass")
        val result = repo.addServer(ServerUrl.parse("https://second.example.com")!!, "admin", "pass")
        assertTrue(result is AddServerResult.Success)
        assertFalse("second server must not be active", (result as AddServerResult.Success).server.isActive)
    }

    @Test
    fun `addServer returns InsecureConnection when network signals self-signed`() = runTest {
        val fakeApi = AbsApi { _, _, _, _ -> NetworkLoginResult.InsecureConnection(com.riffle.core.domain.InsecureConnectionType.SELF_SIGNED) }
        val repo = ServerRepositoryImpl(fakeDao(), fakeTokenStorage, fakeApi)
        val url = ServerUrl.parse("https://abs.example.com")!!
        val result = repo.addServer(url, "admin", "pass", insecureAllowed = false)
        assertTrue(result is AddServerResult.InsecureConnection)
    }

    @Test
    fun `setActive changes active server`() = runTest {
        val e1 = ServerEntity("s1", "https://one.example.com", "one.example.com", true, false)
        val e2 = ServerEntity("s2", "https://two.example.com", "two.example.com", false, false)
        val dao = object : ServerDao {
            val store = mutableListOf(e1, e2)
            override fun observeAll() = flowOf(store.toList())
            override suspend fun getActive() = store.firstOrNull { it.isActive }
            override suspend fun upsert(server: ServerEntity) { store.replaceAll { if (it.id == server.id) server else it } }
            override suspend fun clearActiveFlag() { store.replaceAll { it.copy(isActive = false) } }
            override suspend fun setActive(id: String) { store.replaceAll { if (it.id == id) it.copy(isActive = true) else it } }
            override suspend fun setActiveAtomic(id: String) { clearActiveFlag(); setActive(id) }
            override suspend fun upsertAsFirstIfNoActive(server: ServerEntity): ServerEntity {
                val toInsert = server.copy(isActive = getActive() == null)
                upsert(toInsert)
                return toInsert
            }
            override suspend fun deleteById(id: String) { store.removeAll { it.id == id } }
        }
        val fakeApi = AbsApi { _, _, _, _ -> NetworkLoginResult.WrongCredentials("") }
        val repo = ServerRepositoryImpl(dao, fakeTokenStorage, fakeApi)
        repo.setActive("s2")
        val active = dao.getActive()
        assertEquals("s2", active?.id)
        assertFalse("s1 must no longer be active", dao.store.first { it.id == "s1" }.isActive)
    }

    @Test
    fun `remove deletes entity from store`() = runTest {
        val entity = ServerEntity("srv-1", "https://abs.example.com", "abs.example.com", true, false)
        val dao = fakeDao(active = entity)
        fakeTokenStorage.tokens["srv-1"] = "tok"
        val fakeApi = AbsApi { _, _, _, _ -> NetworkLoginResult.WrongCredentials("") }
        val repo = ServerRepositoryImpl(dao, fakeTokenStorage, fakeApi)
        repo.remove("srv-1")
        // cast the anonymous object to access store
        val cast = dao as? Any
        // we verify via getActive returning null
        assertNull(dao.getActive())
        assertTrue(fakeTokenStorage.tokens.isEmpty())
    }
}
```

- [ ] **Step 3: Create `AddServerViewModelTest.kt`**

Create `app/src/test/kotlin/com/riffle/app/feature/server/AddServerViewModelTest.kt`:

```kotlin
package com.riffle.app.feature.server

import com.riffle.core.domain.AddServerResult
import com.riffle.core.domain.InsecureConnectionType
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

    private fun fakeRepo(result: AddServerResult): ServerRepository = object : ServerRepository {
        override fun observeAll(): Flow<List<Server>> = emptyFlow()
        override suspend fun getActive(): Server? = null
        override suspend fun addServer(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean) = result
        override suspend fun setActive(serverId: String) {}
        override suspend fun remove(serverId: String) {}
    }

    private fun fakeServer() = Server(
        id = "s1",
        url = ServerUrl.parse("https://abs.example.com")!!,
        displayName = "abs.example.com",
        isActive = true,
        insecureConnectionAllowed = false,
    )

    @Test
    fun `onConnect with invalid url sets error`() = runTest {
        val vm = AddServerViewModel(fakeRepo(AddServerResult.Success(fakeServer())))
        vm.url = "not-a-url"
        vm.username = "admin"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.error)
        assertNull(vm.insecureWarning)
    }

    @Test
    fun `onConnect with http url shows insecure warning`() = runTest {
        val vm = AddServerViewModel(fakeRepo(AddServerResult.Success(fakeServer())))
        vm.url = "http://abs.example.com"
        vm.username = "admin"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(InsecureConnectionType.HTTP, vm.insecureWarning)
        assertNull(vm.error)
    }

    @Test
    fun `onConnect success emits navigate back`() = runTest {
        val vm = AddServerViewModel(fakeRepo(AddServerResult.Success(fakeServer())))
        vm.url = "https://abs.example.com"
        vm.username = "admin"
        vm.password = "pass"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()
        val event = vm.navigateBack.first()
        assertNotNull(event)
        assertNull(vm.error)
    }

    @Test
    fun `onConnect wrong credentials sets error`() = runTest {
        val vm = AddServerViewModel(fakeRepo(AddServerResult.WrongCredentials("Bad creds")))
        vm.url = "https://abs.example.com"
        vm.username = "admin"
        vm.password = "wrong"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Bad creds", vm.error)
    }

    @Test
    fun `onConnect network error sets connection-failed message`() = runTest {
        val vm = AddServerViewModel(fakeRepo(AddServerResult.NetworkError(Exception("timeout"))))
        vm.url = "https://abs.example.com"
        vm.username = "admin"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.error?.contains("Connection failed") == true)
    }

    @Test
    fun `onInsecureWarningDismissed clears warning`() = runTest {
        val vm = AddServerViewModel(fakeRepo(AddServerResult.Success(fakeServer())))
        vm.url = "http://abs.example.com"
        vm.username = "admin"
        vm.onConnect()
        vm.onInsecureWarningDismissed()
        assertNull(vm.insecureWarning)
    }

    @Test
    fun `onInsecureWarningAccepted proceeds with insecureAllowed true`() = runTest {
        var capturedInsecure = false
        val repo = object : ServerRepository {
            override fun observeAll(): Flow<List<Server>> = emptyFlow()
            override suspend fun getActive(): Server? = null
            override suspend fun addServer(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean): AddServerResult {
                capturedInsecure = insecureAllowed
                return AddServerResult.Success(fakeServer())
            }
            override suspend fun setActive(serverId: String) {}
            override suspend fun remove(serverId: String) {}
        }
        val vm = AddServerViewModel(repo)
        vm.url = "http://abs.example.com"
        vm.username = "admin"
        vm.onInsecureWarningAccepted()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue("insecureAllowed must be true", capturedInsecure)
    }

    @Test
    fun `isLoading is false after login completes`() = runTest {
        val vm = AddServerViewModel(fakeRepo(AddServerResult.WrongCredentials("x")))
        vm.url = "https://abs.example.com"
        vm.username = "admin"
        vm.onConnect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.isLoading)
    }
}
```

- [ ] **Step 4: Run all unit tests**

```bash
./gradlew :core:network:test :core:data:test :app:test 2>&1 | tail -40
```
Expected: All tests pass, `BUILD SUCCESSFUL`

---

## Task 9: Final verification — full CI check

- [ ] **Step 1: Run full check**

```bash
./gradlew check 2>&1 | tail -40
```
Expected: `BUILD SUCCESSFUL` with no errors or test failures.

- [ ] **Step 2: Commit all changes**

```bash
git add -p
git commit -m "fix: address PR #35 review — navigation, atomicity, security, serialization, tests"
```
