# Multi-platform Phase 3 — sources as pure Kotlin

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract source adapters (ABS, Storyteller, Komga, WebDAV) from the Android `core:data` module into a new pure-JVM `core:sources` module so they have fast, platform-agnostic JVM tests.

**Architecture:** Create `core/sources` as a `kotlin.jvm` module depending only on `core:domain`, `core:models`, `core:common`, and `core:network`. Define a `SourceAdapter` interface (replacement for `CredentialedAuthenticator`). Move `AbsCredentialedAuthenticator`, `KomgaCredentialedAuthenticator`, `WebDavAnnotationSyncTarget`, and `WebDavAnnotationSyncTargetFactory` from `core:data` (Android library) into `core:sources` (pure JVM). Migrate `KomgaSourceAdapter` and `WebDavAnnotationSyncTargetFactory` from `OkHttpClient` to Ktor `HttpClient` so their tests can use Ktor's in-process `MockEngine` instead of a real `MockWebServer`. Update all consumers in `core:data` and `app` to use the new package path and `SourceAdapter` name.

**Tech Stack:** Kotlin JVM, Ktor 3.1.3 (`ktor-client-core`, `ktor-client-mock`), JUnit 4, kotlinx.coroutines-test.

## Global Constraints

- Module plugin: `alias(libs.plugins.kotlin.jvm)` — never `alias(libs.plugins.android.library)`.
- The module path is `core/sources`; the Gradle module ID is `:core:sources`.
- Package root: `com.riffle.core.sources`.
- `checkNoAndroidImports` must pass for `core/sources` after every commit: no `android.*`, no `androidx.*` (except `androidx.annotation`), no `java.util.logging`.
- Tests in `core:sources` use Ktor `MockEngine` only — no `okhttp3.mockwebserver.MockWebServer`, no `OkHttpClient` for test harness construction.
- Never add `@Singleton`, `@Inject`, or any Hilt/Dagger annotation to code in `core:sources`; DI wiring stays in `core:data`.
- Production classes that move from `core:data` must keep identical public API signatures so the Hilt modules in `core:data` can bind them without further changes to `app`.
- `CredentialedAuthenticator` in `core:data` becomes a Kotlin `typealias` for `SourceAdapter` — this lets `AddSourceViewModel.kt` in `app` keep its existing import without touching the `app` module at all.
- No OkHttp imports are allowed in `core:sources` production code. The `withInsecureTls()` extension (already in `core:network`) is permitted since `core:network` is a declared dependency.
- On every `./gradlew :core:sources:test` run all tests must be green. Do not skip or `@Ignore` tests.

---

## File Map

**Created:**
- `core/sources/build.gradle.kts`
- `core/sources/src/main/kotlin/com/riffle/core/sources/SourceAdapter.kt`
- `core/sources/src/main/kotlin/com/riffle/core/sources/abs/AbsSourceAdapter.kt`
- `core/sources/src/main/kotlin/com/riffle/core/sources/komga/KomgaSourceAdapter.kt`
- `core/sources/src/main/kotlin/com/riffle/core/sources/webdav/WebDavAnnotationSyncTarget.kt` (moved, package updated)
- `core/sources/src/main/kotlin/com/riffle/core/sources/webdav/WebDavAnnotationSyncTargetFactory.kt` (moved, takes `HttpClient` instead of `OkHttpClient`)
- `core/sources/src/test/kotlin/com/riffle/core/sources/abs/AbsSourceAdapterTest.kt`
- `core/sources/src/test/kotlin/com/riffle/core/sources/komga/KomgaSourceAdapterTest.kt` (migrated from `core:data`)
- `core/sources/src/test/kotlin/com/riffle/core/sources/webdav/WebDavAnnotationSyncTargetTest.kt` (migrated from `core:data`)
- `core/sources/src/test/kotlin/com/riffle/core/sources/webdav/WebDavAnnotationSyncTargetFactoryTest.kt` (migrated from `core:data`)

**Modified:**
- `settings.gradle.kts` — add `include(":core:sources")`
- `core/data/build.gradle.kts` — add `implementation(project(":core:sources"))`
- `core/data/src/main/kotlin/com/riffle/core/data/credentialed/CredentialedAuthenticator.kt` — replace body with `typealias CredentialedAuthenticator = com.riffle.core.sources.SourceAdapter`
- `core/data/src/main/kotlin/com/riffle/core/data/credentialed/AbsCredentialedAuthenticator.kt` — delete (class moved to `core:sources`)
- `core/data/src/main/kotlin/com/riffle/core/data/credentialed/KomgaCredentialedAuthenticator.kt` — delete (class moved to `core:sources`)
- `core/data/src/main/kotlin/com/riffle/core/data/WebDavAnnotationSyncTarget.kt` — delete (class moved to `core:sources`)
- `core/data/src/main/kotlin/com/riffle/core/data/WebDavAnnotationSyncTargetFactory.kt` — delete (class moved to `core:sources`)
- `core/data/src/main/kotlin/com/riffle/core/data/di/modules/CredentialedAuthenticatorModule.kt` — update imports; return type changes from `CredentialedAuthenticator` to `com.riffle.core.sources.SourceAdapter` (or keep via typealias)
- `core/data/src/main/kotlin/com/riffle/core/data/di/modules/NetworkModule.kt` — add `@Provides fun provideKtorHttpClient(okHttpClient: OkHttpClient): io.ktor.client.HttpClient`; update `KomgaSourceAdapter` and `WebDavAnnotationSyncTargetFactory` wiring
- `core/data/src/main/kotlin/com/riffle/core/data/di/modules/SyncModule.kt` — update import for `WebDavAnnotationSyncTargetFactory`
- `core/data/src/main/kotlin/com/riffle/core/data/AnnotationSyncTargetHolder.kt` — update import for `WebDavAnnotationSyncTargetFactory`
- `core/data/src/test/kotlin/com/riffle/core/data/credentialed/KomgaCredentialedAuthenticatorTest.kt` — delete (migrated to `core:sources`)
- `core/data/src/test/kotlin/com/riffle/core/data/WebDavAnnotationSyncTargetTest.kt` — delete (migrated to `core:sources`)
- `core/data/src/test/kotlin/com/riffle/core/data/WebDavAnnotationSyncTargetFactoryTest.kt` — delete (migrated to `core:sources`)
- `core/data/src/test/kotlin/com/riffle/core/data/SourceRepositoryTest.kt` — update `AbsCredentialedAuthenticator` reference to `com.riffle.core.sources.abs.AbsSourceAdapter`

---

### Task 1: Module scaffolding

**Files:**
- Create: `core/sources/build.gradle.kts`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Produces: `:core:sources` Gradle module, compilable, empty.

- [ ] **Step 1: Create `core/sources/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:models"))
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
```

- [ ] **Step 2: Register the module in `settings.gradle.kts`**

Add after the existing `include(":core:network")` line:

```kotlin
include(":core:sources")
```

- [ ] **Step 3: Create the source tree directories**

```bash
mkdir -p core/sources/src/main/kotlin/com/riffle/core/sources/abs
mkdir -p core/sources/src/main/kotlin/com/riffle/core/sources/komga
mkdir -p core/sources/src/main/kotlin/com/riffle/core/sources/webdav
mkdir -p core/sources/src/test/kotlin/com/riffle/core/sources/abs
mkdir -p core/sources/src/test/kotlin/com/riffle/core/sources/komga
mkdir -p core/sources/src/test/kotlin/com/riffle/core/sources/webdav
```

- [ ] **Step 4: Verify module syncs**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :core:sources:dependencies --configuration compileClasspath 2>&1 | tail -20
```

Expected: tree shows `core:domain`, `core:models`, `core:common`, `core:network` as transitive deps. No build errors.

- [ ] **Step 5: Commit**

```bash
git add core/sources/build.gradle.kts settings.gradle.kts
git commit -m "build(sources): scaffold core:sources pure-JVM module (Phase 3)"
```

---

### Task 2: `SourceAdapter` interface

**Files:**
- Create: `core/sources/src/main/kotlin/com/riffle/core/sources/SourceAdapter.kt`

**Interfaces:**
- Consumes: `com.riffle.core.domain.AuthenticateResult`, `com.riffle.core.models.ServerType`, `com.riffle.core.models.SourceType`, `com.riffle.core.models.SourceUrl`
- Produces: `interface SourceAdapter` with `val sourceType: SourceType` and `suspend fun authenticate(url: SourceUrl, username: String, password: String, insecureAllowed: Boolean, serverType: ServerType): AuthenticateResult`

- [ ] **Step 1: Write `SourceAdapter.kt`**

```kotlin
package com.riffle.core.sources

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl

/**
 * Per-[SourceType] plug-in that owns the "user has typed URL + credentials" step of adding a
 * credentialed source. Implemented by one class per external service: ABS, Komga, and (via
 * [AbsSourceAdapter]) Storyteller. WebDAV is a service-layer adapter and lives alongside but does
 * not implement this interface.
 *
 * Lives in `core:sources` (pure JVM) so every implementation is testable without the Android
 * build toolchain. DI wiring via Hilt `@IntoMap` stays in `core:data`.
 */
interface SourceAdapter {
    val sourceType: SourceType

    suspend fun authenticate(
        url: SourceUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
        serverType: ServerType,
    ): AuthenticateResult
}
```

- [ ] **Step 2: Verify it compiles**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :core:sources:compileKotlin
```

Expected: BUILD SUCCESSFUL with no errors.

- [ ] **Step 3: Commit**

```bash
git add core/sources/src/main/kotlin/com/riffle/core/sources/SourceAdapter.kt
git commit -m "feat(sources): add SourceAdapter interface (Phase 3)"
```

---

### Task 3: `AbsSourceAdapter`

**Files:**
- Create: `core/sources/src/main/kotlin/com/riffle/core/sources/abs/AbsSourceAdapter.kt`
- Create: `core/sources/src/test/kotlin/com/riffle/core/sources/abs/AbsSourceAdapterTest.kt`

**Interfaces:**
- Consumes: `SourceAdapter` (Task 2), `com.riffle.core.network.AbsApi`, `com.riffle.core.network.AbsLibraryApi`, `com.riffle.core.network.StorytellerApi`, `com.riffle.core.network.NetworkResult`, `com.riffle.core.network.errorAsThrowable`
- Produces: `class AbsSourceAdapter(absApi: AbsApi, libraryApi: AbsLibraryApi, storytellerApi: StorytellerApi) : SourceAdapter`

- [ ] **Step 1: Write the failing test first**

```kotlin
// core/sources/src/test/kotlin/com/riffle/core/sources/abs/AbsSourceAdapterTest.kt
package com.riffle.core.sources.abs

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.models.Library
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
import org.junit.Assert.assertInstanceOf
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
            override suspend fun getPlaylists(baseUrl: String, libraryId: String, userId: String, token: String, insecureAllowed: Boolean) =
                throw NotImplementedError()
        }

    private val storytellerNotCalled: StorytellerApi = StorytellerApi { _, _, _, _ -> error("unexpected") }

    private fun storytellerApi(result: NetworkResult<String>): StorytellerApi =
        StorytellerApi { _, _, _, _ -> result }

    @Test fun `ABS login success maps to AuthenticateResult Success`() = runTest {
        val adapter = AbsSourceAdapter(
            absApi = absApi(NetworkResult.Success(NetworkLoginUser("uid1", "tok", "alice"))),
            libraryApi = libsApi(listOf(
                NetworkLibrary("L1", "Books", "book"),
                NetworkLibrary("L2", "Podcasts", "podcast"),
            )),
            storytellerApi = storytellerNotCalled,
        )

        val result = adapter.authenticate(url, "alice", "secret", insecureAllowed = false, serverType = ServerType.AUDIOBOOKSHELF)

        assertInstanceOf(AuthenticateResult.Success::class.java, result)
        val pending = (result as AuthenticateResult.Success).pending
        assertEquals(SourceType.ABS, pending.sourceType)
        assertEquals("alice", pending.username)
        assertEquals("tok", pending.token)
        // Podcast libraries filtered out — only book mediaType survives.
        assertEquals(1, pending.libraries.size)
        assertEquals("L1", pending.libraries[0].id)
        assertEquals("Books", pending.libraries[0].name)
    }

    @Test fun `ABS 401 maps to WrongCredentials`() = runTest {
        val adapter = AbsSourceAdapter(
            absApi = absApi(NetworkResult.Auth),
            libraryApi = libsApi(emptyList()),
            storytellerApi = storytellerNotCalled,
        )

        val result = adapter.authenticate(url, "alice", "wrong", insecureAllowed = false, serverType = ServerType.AUDIOBOOKSHELF)

        assertInstanceOf(AuthenticateResult.WrongCredentials::class.java, result)
    }

    @Test fun `Storyteller success maps to AuthenticateResult Success with empty libraries`() = runTest {
        val adapter = AbsSourceAdapter(
            absApi = absApi(NetworkResult.Unknown(RuntimeException("should not be called"))),
            libraryApi = libsApi(emptyList()),
            storytellerApi = storytellerApi(NetworkResult.Success("storyteller-token")),
        )

        val result = adapter.authenticate(url, "alice", "secret", insecureAllowed = false, serverType = ServerType.STORYTELLER_SERVICE)

        assertInstanceOf(AuthenticateResult.Success::class.java, result)
        val pending = (result as AuthenticateResult.Success).pending
        assertEquals("storyteller-token", pending.token)
        assertEquals(0, pending.libraries.size)
        assertEquals(ServerType.STORYTELLER_SERVICE, pending.serverType)
    }

    @Test fun `Storyteller 401 maps to WrongCredentials`() = runTest {
        val adapter = AbsSourceAdapter(
            absApi = absApi(NetworkResult.Unknown(RuntimeException("should not be called"))),
            libraryApi = libsApi(emptyList()),
            storytellerApi = storytellerApi(NetworkResult.Auth),
        )

        val result = adapter.authenticate(url, "alice", "bad", insecureAllowed = false, serverType = ServerType.STORYTELLER_SERVICE)

        assertInstanceOf(AuthenticateResult.WrongCredentials::class.java, result)
    }
}
```

- [ ] **Step 2: Run test — expect compilation failure (class not yet written)**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :core:sources:test --tests "com.riffle.core.sources.abs.AbsSourceAdapterTest" 2>&1 | tail -30
```

Expected: error `unresolved reference: AbsSourceAdapter`.

- [ ] **Step 3: Write `AbsSourceAdapter.kt` — exact copy of the logic in `AbsCredentialedAuthenticator.kt`, new package and class name**

The source is at `core/data/src/main/kotlin/com/riffle/core/data/credentialed/AbsCredentialedAuthenticator.kt`. Copy the logic exactly, changing only the package and class name. No `@Singleton` or `@Inject`.

```kotlin
package com.riffle.core.sources.abs

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.models.Library
import com.riffle.core.models.PendingSource
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.StorytellerApi
import com.riffle.core.network.errorAsThrowable
import com.riffle.core.sources.SourceAdapter

class AbsSourceAdapter(
    private val absApi: AbsApi,
    private val libraryApi: AbsLibraryApi,
    private val storytellerApi: StorytellerApi,
) : SourceAdapter {
    override val sourceType: SourceType = SourceType.ABS

    override suspend fun authenticate(
        url: SourceUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
        serverType: ServerType,
    ): AuthenticateResult = when (serverType) {
        ServerType.AUDIOBOOKSHELF -> authenticateAudiobookshelf(url, username, password, insecureAllowed)
        ServerType.STORYTELLER_SERVICE -> authenticateStoryteller(url, username, password, insecureAllowed)
    }

    private suspend fun authenticateAudiobookshelf(
        url: SourceUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): AuthenticateResult {
        val loginResult = absApi.login(url.value, username, password, insecureAllowed)
        return when (loginResult) {
            NetworkResult.Auth -> AuthenticateResult.WrongCredentials(WRONG_CREDENTIALS_MESSAGE)
            is NetworkResult.InsecureConnection -> AuthenticateResult.InsecureConnection(loginResult.type)
            is NetworkResult.Success -> {
                val libs = libraryApi.getLibraries(url.value, loginResult.value.token, insecureAllowed)
                if (libs !is NetworkResult.Success) {
                    AuthenticateResult.LibraryFetchFailed(libs.errorAsThrowable())
                } else AuthenticateResult.Success(
                    PendingSource(
                        url = url,
                        username = loginResult.value.username,
                        userId = loginResult.value.userId,
                        token = loginResult.value.token,
                        password = password,
                        insecureConnectionAllowed = insecureAllowed,
                        libraries = libs.value
                            .filter { it.mediaType == "book" }
                            .map {
                                Library(
                                    id = it.id,
                                    name = it.name,
                                    mediaType = it.mediaType,
                                    isUnsupported = false,
                                )
                            },
                        serverType = ServerType.AUDIOBOOKSHELF,
                        sourceType = SourceType.ABS,
                    )
                )
            }
            else -> AuthenticateResult.NetworkError(loginResult.errorAsThrowable())
        }
    }

    private suspend fun authenticateStoryteller(
        url: SourceUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): AuthenticateResult = when (val result = storytellerApi.login(url.value, username, password, insecureAllowed)) {
        NetworkResult.Auth -> AuthenticateResult.WrongCredentials(WRONG_CREDENTIALS_MESSAGE)
        is NetworkResult.InsecureConnection -> AuthenticateResult.InsecureConnection(result.type)
        is NetworkResult.Success -> AuthenticateResult.Success(
            PendingSource(
                url = url,
                username = username,
                userId = "",
                token = result.value,
                password = password,
                insecureConnectionAllowed = insecureAllowed,
                libraries = emptyList(),
                serverType = ServerType.STORYTELLER_SERVICE,
                sourceType = SourceType.ABS,
            )
        )
        else -> AuthenticateResult.NetworkError(result.errorAsThrowable())
    }

    companion object {
        private const val WRONG_CREDENTIALS_MESSAGE = "Invalid username or password"
    }
}
```

- [ ] **Step 4: Run tests — expect green**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :core:sources:test --tests "com.riffle.core.sources.abs.AbsSourceAdapterTest"
```

Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add core/sources/src/main/kotlin/com/riffle/core/sources/abs/AbsSourceAdapter.kt \
        core/sources/src/test/kotlin/com/riffle/core/sources/abs/AbsSourceAdapterTest.kt
git commit -m "feat(sources): add AbsSourceAdapter with ABS+Storyteller auth (Phase 3)"
```

---

### Task 4: `KomgaSourceAdapter`

**Files:**
- Create: `core/sources/src/main/kotlin/com/riffle/core/sources/komga/KomgaSourceAdapter.kt`
- Create: `core/sources/src/test/kotlin/com/riffle/core/sources/komga/KomgaSourceAdapterTest.kt`

**Interfaces:**
- Consumes: `SourceAdapter` (Task 2), `io.ktor.client.HttpClient` from Ktor, `com.riffle.core.network.withInsecureTls` (extension in `core:network`)
- Produces: `class KomgaSourceAdapter(httpClient: HttpClient) : SourceAdapter`

**Key difference from old `KomgaCredentialedAuthenticator`:** constructor takes Ktor `HttpClient` (not `OkHttpClient`). No dependency on `core:catalog-komga`. Inlines Basic auth header building and the `/users/me` probe with Ktor.

- [ ] **Step 1: Write the failing test**

```kotlin
// core/sources/src/test/kotlin/com/riffle/core/sources/komga/KomgaSourceAdapterTest.kt
package com.riffle.core.sources.komga

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.models.InsecureConnectionType
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertInstanceOf
import org.junit.Assert.assertTrue
import org.junit.Test

class KomgaSourceAdapterTest {

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun mockClient(vararg handlers: Pair<String, String>): HttpClient {
        val queue = ArrayDeque(handlers.toList())
        val engine = MockEngine { _ ->
            val (body, statusStr) = queue.removeFirst()
            val code = statusStr.toIntOrNull() ?: 200
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.fromValue(code),
                headers = jsonHeaders(),
            )
        }
        return HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    private fun url(scheme: String = "https") = SourceUrl.parse("$scheme://komga.example.com")!!

    @Test fun `success returns PendingSource with SourceType KOMGA and book libraries`() = runTest {
        val adapter = KomgaSourceAdapter(
            mockClient(
                """{"id":"u1","email":"a@b.com"}""" to "200",
                """[{"id":"L1","name":"Comics"},{"id":"L2","name":"Manga"}]""" to "200",
            )
        )

        val result = adapter.authenticate(url(), "alice", "secret", insecureAllowed = true, serverType = ServerType.AUDIOBOOKSHELF)

        assertInstanceOf(AuthenticateResult.Success::class.java, result)
        val pending = (result as AuthenticateResult.Success).pending
        assertEquals(SourceType.KOMGA, pending.sourceType)
        assertEquals("alice", pending.username)
        assertEquals("secret", pending.password)
        assertEquals("u1", pending.userId)
        assertEquals(2, pending.libraries.size)
        assertEquals("L1", pending.libraries[0].id)
        assertEquals("Comics", pending.libraries[0].name)
    }

    @Test fun `401 on users-me maps to WrongCredentials`() = runTest {
        val adapter = KomgaSourceAdapter(mockClient("""{}""" to "401"))

        val result = adapter.authenticate(url(), "alice", "wrong", insecureAllowed = true, serverType = ServerType.AUDIOBOOKSHELF)

        assertInstanceOf(AuthenticateResult.WrongCredentials::class.java, result)
    }

    @Test fun `http URL without insecureAllowed flags InsecureConnection HTTP`() = runTest {
        val adapter = KomgaSourceAdapter(mockClient())

        val result = adapter.authenticate(url("http"), "alice", "p", insecureAllowed = false, serverType = ServerType.AUDIOBOOKSHELF)

        assertInstanceOf(AuthenticateResult.InsecureConnection::class.java, result)
        assertEquals(InsecureConnectionType.HTTP, (result as AuthenticateResult.InsecureConnection).type)
    }

    @Test fun `v2 404 falls back to v1 users-me then succeeds`() = runTest {
        val adapter = KomgaSourceAdapter(
            mockClient(
                """{}""" to "404",              // v2 /api/v2/users/me
                """{"id":"u2"}""" to "200",     // v1 /api/v1/users/me
                """[]""" to "200",              // /api/v1/libraries
            )
        )

        val result = adapter.authenticate(url(), "alice", "p", insecureAllowed = true, serverType = ServerType.AUDIOBOOKSHELF)

        assertInstanceOf(AuthenticateResult.Success::class.java, result)
        assertEquals(0, (result as AuthenticateResult.Success).pending.libraries.size)
    }

    @Test fun `403 on users-me maps to WrongCredentials`() = runTest {
        val adapter = KomgaSourceAdapter(mockClient("""{}""" to "403"))

        val result = adapter.authenticate(url(), "alice", "wrong", insecureAllowed = true, serverType = ServerType.AUDIOBOOKSHELF)

        assertInstanceOf(AuthenticateResult.WrongCredentials::class.java, result)
    }

    @Test fun `Basic auth header is built correctly`() = runTest {
        var capturedAuth: String? = null
        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond(
                content = ByteReadChannel("""{"id":"x"}"""),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        // Second request for libraries
        val engine2 = MockEngine { _ ->
            respond(ByteReadChannel("[]"), HttpStatusCode.OK, jsonHeaders())
        }
        // Use two-request mock via queue approach
        val queue = ArrayDeque(listOf(
            """{"id":"u1"}""" to "200",
            """[]""" to "200",
        ))
        val engine3 = MockEngine { _ ->
            val (body, code) = queue.removeFirst()
            respond(ByteReadChannel(body), HttpStatusCode.fromValue(code.toInt()), jsonHeaders())
        }
        val client3 = HttpClient(engine3) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        // Attach a spy engine to check the auth header
        var seenAuth: String? = null
        val spyQueue = ArrayDeque(listOf("""{"id":"u1"}""" to "200", """[]""" to "200"))
        val spyEngine = MockEngine { request ->
            seenAuth = request.headers[HttpHeaders.Authorization]
            val (body, code) = spyQueue.removeFirst()
            respond(ByteReadChannel(body), HttpStatusCode.fromValue(code.toInt()), jsonHeaders())
        }
        val spyClient = HttpClient(spyEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        KomgaSourceAdapter(spyClient).authenticate(url(), "alice", "s3cr3t", insecureAllowed = true, serverType = ServerType.AUDIOBOOKSHELF)

        assertTrue("Expected Basic auth header", seenAuth?.startsWith("Basic ") == true)
        // Decode and verify
        val decoded = String(java.util.Base64.getDecoder().decode(seenAuth!!.removePrefix("Basic ")))
        assertEquals("alice:s3cr3t", decoded)
    }
}
```

- [ ] **Step 2: Run test — expect compilation failure**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :core:sources:test --tests "com.riffle.core.sources.komga.KomgaSourceAdapterTest" 2>&1 | tail -20
```

Expected: `unresolved reference: KomgaSourceAdapter`.

- [ ] **Step 3: Write `KomgaSourceAdapter.kt`**

This inlines the logic from `KomgaCredentialedAuthenticator` but uses Ktor `HttpClient` directly (no `core:catalog-komga` dependency). The Basic auth header and `/users/me` probe are implemented here without importing `KomgaHttpClient` or `probeKomgaUserId` from catalog-komga.

```kotlin
package com.riffle.core.sources.komga

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.models.InsecureConnectionType
import com.riffle.core.models.Library
import com.riffle.core.models.PendingSource
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.network.withInsecureTls
import com.riffle.core.sources.SourceAdapter
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.IOException
import java.util.Base64
import javax.net.ssl.SSLHandshakeException

class KomgaSourceAdapter(
    private val httpClient: HttpClient,
) : SourceAdapter {
    override val sourceType: SourceType = SourceType.KOMGA

    override suspend fun authenticate(
        url: SourceUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
        serverType: ServerType,
    ): AuthenticateResult {
        if (!insecureAllowed && url.value.startsWith("http://", ignoreCase = true)) {
            return AuthenticateResult.InsecureConnection(InsecureConnectionType.HTTP)
        }
        val base = url.value.trimEnd('/')
        val authHeader = buildBasicAuthHeader(username, password)
        val client = if (insecureAllowed) httpClient.withInsecureTls() else httpClient

        val meResult = try {
            probeUserId(client, authHeader, base)
        } catch (e: SSLHandshakeException) {
            return AuthenticateResult.InsecureConnection(InsecureConnectionType.SELF_SIGNED)
        } catch (e: IOException) {
            return AuthenticateResult.NetworkError(e)
        }

        when {
            meResult.status == 401 || meResult.status == 403 -> return AuthenticateResult.WrongCredentials()
            meResult.status !in 200..399 -> return AuthenticateResult.NetworkError(
                IOException("Komga returned HTTP ${meResult.status} at /users/me"),
            )
        }

        val libs = try {
            val body = getString(client, authHeader, "$base/api/v1/libraries")
            komgaJson.decodeFromString(ListSerializer(serializer<KomgaLibraryDto>()), body)
        } catch (e: IOException) {
            return AuthenticateResult.LibraryFetchFailed(e)
        }

        return AuthenticateResult.Success(
            PendingSource(
                url = url,
                username = username,
                userId = meResult.userId.orEmpty(),
                token = authHeader,
                password = password,
                insecureConnectionAllowed = insecureAllowed,
                libraries = libs.map {
                    Library(id = it.id, name = it.name, mediaType = "book", isUnsupported = false)
                },
                serverType = ServerType.AUDIOBOOKSHELF,
                sourceType = SourceType.KOMGA,
            )
        )
    }

    private suspend fun probeUserId(client: HttpClient, authHeader: String, base: String): MeProbeResult {
        val v2 = fetchMe(client, authHeader, "$base/api/v2/users/me")
        return if (v2.status == 404) fetchMe(client, authHeader, "$base/api/v1/users/me") else v2
    }

    private suspend fun fetchMe(client: HttpClient, authHeader: String, url: String): MeProbeResult {
        return try {
            val body = getString(client, authHeader, url)
            val id = runCatching {
                komgaJson.decodeFromString(MeDto.serializer(), body).id.takeIf { it.isNotBlank() }
            }.getOrNull()
            MeProbeResult(status = 200, userId = id)
        } catch (e: KomgaHttpStatusException) {
            MeProbeResult(status = e.code, userId = null)
        }
    }

    private suspend fun getString(client: HttpClient, authHeader: String, url: String): String {
        val response = client.get(url) {
            header(HttpHeaders.Authorization, authHeader)
            header(HttpHeaders.UserAgent, USER_AGENT)
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) throw KomgaHttpStatusException(response.status.value)
        return response.bodyAsText()
    }

    private class KomgaHttpStatusException(val code: Int) : IOException("HTTP $code")
    private data class MeProbeResult(val status: Int, val userId: String?)

    @Serializable private data class MeDto(@SerialName("id") val id: String)

    @Serializable
    private data class KomgaLibraryDto(
        val id: String,
        val name: String,
        @SerialName("unavailable") val unavailable: Boolean = false,
    )

    companion object {
        private const val USER_AGENT = "Riffle/dev (Android) komga-source"
        private val komgaJson = Json { ignoreUnknownKeys = true }

        fun buildBasicAuthHeader(username: String, password: String): String {
            val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
            return "Basic $token"
        }
    }
}
```

- [ ] **Step 4: Run tests — expect green**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :core:sources:test --tests "com.riffle.core.sources.komga.KomgaSourceAdapterTest"
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add core/sources/src/main/kotlin/com/riffle/core/sources/komga/KomgaSourceAdapter.kt \
        core/sources/src/test/kotlin/com/riffle/core/sources/komga/KomgaSourceAdapterTest.kt
git commit -m "feat(sources): add KomgaSourceAdapter with Ktor HttpClient (Phase 3)"
```

---

### Task 5: Move `WebDavAnnotationSyncTarget` and `WebDavAnnotationSyncTargetFactory`

**Files:**
- Create: `core/sources/src/main/kotlin/com/riffle/core/sources/webdav/WebDavAnnotationSyncTarget.kt`
- Create: `core/sources/src/main/kotlin/com/riffle/core/sources/webdav/WebDavAnnotationSyncTargetFactory.kt`

**Interfaces:**
- Consumes: `io.ktor.client.HttpClient`, `com.riffle.core.domain.AnnotationSyncTarget`, `com.riffle.core.domain.DispatcherProvider`, `com.riffle.core.domain.AnnotationSyncConfig`
- Produces: `class WebDavAnnotationSyncTarget(baseUrl, username, password, client, dispatchers) : AnnotationSyncTarget`; `class WebDavAnnotationSyncTargetFactory(httpClient: HttpClient, dispatchers: DispatcherProvider)`

**Key change:** `WebDavAnnotationSyncTargetFactory` now takes `HttpClient` instead of `OkHttpClient`. It applies the WebDAV timeouts via `httpClient.config { install(HttpTimeout) { ... } }`.

- [ ] **Step 1: Copy `WebDavAnnotationSyncTarget.kt` to new location with updated package**

Source: `core/data/src/main/kotlin/com/riffle/core/data/WebDavAnnotationSyncTarget.kt`

Change only: `package com.riffle.core.data` → `package com.riffle.core.sources.webdav`

The `AnnotationSyncException` sealed class lives at the bottom of this file — keep it in the same file. The `parseWebDavBaseUrl` internal fun must also stay in this file (it is used by the factory).

- [ ] **Step 2: Write `WebDavAnnotationSyncTargetFactory.kt` in new location**

```kotlin
package com.riffle.core.sources.webdav

import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.DispatcherProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

class WebDavAnnotationSyncTargetFactory(
    httpClient: HttpClient,
    private val dispatchers: DispatcherProvider,
) {
    private val httpClient = httpClient.config {
        install(HttpTimeout) {
            requestTimeoutMillis = WEBDAV_CALL_TIMEOUT_MS
            connectTimeoutMillis = WEBDAV_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = WEBDAV_READ_TIMEOUT_MS
        }
    }

    fun create(config: AnnotationSyncConfig): WebDavAnnotationSyncTarget? {
        val url = parseWebDavBaseUrl(config.baseUrl) ?: return null
        return WebDavAnnotationSyncTarget(
            baseUrl = url,
            username = config.username,
            password = config.password,
            client = this.httpClient,
            dispatchers = dispatchers,
        )
    }

    companion object {
        private const val WEBDAV_CALL_TIMEOUT_MS = 30_000L
        private const val WEBDAV_CONNECT_TIMEOUT_MS = 10_000L
        private const val WEBDAV_READ_TIMEOUT_MS = 20_000L
    }
}
```

- [ ] **Step 3: Verify `core:sources` compiles**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :core:sources:compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/sources/src/main/kotlin/com/riffle/core/sources/webdav/
git commit -m "feat(sources): move WebDavAnnotationSyncTarget and factory to core:sources (Phase 3)"
```

---

### Task 6: Migrate WebDAV tests to `core:sources` with MockEngine

**Files:**
- Create: `core/sources/src/test/kotlin/com/riffle/core/sources/webdav/WebDavAnnotationSyncTargetTest.kt`
- Create: `core/sources/src/test/kotlin/com/riffle/core/sources/webdav/WebDavAnnotationSyncTargetFactoryTest.kt`

**Interfaces:**
- Consumes: `WebDavAnnotationSyncTarget` (Task 5), `WebDavAnnotationSyncTargetFactory` (Task 5)
- Produces: Full test coverage migrated from `core:data` using `MockEngine` instead of `MockWebServer`

**How MockEngine replaces MockWebServer:**
- `server.enqueue(MockResponse().setBody("x"))` → add a handler to `MockEngine` returning `respond(ByteReadChannel("x"), HttpStatusCode.OK, ...)`
- `server.takeRequest()` → `engine.requestHistory.last()`
- `server.url("/path").toString()` → use a hardcoded test URL string like `"https://dav.test/annotations"`
- `makeTargetWithFailingClient(IOException("err"))` → `MockEngine { throw IOException("err") }` 

- [ ] **Step 1: Create `WebDavAnnotationSyncTargetTest.kt`**

Copy the file from `core/data/src/test/kotlin/com/riffle/core/data/WebDavAnnotationSyncTargetTest.kt` and make these changes:

1. Change package: `package com.riffle.core.data` → `package com.riffle.core.sources.webdav`
2. Change all `import com.riffle.core.data.*` → `import com.riffle.core.sources.webdav.*`
3. Replace imports:
   - Remove: `import io.ktor.client.engine.okhttp.OkHttp`
   - Remove: `import okhttp3.OkHttpClient`
   - Remove: `import okhttp3.mockwebserver.*`
   - Add: `import io.ktor.client.engine.mock.MockEngine`
   - Add: `import io.ktor.client.engine.mock.respond`
   - Add: `import io.ktor.http.HttpStatusCode`
   - Add: `import io.ktor.utils.io.ByteReadChannel`
4. Remove `@Before fun setUp()` / `@After fun tearDown()` / `private lateinit var source: MockWebServer`
5. Replace `private fun newTarget(...)`:

```kotlin
private val BASE_URL = "https://dav.test"

private val responses = ArrayDeque<Pair<String, HttpStatusCode>>()

private lateinit var engine: MockEngine

private fun setUp() {
    responses.clear()
    engine = MockEngine { _ ->
        val (body, status) = responses.removeFirst()
        respond(
            content = ByteReadChannel(body),
            status = status,
            headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "text/xml"),
        )
    }
}

private fun enqueue(body: String, status: HttpStatusCode = HttpStatusCode.OK) {
    responses.addLast(body to status)
}

private fun lastRequest() = engine.requestHistory.last()

private fun newTarget(
    username: String = USER,
    password: String = PASS,
    basePath: String = "annotations",
): WebDavAnnotationSyncTarget {
    return WebDavAnnotationSyncTarget(
        baseUrl = io.ktor.http.Url("$BASE_URL/$basePath"),
        username = username,
        password = password,
        client = HttpClient(engine),
        dispatchers = com.riffle.core.domain.DefaultDispatcherProvider,
    )
}
```

6. In each `@Test`, replace:
   - `source.enqueue(MockResponse().setBody("x"))` → `enqueue("x")`
   - `source.enqueue(MockResponse().setResponseCode(207).setBody("x"))` → `enqueue("x", HttpStatusCode.MultiStatus)`
   - `source.enqueue(MockResponse().setResponseCode(N))` → `enqueue("", HttpStatusCode.fromValue(N))`
   - `source.takeRequest()` → `lastRequest()`  
   - `req.getHeader("Authorization")` → `req.headers["Authorization"]`
   - `req.method` → `req.method.value`
   - `req.path` → `req.url.encodedPath`
   - `source.url("/$basePath").toString().trimEnd('/')` → `"$BASE_URL/$basePath"`
7. Add `setUp()` call at the start of each `@Test` (or convert to `@Before`)
8. Replace `makeTargetWithFailingClient`:

```kotlin
private fun makeTargetWithFailingClient(throwable: Throwable): WebDavAnnotationSyncTarget {
    val failEngine = MockEngine { throw throwable }
    return WebDavAnnotationSyncTarget(
        baseUrl = io.ktor.http.Url("https://example.test/dav/"),
        username = "u",
        password = "p",
        client = HttpClient(failEngine),
        dispatchers = com.riffle.core.domain.DefaultDispatcherProvider,
    )
}
```

- [ ] **Step 2: Run the WebDAV target tests**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :core:sources:test --tests "com.riffle.core.sources.webdav.WebDavAnnotationSyncTargetTest"
```

Expected: all tests pass. Fix any remaining import or path issues.

- [ ] **Step 3: Create `WebDavAnnotationSyncTargetFactoryTest.kt`**

```kotlin
package com.riffle.core.sources.webdav

import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.DefaultDispatcherProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WebDavAnnotationSyncTargetFactoryTest {

    private val factory = WebDavAnnotationSyncTargetFactory(
        httpClient = HttpClient(MockEngine { respond(ByteReadChannel(""), HttpStatusCode.OK) }),
        dispatchers = DefaultDispatcherProvider,
    )

    @Test fun `valid config produces a target`() {
        val target = factory.create(
            AnnotationSyncConfig(
                baseUrl = "https://dav.example.org/remote.php/dav/files/me/annotations",
                username = "u",
                password = "p",
            ),
        )
        assertNotNull(target)
    }

    @Test fun `malformed base URL yields null`() {
        val target = factory.create(
            AnnotationSyncConfig(baseUrl = "::not a url::", username = "u", password = "p"),
        )
        assertNull(target)
    }

    @Test fun `empty base URL yields null`() {
        val target = factory.create(
            AnnotationSyncConfig(baseUrl = "", username = "u", password = "p"),
        )
        assertNull(target)
    }
}
```

- [ ] **Step 4: Run factory tests**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :core:sources:test --tests "com.riffle.core.sources.webdav.WebDavAnnotationSyncTargetFactoryTest"
```

Expected: all tests pass.

- [ ] **Step 5: Run all `core:sources` tests**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :core:sources:test
```

Expected: all tests in the module pass.

- [ ] **Step 6: Commit**

```bash
git add core/sources/src/test/kotlin/com/riffle/core/sources/webdav/
git commit -m "test(sources): migrate WebDAV tests to core:sources with Ktor MockEngine (Phase 3)"
```

---

### Task 7: Wire `core:data` to use `core:sources`

Replace source-in-Android-module classes with references to their new homes. This task should not change any behaviour — only imports and package paths.

**Files:**
- Modify: `core/data/build.gradle.kts`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/credentialed/CredentialedAuthenticator.kt`
- Delete: `core/data/src/main/kotlin/com/riffle/core/data/credentialed/AbsCredentialedAuthenticator.kt`
- Delete: `core/data/src/main/kotlin/com/riffle/core/data/credentialed/KomgaCredentialedAuthenticator.kt`
- Delete: `core/data/src/main/kotlin/com/riffle/core/data/WebDavAnnotationSyncTarget.kt`
- Delete: `core/data/src/main/kotlin/com/riffle/core/data/WebDavAnnotationSyncTargetFactory.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/modules/CredentialedAuthenticatorModule.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/modules/NetworkModule.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/modules/SyncModule.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/AnnotationSyncTargetHolder.kt`
- Delete test files: `KomgaCredentialedAuthenticatorTest.kt`, `WebDavAnnotationSyncTargetTest.kt`, `WebDavAnnotationSyncTargetFactoryTest.kt`
- Modify: `core/data/src/test/kotlin/com/riffle/core/data/SourceRepositoryTest.kt`

- [ ] **Step 1: Add `core:sources` dependency to `core/data/build.gradle.kts`**

```kotlin
// existing deps
implementation(project(":core:sources"))   // add this line
```

- [ ] **Step 2: Replace `CredentialedAuthenticator.kt` with a type alias**

Replace the entire file body with:

```kotlin
package com.riffle.core.data.credentialed

/** Backward-compatible alias so callers in `app` don't need to change their imports. */
typealias CredentialedAuthenticator = com.riffle.core.sources.SourceAdapter
```

- [ ] **Step 3: Delete moved source files from `core:data`**

```bash
rm core/data/src/main/kotlin/com/riffle/core/data/credentialed/AbsCredentialedAuthenticator.kt
rm core/data/src/main/kotlin/com/riffle/core/data/credentialed/KomgaCredentialedAuthenticator.kt
rm core/data/src/main/kotlin/com/riffle/core/data/WebDavAnnotationSyncTarget.kt
rm core/data/src/main/kotlin/com/riffle/core/data/WebDavAnnotationSyncTargetFactory.kt
```

- [ ] **Step 4: Update `CredentialedAuthenticatorModule.kt`**

Update imports and return type to use `com.riffle.core.sources.SourceAdapter`:

```kotlin
package com.riffle.core.data.di.modules

import com.riffle.core.sources.abs.AbsSourceAdapter
import com.riffle.core.sources.komga.KomgaSourceAdapter
import com.riffle.core.sources.SourceAdapter
import com.riffle.core.models.SourceType
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

@Module
@InstallIn(SingletonComponent::class)
abstract class CredentialedAuthenticatorModule {

    @Binds
    @IntoMap
    @SourceTypeKey(SourceType.ABS)
    abstract fun bindAbsSourceAdapter(impl: AbsSourceAdapter): SourceAdapter

    @Binds
    @IntoMap
    @SourceTypeKey(SourceType.KOMGA)
    abstract fun bindKomgaSourceAdapter(impl: KomgaSourceAdapter): SourceAdapter
}
```

- [ ] **Step 5: Update `NetworkModule.kt` — add Ktor `HttpClient` provider and update `AbsSourceAdapter` / `KomgaSourceAdapter` / `WebDavAnnotationSyncTargetFactory` wiring**

Add to the `@Provides` companion inside `NetworkModule`:

```kotlin
@Provides
@Singleton
fun provideKtorHttpClient(okHttpClient: OkHttpClient): io.ktor.client.HttpClient =
    com.riffle.core.network.createDefaultHttpClient(okHttpClient)

@Provides
@Singleton
fun provideAbsSourceAdapter(
    absApi: AbsApi,
    libraryApi: AbsLibraryApi,
    storytellerApi: StorytellerApi,
): com.riffle.core.sources.abs.AbsSourceAdapter =
    com.riffle.core.sources.abs.AbsSourceAdapter(absApi, libraryApi, storytellerApi)

@Provides
@Singleton
fun provideKomgaSourceAdapter(httpClient: io.ktor.client.HttpClient): com.riffle.core.sources.komga.KomgaSourceAdapter =
    com.riffle.core.sources.komga.KomgaSourceAdapter(httpClient)

@Provides
@Singleton
fun provideWebDavAnnotationSyncTargetFactory(
    httpClient: io.ktor.client.HttpClient,
    dispatchers: DispatcherProvider,
): com.riffle.core.sources.webdav.WebDavAnnotationSyncTargetFactory =
    com.riffle.core.sources.webdav.WebDavAnnotationSyncTargetFactory(httpClient, dispatchers)
```

Remove the old `provideWebDavAnnotationSyncTargetFactory` if it was in `SyncModule`.

- [ ] **Step 6: Update `SyncModule.kt` — import updated `WebDavAnnotationSyncTargetFactory`**

Change:
```kotlin
webDavFactory: com.riffle.core.data.WebDavAnnotationSyncTargetFactory,
```
to:
```kotlin
webDavFactory: com.riffle.core.sources.webdav.WebDavAnnotationSyncTargetFactory,
```

- [ ] **Step 7: Update `AnnotationSyncTargetHolder.kt` — update import**

Change:
```kotlin
private val webDavFactory: WebDavAnnotationSyncTargetFactory,
```
to:
```kotlin
private val webDavFactory: com.riffle.core.sources.webdav.WebDavAnnotationSyncTargetFactory,
```

And add/update the import at the top of the file.

- [ ] **Step 8: Delete migrated test files from `core:data`**

```bash
rm core/data/src/test/kotlin/com/riffle/core/data/credentialed/KomgaCredentialedAuthenticatorTest.kt
rm core/data/src/test/kotlin/com/riffle/core/data/WebDavAnnotationSyncTargetTest.kt
rm core/data/src/test/kotlin/com/riffle/core/data/WebDavAnnotationSyncTargetFactoryTest.kt
```

- [ ] **Step 9: Update `SourceRepositoryTest.kt` — reference `AbsSourceAdapter` in `core:sources`**

Change:
```kotlin
): com.riffle.core.data.credentialed.AbsCredentialedAuthenticator =
    com.riffle.core.data.credentialed.AbsCredentialedAuthenticator(
        absApi = absApi,
        libraryApi = libraryApi,
        storytellerApi = storytellerApi,
    )
```
to:
```kotlin
): com.riffle.core.sources.abs.AbsSourceAdapter =
    com.riffle.core.sources.abs.AbsSourceAdapter(
        absApi = absApi,
        libraryApi = libraryApi,
        storytellerApi = storytellerApi,
    )
```

- [ ] **Step 10: Build `core:data` to confirm no unresolved references**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :core:data:compileDebugKotlin 2>&1 | grep -E "error:|warning:|BUILD" | tail -30
```

Expected: BUILD SUCCESSFUL (zero errors).

- [ ] **Step 11: Run `core:data` JVM tests**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :core:data:test
```

Expected: all tests pass (the deleted tests are gone; remaining tests still pass).

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "refactor(sources): wire core:data to use moved classes from core:sources (Phase 3)"
```

---

### Task 8: Verify the full build and `checkNoAndroidImports`

- [ ] **Step 1: Run `checkNoAndroidImports`**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew checkNoAndroidImports
```

Expected: BUILD SUCCESSFUL — `core/sources` contains no `android.*` or `androidx.*` (except `annotation`) imports.

- [ ] **Step 2: Run `:core:sources:test` — the Phase 3 success criterion**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :core:sources:test
```

Expected: BUILD SUCCESSFUL. No MockWebServer. All tests use MockEngine only.

- [ ] **Step 3: Run full JVM test suite to catch any regressions**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew test
```

Expected: all JVM test modules green. Note: `core:database` migration tests have 7 pre-existing failures on API-25 (documented in memory — skip those). All other modules must be green.

- [ ] **Step 4: Commit if there were any fixups, else no-op**

```bash
git status
# only commit if there are unstaged changes from fixups
```

---

### Task 9: Update `app` module — update map injection type (if needed)

The `AddSourceViewModel.kt` in `app` injects `Map<SourceType, @JvmSuppressWildcards CredentialedAuthenticator>`. Since `CredentialedAuthenticator` in `core:data` is now a `typealias` for `SourceAdapter`, this should compile without changes. Verify and fix if Hilt complains.

- [ ] **Step 1: Compile `app` debug**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|warning:|BUILD" | tail -30
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: If Hilt rejects the typealias, update `AddSourceViewModel.kt`**

If you see a Hilt error like `"[Dagger/MissingBinding] Map<SourceType, CredentialedAuthenticator>"`, change the injection site in `AddSourceViewModel.kt`:

```kotlin
// Change:
private val authenticators: Map<SourceType, @JvmSuppressWildcards CredentialedAuthenticator>,
// To:
private val authenticators: Map<SourceType, @JvmSuppressWildcards com.riffle.core.sources.SourceAdapter>,
```

And update the import accordingly.

- [ ] **Step 3: Run `app` JVM tests**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :app:test
```

Expected: all `app` JVM tests pass.

- [ ] **Step 4: Commit any changes**

```bash
git add -A
git commit -m "fix(app): update authenticator map type after CredentialedAuthenticator → SourceAdapter rename (Phase 3)"
```

---

## Regression test summary

Before opening the PR, confirm each of the following assertions can be named by line:

| Assertion | File | Passes after change | Would fail if reverted |
|---|---|---|---|
| ABS login success → `AuthenticateResult.Success` | `AbsSourceAdapterTest.kt` | Yes | `AbsSourceAdapter` deleted |
| Komga 401 → `WrongCredentials` | `KomgaSourceAdapterTest.kt` | Yes | `KomgaSourceAdapter` deleted |
| Komga v2 404 → falls back to v1 | `KomgaSourceAdapterTest.kt` | Yes | probe logic reverted |
| WebDAV read on 200 returns body | `WebDavAnnotationSyncTargetTest.kt` | Yes | `WebDavAnnotationSyncTarget` deleted from sources |
| `checkNoAndroidImports` passes for `core/sources` | Gradle task | Yes | any Android import added |
| `./gradlew :core:sources:test` is green | All 4 test classes | Yes | any test broken |
