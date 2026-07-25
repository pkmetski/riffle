# Ktor Networking Migration (Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace direct OkHttp usage with Ktor `HttpClient` (OkHttp engine) so source adapters can become pure Kotlin in Phase 3.

**Architecture:** Add Ktor to `core:network` with OkHttp as the engine (behavior-identical). Adapters receive `io.ktor.client.HttpClient` instead of `okhttp3.OkHttpClient`. A new `KtorClassifier` replaces `OkHttpClassifier`. Streaming interfaces expose `InputStream` instead of `okhttp3.ResponseBody`. Tests keep using `MockWebServer`; the Ktor `HttpClient` points at it via the OkHttp engine.

**Tech Stack:** Ktor 3.1.3, OkHttp 5.4.0 (as Ktor engine), kotlinx.serialization (already present), MockWebServer (tests, unchanged).

## Global Constraints

- Ktor version: `3.1.3`
- OkHttp stays as engine-only; no `okhttp3.*` imports outside `core:network`'s engine configuration after migration.
- All `NetworkResult` variants and public API signatures stay identical.
- `DispatcherProvider` injected in constructors stays (removed from `classify` call itself — Ktor handles its own dispatch).
- No behavior change in cache behavior, insecure TLS, timeout configuration, retry logic, or User-Agent stamping.
- Tests keep using `MockWebServer` — create Ktor `HttpClient` with OkHttp engine pointing at `server.url("/")`.
- `AudiobookBundleStream.body` and `StorytellerBundleStream.body` change type from `okhttp3.ResponseBody` to `java.io.InputStream`.

---

### Task 1: Add Ktor to version catalog and build files

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `core/network/build.gradle.kts`
- Modify: `core/catalog-komga/build.gradle.kts`
- Modify: `core/catalog-chitanka/build.gradle.kts`
- Modify: `core/catalog-gutenberg/build.gradle.kts`
- Modify: `core/data/build.gradle.kts`

**Interfaces:**
- Produces: `libs.ktor.client.core`, `libs.ktor.client.okhttp`, `libs.ktor.client.content.negotiation`, `libs.ktor.serialization.kotlinx.json`, `libs.ktor.client.mock` available in all relevant modules.

- [ ] **Step 1: Add Ktor version and libraries to libs.versions.toml**

In `gradle/libs.versions.toml`, add after `okhttp = "5.4.0"`:
```toml
ktor = "3.1.3"
```

In `[libraries]` section, add:
```toml
ktor-client-core = { group = "io.ktor", name = "ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { group = "io.ktor", name = "ktor-client-okhttp", version.ref = "ktor" }
ktor-client-content-negotiation = { group = "io.ktor", name = "ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { group = "io.ktor", name = "ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-mock = { group = "io.ktor", name = "ktor-client-mock", version.ref = "ktor" }
```

- [ ] **Step 2: Update core/network/build.gradle.kts**

Replace current dependencies block:
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:domain"))
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}
```

- [ ] **Step 3: Update catalog and data build files**

In `core/catalog-komga/build.gradle.kts`, replace `implementation(libs.okhttp)` with:
```kotlin
implementation(libs.ktor.client.core)
```

In `core/catalog-chitanka/build.gradle.kts`, replace `implementation(libs.okhttp)` with:
```kotlin
implementation(libs.ktor.client.core)
```

In `core/catalog-gutenberg/build.gradle.kts`, replace `implementation(libs.okhttp)` with:
```kotlin
implementation(libs.ktor.client.core)
```

In `core/data/build.gradle.kts`, find the line with `// okhttp` or wherever OkHttp is and add:
```kotlin
implementation(libs.ktor.client.core)
implementation(libs.ktor.client.okhttp)
```
(Keep okhttp-mockwebserver in testImplementation for tests.)

- [ ] **Step 4: Verify build resolves**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr
./gradlew :core:network:dependencies --configuration compileClasspath 2>&1 | grep -E "ktor|okhttp" | head -20
```
Expected: Ktor libraries resolved.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml core/network/build.gradle.kts core/catalog-komga/build.gradle.kts core/catalog-chitanka/build.gradle.kts core/catalog-gutenberg/build.gradle.kts core/data/build.gradle.kts
git commit -m "build: add Ktor 3.1.3 to version catalog and module build files"
```

---

### Task 2: Create Ktor infrastructure in core:network

**Files:**
- Create: `core/network/src/main/kotlin/com/riffle/core/network/KtorClientFactory.kt`
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/NetworkResult.kt` (remove OkHttp extensions, add Ktor helpers)
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/InsecureTls.kt` (add Ktor version)

**Interfaces:**
- Produces:
  - `KtorClassifier.classify(block)` — suspend, returns `NetworkResult<T>`
  - `createDefaultHttpClient(okHttpClient: OkHttpClient): HttpClient`
  - `HttpClient.withInsecureTls(): HttpClient` — returns a new client with trust-all TLS

- [ ] **Step 1: Create KtorClientFactory.kt**

Create `core/network/src/main/kotlin/com/riffle/core/network/KtorClientFactory.kt`:
```kotlin
package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

val RIFFLE_JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/**
 * Creates the default Ktor HttpClient backed by the OkHttp engine, pre-configured with
 * kotlinx.serialization content negotiation using Riffle's Json instance.
 */
fun createDefaultHttpClient(okHttpClient: OkHttpClient): HttpClient = HttpClient(OkHttp) {
    engine {
        preconfigured = okHttpClient
    }
    install(ContentNegotiation) {
        json(RIFFLE_JSON)
    }
}
```

- [ ] **Step 2: Update NetworkResult.kt — replace OkHttp helpers with Ktor equivalents**

Remove the OkHttp-specific extension functions (`requireSuccessful()`, `requireBody()` on `okhttp3.Response`) and the `OkHttpClassifier` object. Add Ktor classifier and helper.

The file should end up looking like:
```kotlin
package com.riffle.core.network

import com.riffle.core.models.InsecureConnectionType
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerializationException
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

sealed class NetworkResult<out T> {
    data class Success<T>(val value: T) : NetworkResult<T>()
    data class Offline(val cause: Throwable) : NetworkResult<Nothing>()
    data object Auth : NetworkResult<Nothing>()
    data class ServerError(val code: Int, val errorMessage: String? = null) : NetworkResult<Nothing>()
    data class Parse(val cause: Throwable) : NetworkResult<Nothing>()
    data class InsecureConnection(val type: InsecureConnectionType) : NetworkResult<Nothing>()
    data class Unknown(val cause: Throwable) : NetworkResult<Nothing>()
}

internal class HttpException(val code: Int, msg: String? = null) : IOException(msg)

object KtorClassifier {
    suspend fun <T> classify(block: suspend () -> T): NetworkResult<T> = try {
        NetworkResult.Success(block())
    } catch (e: HttpException) {
        if (e.code == 401) NetworkResult.Auth
        else NetworkResult.ServerError(e.code, e.message)
    } catch (e: ResponseException) {
        val code = e.response.status.value
        if (code == 401) NetworkResult.Auth
        else NetworkResult.ServerError(code, e.message)
    } catch (e: SSLHandshakeException) {
        NetworkResult.InsecureConnection(InsecureConnectionType.SELF_SIGNED)
    } catch (e: SerializationException) {
        NetworkResult.Parse(e)
    } catch (e: IOException) {
        NetworkResult.Offline(e)
    } catch (e: Throwable) {
        NetworkResult.Unknown(e)
    }
}

// Keep OkHttpClassifier as a deprecated alias so we can migrate callers one at a time.
@Deprecated("Use KtorClassifier", replaceWith = ReplaceWith("KtorClassifier"))
object OkHttpClassifier {
    @Deprecated("Use KtorClassifier.classify")
    suspend fun <T> classify(io: kotlinx.coroutines.CoroutineDispatcher, block: suspend () -> T): NetworkResult<T> =
        kotlinx.coroutines.withContext(io) { KtorClassifier.classify { block() } }
}

inline fun <T, R> NetworkResult<T>.mapResult(f: (T) -> R): NetworkResult<R> = when (this) {
    is NetworkResult.Success -> NetworkResult.Success(f(value))
    is NetworkResult.Offline -> this
    is NetworkResult.Auth -> this
    is NetworkResult.ServerError -> this
    is NetworkResult.Parse -> this
    is NetworkResult.InsecureConnection -> this
    is NetworkResult.Unknown -> this
}

inline fun <T> NetworkResult<T>.onError(report: (NetworkResult<Nothing>) -> Unit): NetworkResult<T> {
    if (this !is NetworkResult.Success) {
        @Suppress("UNCHECKED_CAST")
        report(this as NetworkResult<Nothing>)
    }
    return this
}

val NetworkResult<*>.isError: Boolean get() = this !is NetworkResult.Success

fun <T> NetworkResult<T>.getOrNull(): T? = (this as? NetworkResult.Success)?.value

fun NetworkResult<*>.errorAsThrowable(): Throwable = when (this) {
    is NetworkResult.Success -> error("Success has no error")
    is NetworkResult.Offline -> cause
    is NetworkResult.Parse -> cause
    is NetworkResult.Unknown -> cause
    is NetworkResult.ServerError -> IOException("HTTP $code${errorMessage?.let { ": $it" } ?: ""}")
    NetworkResult.Auth -> IOException("HTTP 401")
    is NetworkResult.InsecureConnection -> SSLHandshakeException("Insecure connection ($type)")
}
```

- [ ] **Step 3: Update InsecureTls.kt — add Ktor version**

Add alongside the existing `OkHttpClient.withInsecureTls()`:
```kotlin
package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal val TRUST_ALL_MANAGER = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal fun OkHttpClient.withInsecureTls(): OkHttpClient {
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(TRUST_ALL_MANAGER), SecureRandom())
    }
    return newBuilder()
        .sslSocketFactory(sslContext.socketFactory, TRUST_ALL_MANAGER)
        .hostnameVerifier { _, _ -> true }
        .build()
}

internal fun HttpClient.withInsecureTls(): HttpClient {
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(TRUST_ALL_MANAGER), SecureRandom())
    }
    return config {
        engine {
            this as io.ktor.client.engine.okhttp.OkHttpConfig
            config {
                sslSocketFactory(sslContext.socketFactory, TRUST_ALL_MANAGER)
                hostnameVerifier { _, _ -> true }
            }
        }
    }
}
```

- [ ] **Step 4: Verify compile**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr
./gradlew :core:network:compileKotlin 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL (or only errors in not-yet-migrated callers).

- [ ] **Step 5: Commit**

```bash
git add core/network/src/main/kotlin/com/riffle/core/network/KtorClientFactory.kt \
        core/network/src/main/kotlin/com/riffle/core/network/NetworkResult.kt \
        core/network/src/main/kotlin/com/riffle/core/network/InsecureTls.kt
git commit -m "feat(network): add Ktor infrastructure — KtorClassifier, HttpClient factory, insecure-TLS helper"
```

---

### Task 3: Migrate AbsApiClient to Ktor

**Files:**
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt`

**Interfaces:**
- Consumes: `HttpClient` (Ktor), `KtorClassifier.classify`, `RIFFLE_JSON`
- Produces: Same `AbsApi`, `AbsLibraryApi`, etc. interface implementations, just using Ktor internally.

**Key migration pattern:**

OkHttp pattern:
```kotlin
OkHttpClassifier.classify(dispatchers.io) {
    val request = Request.Builder().url("$baseUrl/api/login").post(body).build()
    httpClient.newCall(request).execute().use { r ->
        r.requireSuccessful()
        json.decodeFromString<AbsLoginResponse>(r.requireBody()).toNetwork()
    }
}
```

Ktor pattern:
```kotlin
KtorClassifier.classify {
    httpClient.post("$baseUrl/api/login") {
        contentType(ContentType.Application.Json)
        setBody(AbsLoginRequest(username, password))
    }.body<AbsLoginResponse>().toNetwork()
}
```

- [ ] **Step 1: Rewrite AbsApiClient constructor and imports**

Change:
```kotlin
class AbsApiClient(
    private val httpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
) : AbsApi, ...
```
To:
```kotlin
class AbsApiClient(
    private val httpClient: io.ktor.client.HttpClient,
) : AbsApi, ...
```
(Remove `dispatchers` — Ktor handles dispatch internally. Remove `json` and `jsonMediaType` locals — Ktor ContentNegotiation handles these.)

- [ ] **Step 2: Migrate each method group**

For each method in `AbsApiClient`, apply the Ktor migration pattern. Methods fall into these shapes:

**GET → body<T>():**
```kotlin
override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
    KtorClassifier.classify {
        client(insecureAllowed).get("$baseUrl/api/libraries") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<AbsLibrariesResponse>().libraries.map { it.toNetwork() }
    }
```

**POST with JSON body:**
```kotlin
override suspend fun login(baseUrl: String, username: String, password: String, insecureAllowed: Boolean): NetworkResult<NetworkLoginUser> =
    KtorClassifier.classify {
        client(insecureAllowed).post("$baseUrl/api/login") {
            contentType(ContentType.Application.Json)
            setBody(AbsLoginRequest(username, password))
        }.body<AbsLoginResponse>().toNetwork()
    }
```

**PATCH/DELETE (no body back):**
```kotlin
override suspend fun deleteItem(baseUrl: String, token: String, itemId: String, insecureAllowed: Boolean): NetworkResult<Unit> =
    KtorClassifier.classify {
        client(insecureAllowed).delete("$baseUrl/api/items/$itemId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        Unit
    }
```

**Caching override (FORCE_NETWORK equivalent):**
For endpoints where the old code set `CacheControl.FORCE_NETWORK`, add Ktor's cache control header:
```kotlin
header(HttpHeaders.CacheControl, "no-cache")
```

**Insecure TLS helper:**
```kotlin
private fun client(insecureAllowed: Boolean): HttpClient =
    if (insecureAllowed) httpClient.withInsecureTls() else httpClient
```

Apply this pattern to every method in `AbsApiClient.kt`.

- [ ] **Step 3: Compile check**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr
./gradlew :core:network:compileKotlin 2>&1 | grep -E "error:|warning:" | head -30
```

- [ ] **Step 4: Commit**

```bash
git add core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt
git commit -m "feat(network): migrate AbsApiClient from OkHttp to Ktor"
```

---

### Task 4: Migrate StorytellerApiClient, StorytellerPositionApiImpl, GitHubReleaseApi to Ktor

**Files:**
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/StorytellerApiClient.kt`
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/StorytellerPositionApi.kt`
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/GitHubReleaseApi.kt`

**Interfaces:**
- Consumes: `HttpClient` (Ktor), `KtorClassifier`
- Produces: Same interfaces implemented via Ktor

**StorytellerApiClient migration:**

Constructor change:
```kotlin
class StorytellerApiClient(
    private val httpClient: io.ktor.client.HttpClient,
) : StorytellerApi, StorytellerLibraryApi
```

Login (multipart form):
```kotlin
override suspend fun login(baseUrl: String, username: String, password: String, insecureAllowed: Boolean): NetworkResult<String> =
    KtorClassifier.classify {
        val response = client(insecureAllowed).submitForm(
            url = "$baseUrl/api/token",
            formParameters = parameters {
                append("username", username)
                append("password", password)
            }
        )
        when (response.status.value) {
            200 -> response.body<StorytellerLoginResponse>().accessToken
            400, 401, 405 -> throw HttpException(401, "Invalid username or password")
            else -> throw HttpException(response.status.value, response.status.description)
        }
    }
```

**GitHubReleaseApi migration:**

Constructor: `class GitHubReleaseApi(private val httpClient: HttpClient, ...)`

`latestRelease`:
```kotlin
suspend fun latestRelease(repo: String): GitHubReleaseResult = try {
    val response = httpClient.get("$apiBaseUrl/repos/$repo/releases") {
        parameter("per_page", 10)
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header(HttpHeaders.CacheControl, "no-cache")
    }
    if (!response.status.isSuccess()) return GitHubReleaseResult.Failed("HTTP ${response.status.value}")
    val releases = response.body<List<ReleaseResponse>>()
    for (release in releases) {
        if (release.draft || release.prerelease) continue
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: continue
        return GitHubReleaseResult.Success(GitHubRelease(release.tagName, apk.downloadUrl, apk.size))
    }
    GitHubReleaseResult.Failed("No release with an APK asset")
} catch (e: IOException) {
    GitHubReleaseResult.Failed(e.message ?: "Network error")
}
```

`download` (streaming to File):
```kotlin
suspend fun download(url: String, dest: File, onProgress: (percent: Int) -> Unit): Boolean = try {
    httpClient.prepareGet(url).execute { response ->
        val body = response.bodyAsChannel()
        val total = response.contentLength() ?: -1L
        dest.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            var lastPercent = -1
            while (!body.isClosedForRead) {
                val read = body.readAvailable(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
                copied += read
                if (total > 0) {
                    val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
                }
            }
        }
        true
    }
} catch (e: IOException) {
    dest.delete()
    false
}
```

- [ ] **Step 1: Apply migrations to all three files**

- [ ] **Step 2: Compile check**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr
./gradlew :core:network:compileKotlin 2>&1 | grep "error:" | head -20
```

- [ ] **Step 3: Commit**

```bash
git add core/network/src/main/kotlin/com/riffle/core/network/StorytellerApiClient.kt \
        core/network/src/main/kotlin/com/riffle/core/network/StorytellerPositionApi.kt \
        core/network/src/main/kotlin/com/riffle/core/network/GitHubReleaseApi.kt
git commit -m "feat(network): migrate Storyteller and GitHub API clients to Ktor"
```

---

### Task 5: Migrate streaming APIs (AudiobookBundleApi, StorytellerBundleApi)

**Files:**
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/AudiobookBundleApi.kt`
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/StorytellerBundleApi.kt`

**Key change:** `body: ResponseBody` → `body: InputStream`. Consumers of these streams must be updated too (search for usages of `AudiobookBundleStream.body` and `StorytellerBundleStream.body`).

**AudiobookBundleApi migration:**

```kotlin
data class AudiobookBundleStream(val body: InputStream, val totalBytes: Long, val isPartial: Boolean)

class AudiobookBundleApiImpl(
    private val client: io.ktor.client.HttpClient,
) : AudiobookBundleApi {

    override suspend fun openBundleStream(
        baseUrl: String, bookId: String, token: String, insecureAllowed: Boolean, fromByte: Long,
    ): NetworkResult<AudiobookBundleStream> {
        val effectiveClient = if (insecureAllowed) client.withInsecureTls() else client
        return try {
            effectiveClient.prepareGet("$baseUrl/api/books/$bookId/synced") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, "application/audiobook+zip")
                if (fromByte > 0L) header(HttpHeaders.Range, "bytes=$fromByte-")
                // Disable timeout for streaming
                timeout { requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    return@execute NetworkResult.Offline(IOException("HTTP ${response.status.value}"))
                }
                val isPartial = response.status == HttpStatusCode.PartialContent
                val total = if (isPartial) {
                    response.headers[HttpHeaders.ContentRange]?.substringAfter('/')?.toLongOrNull()
                } else {
                    response.contentLength()
                } ?: -1L
                currentCoroutineContext().ensureActive()
                NetworkResult.Success(AudiobookBundleStream(
                    body = response.bodyAsChannel().toInputStream(),
                    totalBytes = total,
                    isPartial = isPartial,
                ))
            }
        } catch (e: IOException) {
            NetworkResult.Offline(e)
        }
    }
}
```

**StorytellerBundleApiImpl migration:**

For the multiple timeout variants, use Ktor's `HttpRequestTimeoutException` and per-request `timeout { }` config. The `sidecarClient` → sidecar requests with bounded `requestTimeoutMillis`. The `bundleClient` → unbounded timeout. The `sidecarStreamClient` → bounded `requestTimeoutMillis` for whole streaming.

```kotlin
data class StorytellerBundleStream(val body: InputStream)

class StorytellerBundleApiImpl(
    private val client: io.ktor.client.HttpClient,
    private val sidecarCallTimeoutSeconds: Long = SIDECAR_CALL_TIMEOUT_SECONDS,
    private val sidecarStreamTimeoutSeconds: Long = SIDECAR_STREAM_TIMEOUT_SECONDS,
) : StorytellerBundleApi, StorytellerBundleProbeApi {

    override suspend fun downloadBundle(...): NetworkResult<StorytellerBundleStream> =
        openStream(timeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS, baseUrl, bookId, token, insecureAllowed)

    suspend fun streamSidecar(...): NetworkResult<StorytellerBundleStream> =
        openStream(timeoutMillis = sidecarStreamTimeoutSeconds * 1000, baseUrl, bookId, token, insecureAllowed)

    private suspend fun openStream(timeoutMillis: Long, ...): NetworkResult<StorytellerBundleStream> {
        val effectiveClient = if (insecureAllowed) client.withInsecureTls() else client
        return try {
            effectiveClient.prepareGet("$baseUrl/api/books/$bookId/synced") {
                header(HttpHeaders.Authorization, "Bearer $token")
                timeout { requestTimeoutMillis = timeoutMillis }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    return@execute NetworkResult.Offline(IOException("HTTP ${response.status.value}"))
                }
                currentCoroutineContext().ensureActive()
                NetworkResult.Success(StorytellerBundleStream(
                    body = response.bodyAsChannel().toInputStream()
                ))
            }
        } catch (e: IOException) { NetworkResult.Offline(e) }
    }

    override suspend fun probeBundleSize(...): NetworkResult<Long> {
        val effectiveClient = if (insecureAllowed) client.withInsecureTls() else client
        return try {
            val response = effectiveClient.head("$baseUrl/api/books/$bookId/synced") {
                header(HttpHeaders.Authorization, "Bearer $token")
                timeout { requestTimeoutMillis = sidecarCallTimeoutSeconds * 1000 }
            }
            if (!response.status.isSuccess()) return NetworkResult.Offline(IOException("HTTP ${response.status.value}"))
            val length = response.contentLength() ?: return NetworkResult.Offline(IOException("Missing Content-Length"))
            NetworkResult.Success(length)
        } catch (e: IOException) { NetworkResult.Offline(e) }
    }

    companion object {
        const val SIDECAR_CALL_TIMEOUT_SECONDS = 15L
        const val SIDECAR_STREAM_TIMEOUT_SECONDS = 240L
    }
}
```

- [ ] **Step 1: Update AudiobookBundleApi.kt and StorytellerBundleApi.kt**

- [ ] **Step 2: Find and update consumers of `.body` on AudiobookBundleStream / StorytellerBundleStream**

```bash
grep -rn "\.body\." /Users/plamen.kmetski/conductor/workspaces/riffle/singapore-v2/core/data/src \
    --include="*.kt" | grep -E "BundleStream|bundleStream"
grep -rn "AudiobookBundleStream\|StorytellerBundleStream" /Users/plamen.kmetski/conductor/workspaces/riffle/singapore-v2/app/src \
    --include="*.kt"
```
Update each consumer: `.body.byteStream()` → `.body` (already InputStream), `.body.source()` → `body.source()` via Okio adapter (or use `InputStreamAdapter`).

- [ ] **Step 3: Compile check**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr
./gradlew :core:network:compileKotlin :core:data:compileKotlin :app:compileDebugKotlin 2>&1 | grep "error:" | head -30
```

- [ ] **Step 4: Commit**

```bash
git add core/network/src/main/kotlin/com/riffle/core/network/AudiobookBundleApi.kt \
        core/network/src/main/kotlin/com/riffle/core/network/StorytellerBundleApi.kt
git commit -m "feat(network): migrate streaming bundle APIs to Ktor; ResponseBody → InputStream"
```

---

### Task 6: Update NetworkModule.kt (DI) to provide Ktor HttpClient

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/modules/NetworkModule.kt`

**Key change:** `provideOkHttpClient()` now creates OkHttp with cache+interceptors and wraps it via `createDefaultHttpClient()`. `provideWebSourceOkHttpClient()` similarly. All `@Provides` that took `OkHttpClient` now take `HttpClient`. Binds stay the same.

```kotlin
@Provides
@Singleton
fun provideHttpClient(@ApplicationContext context: Context): HttpClient {
    val cacheDir = File(context.cacheDir, "default-http")
    val cache = Cache(cacheDir, DEFAULT_HTTP_CACHE_BYTES)
    val okHttpClient = OkHttpClient.Builder()
        .cache(cache)
        .addNetworkInterceptor(EndpointCacheHeadersInterceptor(DEFAULT_HTTP_CACHE_RULES))
        .build()
    return createDefaultHttpClient(okHttpClient)
}

@Provides
@Singleton
@WebSourceOkHttpClient  // rename qualifier to @WebSourceHttpClient
fun provideWebSourceHttpClient(@ApplicationContext context: Context): HttpClient {
    val cacheDir = File(context.cacheDir, "web-source-http")
    val cache = Cache(cacheDir, WEB_SOURCE_CACHE_BYTES)
    val okHttpClient = OkHttpClient.Builder()
        .cache(cache)
        .addNetworkInterceptor(ForceCacheHeadersInterceptor(WEB_SOURCE_MAX_AGE_SECONDS))
        .addInterceptor(OfflineStaleFallbackInterceptor())
        .build()
    return createDefaultHttpClient(okHttpClient)
}

@Provides
@Singleton
fun provideGitHubReleaseApi(httpClient: HttpClient): GitHubReleaseApi =
    GitHubReleaseApi(httpClient)

@Provides
@Singleton
fun provideAbsApiClient(httpClient: HttpClient): AbsApiClient =
    AbsApiClient(httpClient)

@Provides
@Singleton
fun provideStorytellerApiClient(httpClient: HttpClient): StorytellerApiClient =
    StorytellerApiClient(httpClient)

@Provides
@Singleton
fun provideStorytellerBundleApiImpl(httpClient: HttpClient): StorytellerBundleApiImpl =
    StorytellerBundleApiImpl(httpClient)

@Provides
@Singleton
fun provideAudiobookBundleApi(httpClient: HttpClient): AudiobookBundleApiImpl =
    AudiobookBundleApiImpl(httpClient)

@Provides
@Singleton
fun provideStorytellerPositionApi(httpClient: HttpClient): StorytellerPositionApi =
    StorytellerPositionApiImpl(httpClient)
```

- [ ] **Step 1: Rewrite NetworkModule.kt with Ktor providers**

Also rename `@WebSourceOkHttpClient` qualifier to `@WebSourceHttpClient` (or keep name and just change internal type). Update all injection sites that use the qualifier.

- [ ] **Step 2: Compile check (Hilt processes annotations)**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr
./gradlew :core:data:kspDebugKotlin 2>&1 | grep -E "error:|warning:" | head -30
```

- [ ] **Step 3: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/di/
git commit -m "feat(data): update NetworkModule to provide Ktor HttpClient instead of OkHttpClient"
```

---

### Task 7: Migrate catalog HTTP clients to Ktor

**Files:**
- Modify: `core/catalog-komga/src/main/kotlin/com/riffle/core/catalog/komga/KomgaHttpClient.kt`
- Modify: `core/catalog-chitanka/src/main/kotlin/com/riffle/core/catalog/chitanka/ChitankaHttpClient.kt`
- Modify: `core/catalog-gutenberg/src/main/kotlin/com/riffle/core/catalog/gutenberg/GutenbergHttpClient.kt`

**KomgaHttpClient migration:**

Constructor: `class KomgaHttpClient(private val client: HttpClient, private val basicAuthHeader: String, ...)`

`getString`:
```kotlin
suspend fun getString(url: String): String {
    val response = client.get(url) {
        header(HttpHeaders.Authorization, basicAuthHeader)
        header(HttpHeaders.UserAgent, userAgent)
        accept(ContentType.Application.Json)
    }
    if (!response.status.isSuccess()) throw KomgaHttpException(response.status.value, url, response.status.description)
    return response.bodyAsText()
}
```

`getStreaming` (returns InputStream now):
```kotlin
suspend fun getStreaming(url: String): InputStream {
    val response = client.get(url) {
        header(HttpHeaders.Authorization, basicAuthHeader)
        header(HttpHeaders.UserAgent, userAgent)
    }
    if (!response.status.isSuccess()) throw KomgaHttpException(response.status.value, url, response.status.description)
    return response.bodyAsChannel().toInputStream()
}
```

(Remove the `Call.await()` extension — not needed with Ktor.)

**ChitankaHttpClient migration:**

Constructor: `class ChitankaHttpClient(private val client: HttpClient, ...)`

`getString` (with retry):
```kotlin
suspend fun getString(url: String): String {
    var attempt = 0
    while (true) {
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.AcceptLanguage, "bg,en;q=0.5")
        }
        if (response.status.value == 429 && attempt < retryDelaysMs.size) {
            delay(retryDelaysMs[attempt++])
            continue
        }
        if (!response.status.isSuccess()) throw ChitankaHttpException(response.status.value, url, response.status.description)
        return response.bodyAsText()
    }
}
```

`rangeGet` becomes suspend:
```kotlin
private suspend fun rangeGet(url: String, start: Long, endInclusive: Long): RangeReply? {
    val response = try {
        client.get(url) {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Range, "bytes=$start-$endInclusive")
        }
    } catch (_: IOException) { return null }
    if (!response.status.isSuccess()) return null
    val total = response.headers[HttpHeaders.ContentRange]?.substringAfter("/")?.toLongOrNull()
        ?: response.contentLength()
        ?: return null
    return RangeReply(response.readBytes(), total)
}
```

`probeMp3DurationSec` now just calls suspend `rangeGet` directly (no `withContext` needed).

**GutenbergHttpClient migration:**

Constructor: `class GutenbergHttpClient(private val client: HttpClient, ...)`

`getString` (with retry on 429/503):
```kotlin
suspend fun getString(url: String): String {
    var attempt = 0
    while (true) {
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, userAgent)
            accept(ContentType.Application.Json)
            accept(ContentType("text", "html", mapOf("q" to "0.5")))
        }
        if ((response.status.value == 429 || response.status.value == 503) && attempt < retryDelaysMs.size) {
            delay(retryDelaysMs[attempt++])
            continue
        }
        if (!response.status.isSuccess()) throw GutenbergHttpException(response.status.value, url, response.status.description)
        return response.bodyAsText()
    }
}
```

- [ ] **Step 1: Migrate all three catalog clients**

- [ ] **Step 2: Update catalog factories to inject HttpClient instead of OkHttpClient**

In `KomgaCatalogFactory`, `ChitankaCatalogFactory`, `GutenbergCatalogFactory`, change constructor parameter type from `OkHttpClient` to `HttpClient`.

Also update any Hilt modules that provide these (likely in `core:data` DI).

- [ ] **Step 3: Compile check**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr
./gradlew :core:catalog-komga:compileKotlin :core:catalog-chitanka:compileKotlin :core:catalog-gutenberg:compileKotlin 2>&1 | grep "error:" | head -30
```

- [ ] **Step 4: Commit**

```bash
git add core/catalog-komga/ core/catalog-chitanka/ core/catalog-gutenberg/
git commit -m "feat(catalogs): migrate Komga, Chitanka, Gutenberg HTTP clients from OkHttp to Ktor"
```

---

### Task 8: Migrate WebDavAnnotationSyncTarget to Ktor

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/WebDavAnnotationSyncTarget.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/WebDavAnnotationSyncTargetFactory.kt`

**Key changes:**
- Constructor: `client: OkHttpClient` → `client: HttpClient`
- `Credentials.basic(username, password)` → `"Basic ${Base64.getEncoder().encodeToString("$username:$password".toByteArray())}"`
- `HttpUrl` → `Url` (Ktor) for baseUrl
- PROPFIND is a custom HTTP method — use `client.request { method = HttpMethod("PROPFIND"); ... }`

**WebDavAnnotationSyncTarget migration:**

```kotlin
class WebDavAnnotationSyncTarget(
    baseUrl: io.ktor.http.Url,
    username: String,
    password: String,
    private val client: HttpClient,
    private val dispatchers: DispatcherProvider,
) : AnnotationSyncTarget {

    private val authHeader: String = "Basic " + 
        java.util.Base64.getEncoder().encodeToString("$username:$password".toByteArray())

    private suspend fun propfind(): String = client.request(basePath) {
        method = HttpMethod("PROPFIND")
        header(HttpHeaders.Authorization, authHeader)
        header(HttpHeaders.UserAgent, FINDER_UA)
        header("Depth", "1")
    }.bodyAsText()

    private suspend fun readFile(url: Url): String? {
        val response = client.get(url) {
            header(HttpHeaders.Authorization, authHeader)
            header(HttpHeaders.UserAgent, FINDER_UA)
        }
        return when (response.status.value) {
            404 -> null
            401, 403 -> throw AnnotationSyncException.AuthFailed()
            in 200..299 -> response.bodyAsText()
            else -> throw AnnotationSyncException.HttpFailure(response.status.value)
        }
    }

    private suspend fun putFile(url: Url, content: String, contentType: ContentType, op: String) {
        val response = client.put(url) {
            header(HttpHeaders.Authorization, authHeader)
            header(HttpHeaders.UserAgent, FINDER_UA)
            contentType(contentType)
            setBody(content)
        }
        when (response.status.value) {
            in 200..299 -> Unit
            401, 403 -> throw AnnotationSyncException.AuthFailed()
            else -> throw AnnotationSyncException.HttpFailure(response.status.value)
        }
    }

    private suspend fun deleteFile(url: Url, op: String) {
        val response = client.delete(url) {
            header(HttpHeaders.Authorization, authHeader)
            header(HttpHeaders.UserAgent, FINDER_UA)
        }
        when (response.status.value) {
            in 200..299, 404 -> Unit
            401, 403 -> throw AnnotationSyncException.AuthFailed()
            else -> throw AnnotationSyncException.HttpFailure(response.status.value)
        }
    }
}
```

**WebDavAnnotationSyncTargetFactory:**

```kotlin
class WebDavAnnotationSyncTargetFactory(
    private val client: HttpClient,
    private val dispatchers: DispatcherProvider,
) {
    fun create(config: AnnotationSyncConfig): WebDavAnnotationSyncTarget? {
        val url = try { URLBuilder(config.baseUrl).build() } catch (_: Exception) { return null }
        if (config.baseUrl.isBlank()) return null
        return WebDavAnnotationSyncTarget(url, config.username, config.password, client, dispatchers)
    }
}
```

- [ ] **Step 1: Rewrite WebDavAnnotationSyncTarget.kt with Ktor**

(The XML parsing via SAX stays unchanged — it's pure Java, not OkHttp.)

- [ ] **Step 2: Update WebDavAnnotationSyncTargetFactory.kt**

- [ ] **Step 3: Compile check**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr
./gradlew :core:data:compileKotlin 2>&1 | grep "error:" | head -30
```

- [ ] **Step 4: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/WebDavAnnotationSyncTarget.kt \
        core/data/src/main/kotlin/com/riffle/core/data/WebDavAnnotationSyncTargetFactory.kt
git commit -m "feat(data): migrate WebDavAnnotationSyncTarget from OkHttp to Ktor"
```

---

### Task 9: Update all tests in core:network to use Ktor + MockWebServer

**Files:**
- Modify: All `*.kt` in `core/network/src/test/` (~20 files)

**Key pattern change:**

Before:
```kotlin
private lateinit var server: MockWebServer
private lateinit var client: OkHttpClient

@Before fun setUp() {
    server = MockWebServer(); server.start()
    client = OkHttpClient()
}

private fun makeClient() = AbsApiClient(client, ...)
```

After:
```kotlin
private lateinit var server: MockWebServer
private lateinit var httpClient: HttpClient

@Before fun setUp() {
    server = MockWebServer(); server.start()
    val okHttpClient = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
    httpClient = createDefaultHttpClient(okHttpClient)
}

@After fun tearDown() {
    server.shutdown()
    httpClient.close()
}

private fun makeClient() = AbsApiClient(httpClient)
```

For tests that rely on `dispatchers.io` being passed to `OkHttpClassifier.classify`, the dispatcher parameter is now removed from the constructors.

For insecure TLS tests (`InsecureTlsTest`): adapt to use `httpClient.withInsecureTls()`.

- [ ] **Step 1: Update all test files — remove OkHttpClient constructor args, add HttpClient**

Apply the pattern above to each test file. The MockWebServer and MockResponse setup stays unchanged.

- [ ] **Step 2: Run tests**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr
./gradlew :core:network:test 2>&1 | tail -30
```
Expected: all green.

- [ ] **Step 3: Update core:data tests (WebDAV)**

```bash
# Update WebDavAnnotationSyncTargetTest and WebDavAnnotationSyncTargetFactoryTest
# to use HttpClient instead of OkHttpClient
```

- [ ] **Step 4: Run data tests**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr
./gradlew :core:data:test 2>&1 | tail -20
```

- [ ] **Step 5: Commit**

```bash
git add core/network/src/test/ core/data/src/test/
git commit -m "test(network,data): update tests to use Ktor HttpClient with MockWebServer"
```

---

### Task 10: Add OkHttp import guardrail

**Files:**
- Create: `buildSrc/src/main/kotlin/com/riffle/buildlogic/OkHttpImportLint.kt`
- Modify: `buildSrc/src/main/kotlin/com/riffle/buildlogic/RiffleBuildLogicPlugin.kt` (or wherever `checkNoAndroidImports` is registered)
- Modify: `build.gradle.kts` (root) if needed

**OkHttpImportLint.kt:**

```kotlin
package com.riffle.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import org.gradle.api.DefaultTask

abstract class CheckNoOkhttpOutsideNetworkTask : DefaultTask() {
    @TaskAction
    fun check() {
        val allowlist = setOf(
            // OkHttp engine config is allowed only in core:network and core:data DI
            "core/network/src/main/kotlin/com/riffle/core/network/InsecureTls.kt",
            "core/network/src/main/kotlin/com/riffle/core/network/KtorClientFactory.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/di/modules/NetworkModule.kt",
            // Tests may use OkHttpClient + MockWebServer
        )
        val violations = mutableListOf<String>()
        project.rootProject.projectDir.walkTopDown()
            .filter { it.extension == "kt" && !it.path.contains("/test/") && !it.path.contains("buildSrc") }
            .filter { f -> allowlist.none { f.path.endsWith(it) } }
            .forEach { file ->
                if (file.readText().contains("import okhttp3.")) {
                    violations.add(file.path.removePrefix(project.rootProject.projectDir.absolutePath + "/"))
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "checkNoOkhttpOutsideNetwork: okhttp3 imports found outside the allowlist:\n" +
                    violations.joinToString("\n") { "  $it" }
            )
        }
    }
}
```

Wire into `check` task in the root `build.gradle.kts`:
```kotlin
tasks.register<com.riffle.buildlogic.CheckNoOkhttpOutsideNetworkTask>("checkNoOkhttpOutsideNetwork")
tasks.named("check") { dependsOn("checkNoOkhttpOutsideNetwork") }
```

- [ ] **Step 1: Create OkHttpImportLint task**

- [ ] **Step 2: Wire into check**

- [ ] **Step 3: Run guardrail**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr
./gradlew checkNoOkhttpOutsideNetwork 2>&1 | tail -20
```
Expected: PASS (no violations).

- [ ] **Step 4: Run full test suite**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr
./gradlew test 2>&1 | tail -30
```

- [ ] **Step 5: Commit**

```bash
git add buildSrc/
git commit -m "feat(build): add checkNoOkhttpOutsideNetwork guardrail task"
```
