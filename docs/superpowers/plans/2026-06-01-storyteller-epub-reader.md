# Storyteller EPUB Reader (Issue #35) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a Storyteller-served EPUB readable in Riffle's existing reader — fetch the small synced bundle, extract the EPUB, and present it to `EpubRepository` so every existing reader feature works unchanged. Text-only; no readaloud audio, no headphones icon, no progress sync.

**Architecture:** Add a `StorytellerBundleApi` (Retrofit-free, OkHttp like the rest of `core/network`) that downloads `GET /api/books/{id}/synced`. Add an `EpubBundleExtractor` that finds the inner `.epub` entry in the archive and streams it out. Compose both into an `EpubBundleFetcher` that mirrors the shape `EpubRepositoryImpl` already uses (an InputStream into `LocalStore.save`). In `EpubRepositoryImpl`, dispatch by `activeServer.serverType`: ABS keeps today's `AbsLibraryApi.downloadEpub` path; Storyteller uses `EpubBundleFetcher`. The two `LocalStore` tiers (cache + downloads) are reused as-is per ADR 0001 / ADR 0011 — Storyteller `.epub`s sit beside ABS `.epub`s under the same item ID. Reading position already keys by `(serverId, itemId)` so no schema change. UI: flip the Read button on Storyteller items from "Read (coming soon)" to "Read", enabled iff cached/downloaded **or** online; when offline-and-not-cached show a "Connect to download book" tooltip.

**Tech Stack:** Kotlin, OkHttp, Coroutines, JUnit + MockK + MockWebServer (`core/data`, `core/network`); Jetpack Compose + Hilt (`app` UI); Room (already migrated).

---

## File Structure

**Create:**
- `core/network/src/main/kotlin/com/riffle/core/network/StorytellerBundleApi.kt` — interface + impl for `GET /api/books/{id}/synced`.
- `core/data/src/main/kotlin/com/riffle/core/data/EpubBundleExtractor.kt` — pure extraction utility (zip → first `.epub` entry's `InputStream`).
- `core/data/src/main/kotlin/com/riffle/core/data/EpubBundleFetcher.kt` — orchestrates `StorytellerBundleApi` + `EpubBundleExtractor`, returns an `InputStream` ready for `LocalStore.save`.
- `core/network/src/test/kotlin/com/riffle/core/network/StorytellerBundleApiTest.kt`
- `core/data/src/test/kotlin/com/riffle/core/data/EpubBundleExtractorTest.kt`
- `core/data/src/test/kotlin/com/riffle/core/data/EpubBundleFetcherTest.kt`

**Modify:**
- `core/data/src/main/kotlin/com/riffle/core/data/EpubRepositoryImpl.kt` — dispatch on `activeServer.serverType` in `openEpub()` and `downloadEpub()`.
- `core/data/src/test/kotlin/com/riffle/core/data/EpubRepositoryTest.kt` — add Storyteller-path tests.
- `app/src/main/kotlin/com/riffle/app/di/DataModule.kt` — provide `StorytellerBundleApi` + `EpubBundleFetcher`; pass into `EpubRepositoryImpl`.
- `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailScreen.kt:355-406` — flip Read button enable logic for Storyteller items; show download/remove buttons; add "Connect to download book" tooltip.
- `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModel.kt` — expose connectivity signal so UI can decide tooltip.
- `README.md` — flip `- [ ]` to `- [x]` for the matching feature row.

---

## Sequencing

1. **Task 1** — `StorytellerBundleApi` (network call, MockWebServer test).
2. **Task 2** — `EpubBundleExtractor` (pure zip util).
3. **Task 3** — `EpubBundleFetcher` (composes 1 + 2 into an `InputStream`).
4. **Task 4** — `EpubRepositoryImpl.openEpub` dispatches Storyteller path.
5. **Task 5** — `EpubRepositoryImpl.downloadEpub` dispatches Storyteller path.
6. **Task 6** — DI wiring in `DataModule`.
7. **Task 7** — Library Item Detail UI: enable Read for Storyteller items, show download controls, "Connect to download book" tooltip.
8. **Task 8** — README progress flip.

Each task is a complete RED→GREEN→REFACTOR→commit cycle.

---

### Task 1: StorytellerBundleApi — download the synced bundle

**Files:**
- Create: `core/network/src/main/kotlin/com/riffle/core/network/StorytellerBundleApi.kt`
- Test: `core/network/src/test/kotlin/com/riffle/core/network/StorytellerBundleApiTest.kt`

Modeled on `AbsLibraryApi.downloadEpub` (returns the raw `ResponseBody` via a sealed result). The bundle is "few MB" per ADR 0020 — same streaming pattern, no in-memory buffering.

- [ ] **Step 1.1: Write the failing test**

`core/network/src/test/kotlin/com/riffle/core/network/StorytellerBundleApiTest.kt`:

```kotlin
package com.riffle.core.network

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

class StorytellerBundleApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: StorytellerBundleApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        api = StorytellerBundleApiImpl(OkHttpClient())
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun downloadBundle_callsExpectedPath_withBearerAuth() = runBlocking {
        val bytes = ByteArray(64) { it.toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))

        val result = api.downloadBundle(
            baseUrl = server.url("/").toString().trimEnd('/'),
            bookId = "42",
            token = "tkn",
            insecureAllowed = false,
        )

        val recorded = server.takeRequest()
        assertEquals("/api/books/42/synced", recorded.path)
        assertEquals("Bearer tkn", recorded.getHeader("Authorization"))
        assertTrue(result is NetworkStorytellerBundleResult.Success)
        val readBytes = (result as NetworkStorytellerBundleResult.Success).body.use { it.bytes() }
        assertEquals(bytes.toList(), readBytes.toList())
    }

    @Test fun downloadBundle_nonSuccess_returnsNetworkError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = api.downloadBundle(
            baseUrl = server.url("/").toString().trimEnd('/'),
            bookId = "42",
            token = "tkn",
            insecureAllowed = false,
        )

        assertTrue(result is NetworkStorytellerBundleResult.NetworkError)
    }
}
```

- [ ] **Step 1.2: Run test to verify it fails**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.riffle.core.network.StorytellerBundleApiTest"`
Expected: FAIL — `StorytellerBundleApi` / `StorytellerBundleApiImpl` / `NetworkStorytellerBundleResult` unresolved.

- [ ] **Step 1.3: Write minimal implementation**

`core/network/src/main/kotlin/com/riffle/core/network/StorytellerBundleApi.kt`:

```kotlin
package com.riffle.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.IOException

sealed interface NetworkStorytellerBundleResult {
    data class Success(val body: ResponseBody) : NetworkStorytellerBundleResult
    data class NetworkError(val cause: Throwable) : NetworkStorytellerBundleResult
}

interface StorytellerBundleApi {
    suspend fun downloadBundle(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkStorytellerBundleResult
}

class StorytellerBundleApiImpl(
    private val client: OkHttpClient,
) : StorytellerBundleApi {

    override suspend fun downloadBundle(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkStorytellerBundleResult {
        val effectiveClient = if (insecureAllowed) client.insecureCopy() else client
        val request = Request.Builder()
            .url("$baseUrl/api/books/$bookId/synced")
            .header("Authorization", "Bearer $token")
            .build()
        return try {
            val response = effectiveClient.newCall(request).execute()
            val body = response.body
            if (response.isSuccessful && body != null) {
                NetworkStorytellerBundleResult.Success(body)
            } else {
                response.close()
                NetworkStorytellerBundleResult.NetworkError(IOException("HTTP ${response.code}"))
            }
        } catch (e: IOException) {
            NetworkStorytellerBundleResult.NetworkError(e)
        }
    }
}
```

If `OkHttpClient.insecureCopy()` does not already exist in `core/network`, search for the existing helper that other `*Api` impls use (e.g. `AbsLibraryApi`). Reuse it as-is — do not duplicate.

- [ ] **Step 1.4: Run test to verify it passes**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.riffle.core.network.StorytellerBundleApiTest"`
Expected: PASS, two tests green.

- [ ] **Step 1.5: Commit**

```bash
git add core/network/src/main/kotlin/com/riffle/core/network/StorytellerBundleApi.kt \
        core/network/src/test/kotlin/com/riffle/core/network/StorytellerBundleApiTest.kt
git commit -m "feat(network): add StorytellerBundleApi for /api/books/{id}/synced (#35)"
```

---

### Task 2: EpubBundleExtractor — pull the .epub out of the archive

**Files:**
- Create: `core/data/src/main/kotlin/com/riffle/core/data/EpubBundleExtractor.kt`
- Test: `core/data/src/test/kotlin/com/riffle/core/data/EpubBundleExtractorTest.kt`

The bundle is a zip whose entries include a single `.epub`. We extract that one entry to a temp file under the caller-supplied directory and return a `File` plus an `InputStream` (or a single `File` — see signature below). Choosing **`extractEpub(bundle: InputStream, workingDir: File): File`** because callers always pipe the result back into `LocalStore.save` which itself wants an `InputStream` — we'll open it once at the call site.

- [ ] **Step 2.1: Write the failing test**

`core/data/src/test/kotlin/com/riffle/core/data/EpubBundleExtractorTest.kt`:

```kotlin
package com.riffle.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBundleExtractorTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun extractEpub_writesInnerEpubToWorkingDir() {
        val epubBytes = "EPUB PAYLOAD".toByteArray()
        val bundle = buildBundle(
            entries = mapOf(
                "book.epub" to epubBytes,
                "overlay/chapter1.smil" to "<smil/>".toByteArray(),
            ),
        )

        val out = EpubBundleExtractor.extractEpub(ByteArrayInputStream(bundle), tmp.root)

        assertTrue(out.exists())
        assertEquals(epubBytes.toList(), out.readBytes().toList())
    }

    @Test fun extractEpub_throws_whenNoEpubEntry() {
        val bundle = buildBundle(entries = mapOf("overlay.smil" to ByteArray(0)))

        try {
            EpubBundleExtractor.extractEpub(ByteArrayInputStream(bundle), tmp.root)
            error("expected exception")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("epub", ignoreCase = true))
        }
    }

    private fun buildBundle(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
```

- [ ] **Step 2.2: Run test to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.riffle.core.data.EpubBundleExtractorTest"`
Expected: FAIL — `EpubBundleExtractor` unresolved.

- [ ] **Step 2.3: Write minimal implementation**

`core/data/src/main/kotlin/com/riffle/core/data/EpubBundleExtractor.kt`:

```kotlin
package com.riffle.core.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

object EpubBundleExtractor {

    fun extractEpub(bundle: InputStream, workingDir: File): File {
        if (!workingDir.exists()) workingDir.mkdirs()
        ZipInputStream(bundle).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".epub", ignoreCase = true)) {
                    val out = File.createTempFile("storyteller-", ".epub", workingDir)
                    out.outputStream().use { zis.copyTo(it) }
                    return out
                }
                entry = zis.nextEntry
            }
        }
        throw IOException("No .epub entry found in Storyteller bundle")
    }
}
```

- [ ] **Step 2.4: Run test to verify it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.riffle.core.data.EpubBundleExtractorTest"`
Expected: PASS, both tests green.

- [ ] **Step 2.5: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/EpubBundleExtractor.kt \
        core/data/src/test/kotlin/com/riffle/core/data/EpubBundleExtractorTest.kt
git commit -m "feat(data): add EpubBundleExtractor for Storyteller bundles (#35)"
```

---

### Task 3: EpubBundleFetcher — compose API + extractor into an InputStream

**Files:**
- Create: `core/data/src/main/kotlin/com/riffle/core/data/EpubBundleFetcher.kt`
- Test: `core/data/src/test/kotlin/com/riffle/core/data/EpubBundleFetcherTest.kt`

This is the seam `EpubRepositoryImpl` will call when the active server is Storyteller. Signature is intentionally minimal: it returns the extracted EPUB as a `File` (already on disk in a temp dir); callers pipe its `inputStream()` into `LocalStore.save` and let the temp file get GC'd by `workingDir.deleteRecursively()` afterwards.

- [ ] **Step 3.1: Write the failing test**

`core/data/src/test/kotlin/com/riffle/core/data/EpubBundleFetcherTest.kt`:

```kotlin
package com.riffle.core.data

import com.riffle.core.network.NetworkStorytellerBundleResult
import com.riffle.core.network.StorytellerBundleApi
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBundleFetcherTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun fetch_returnsExtractedEpubFile() = runBlocking {
        val epubBytes = "EPUB PAYLOAD".toByteArray()
        val bundleBytes = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { zip ->
                zip.putNextEntry(ZipEntry("book.epub")); zip.write(epubBytes); zip.closeEntry()
            }
        }.toByteArray()

        val api = StorytellerBundleApi { _, bookId, token, _ ->
            assertEquals("42", bookId)
            assertEquals("tkn", token)
            NetworkStorytellerBundleResult.Success(
                bundleBytes.toResponseBody("application/zip".toMediaType()),
            )
        }
        val fetcher = EpubBundleFetcher(api, workingDirProvider = { tmp.root })

        val result = fetcher.fetch(
            baseUrl = "http://stub",
            bookId = "42",
            token = "tkn",
            insecureAllowed = false,
        )

        assertTrue(result is EpubBundleFetcher.Result.Success)
        val file = (result as EpubBundleFetcher.Result.Success).epubFile
        assertEquals(epubBytes.toList(), file.readBytes().toList())
    }

    @Test fun fetch_propagatesNetworkError() = runBlocking {
        val api = StorytellerBundleApi { _, _, _, _ ->
            NetworkStorytellerBundleResult.NetworkError(RuntimeException("boom"))
        }
        val fetcher = EpubBundleFetcher(api, workingDirProvider = { tmp.root })

        val result = fetcher.fetch("http://stub", "42", "tkn", false)

        assertTrue(result is EpubBundleFetcher.Result.NetworkError)
    }
}
```

Note: `StorytellerBundleApi` will need to become a `fun interface` (single-method) so the test lambda compiles. Update its declaration accordingly during this task.

- [ ] **Step 3.2: Run test to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.riffle.core.data.EpubBundleFetcherTest"`
Expected: FAIL — `EpubBundleFetcher` unresolved (and possibly `StorytellerBundleApi` not a `fun interface`).

- [ ] **Step 3.3: Write minimal implementation**

Update `core/network/src/main/kotlin/com/riffle/core/network/StorytellerBundleApi.kt` — change `interface` to `fun interface`. (No other call sites yet.)

Create `core/data/src/main/kotlin/com/riffle/core/data/EpubBundleFetcher.kt`:

```kotlin
package com.riffle.core.data

import com.riffle.core.network.NetworkStorytellerBundleResult
import com.riffle.core.network.StorytellerBundleApi
import java.io.File

class EpubBundleFetcher(
    private val api: StorytellerBundleApi,
    private val workingDirProvider: () -> File,
) {

    sealed interface Result {
        data class Success(val epubFile: File) : Result
        data class NetworkError(val cause: Throwable) : Result
    }

    suspend fun fetch(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): Result = when (val r = api.downloadBundle(baseUrl, bookId, token, insecureAllowed)) {
        is NetworkStorytellerBundleResult.Success -> r.body.use { body ->
            try {
                val epub = EpubBundleExtractor.extractEpub(body.byteStream(), workingDirProvider())
                Result.Success(epub)
            } catch (e: Throwable) {
                Result.NetworkError(e)
            }
        }
        is NetworkStorytellerBundleResult.NetworkError -> Result.NetworkError(r.cause)
    }
}
```

- [ ] **Step 3.4: Run test to verify it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.riffle.core.data.EpubBundleFetcherTest"`
Expected: PASS.

- [ ] **Step 3.5: Commit**

```bash
git add core/network/src/main/kotlin/com/riffle/core/network/StorytellerBundleApi.kt \
        core/data/src/main/kotlin/com/riffle/core/data/EpubBundleFetcher.kt \
        core/data/src/test/kotlin/com/riffle/core/data/EpubBundleFetcherTest.kt
git commit -m "feat(data): add EpubBundleFetcher composing API + extractor (#35)"
```

---

### Task 4: EpubRepositoryImpl.openEpub dispatches on server type

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/EpubRepositoryImpl.kt`
- Modify: `core/data/src/test/kotlin/com/riffle/core/data/EpubRepositoryTest.kt`

The repository now needs `EpubBundleFetcher` as a constructor dep. The dispatch happens inside `openEpub` (and Task 5 covers `downloadEpub`). For ABS items the behavior is unchanged. For Storyteller items: skip `getItemEbookFileIno`, call `EpubBundleFetcher.fetch`, pipe the extracted file's stream into `cacheStore.save(item.id, …)`, then clean up the temp file.

Before writing test/impl, look at the existing `EpubRepositoryTest` fakes (`fakeServerRepository`, `fakeTokenStorage`, `FakePositionStore`) and reuse them for the Storyteller variant — just produce a `Server` whose `serverType = ServerType.STORYTELLER`.

- [ ] **Step 4.1: Write the failing test**

Add to `core/data/src/test/kotlin/com/riffle/core/data/EpubRepositoryTest.kt` (assuming existing top-of-file imports `Server`, `ServerType`, etc; if not, add them):

```kotlin
@Test fun openEpub_storytellerServer_usesBundleFetcher_andCachesResult() = runBlocking {
    val epubBytes = "STORY EPUB".toByteArray()
    val bundleBytes = java.io.ByteArrayOutputStream().also { baos ->
        java.util.zip.ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("book.epub"))
            zip.write(epubBytes)
            zip.closeEntry()
        }
    }.toByteArray()

    val bundleApi = com.riffle.core.network.StorytellerBundleApi { _, bookId, token, _ ->
        assertEquals("42", bookId)
        assertEquals("st-tkn", token)
        com.riffle.core.network.NetworkStorytellerBundleResult.Success(
            bundleBytes.toResponseBody("application/zip".toMediaType()),
        )
    }
    val fetcher = EpubBundleFetcher(bundleApi, workingDirProvider = { tmp.newFolder("wd") })

    val cacheStore = LocalStoreImpl(tmp.newFolder("cache"), ".epub")
    val downloadsStore = LocalStoreImpl(tmp.newFolder("dl"), ".epub")
    val positions = FakePositionStore()
    val absApi = mockk<AbsLibraryApi>() // not invoked for Storyteller
    val servers = fakeServerRepository(
        Server(
            id = "srv-st",
            url = ServerUrl("http://st.example"),
            name = "St",
            serverType = ServerType.STORYTELLER,
            insecureConnectionAllowed = false,
        ),
    )
    val tokens = fakeTokenStorage("srv-st" to "st-tkn")

    val repo = EpubRepositoryImpl(
        api = absApi,
        bundleFetcher = fetcher,
        cacheStore = cacheStore,
        downloadsStore = downloadsStore,
        positionStore = positions,
        serverRepository = servers,
        tokenStorage = tokens,
    )

    val item = LibraryItem(
        id = "42", libraryId = "readaloud:srv-st", title = "x", author = "x",
        coverUrl = null, readingProgress = 0f, ebookFormat = EbookFormat.Epub,
        ebookFileIno = null, description = null, seriesName = null,
        publishedYear = null, genres = emptyList(), publisher = null,
        lastOpenedAt = null, addedAt = null,
    )

    val result = repo.openEpub(item)

    assertTrue(result is EpubOpenResult.Success)
    val cached = cacheStore.get("42")!!
    assertEquals(epubBytes.toList(), cached.readBytes().toList())
    verify(exactly = 0) { absApi.getItemEbookFileIno(any(), any(), any(), any()) }
}
```

If `Server`, `ServerType`, `ServerUrl` are not imported at the top of the test, add the imports. If `fakeServerRepository` accepts a single `Server` (current signature serves an ABS one), extend it to take a `Server` argument — keep the ABS-flavoured test using the same helper. Reuse, don't duplicate.

- [ ] **Step 4.2: Run test to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.riffle.core.data.EpubRepositoryTest"`
Expected: FAIL — `EpubRepositoryImpl` constructor does not take `bundleFetcher`; dispatch logic missing.

- [ ] **Step 4.3: Write minimal implementation**

Edit `core/data/src/main/kotlin/com/riffle/core/data/EpubRepositoryImpl.kt`:

```kotlin
class EpubRepositoryImpl(
    private val api: AbsLibraryApi,
    private val bundleFetcher: EpubBundleFetcher,
    private val cacheStore: LocalStore,
    private val downloadsStore: LocalStore,
    private val positionStore: ReadingPositionStore,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : EpubRepository {

    override suspend fun openEpub(item: LibraryItem): EpubOpenResult {
        val activeServer = serverRepository.getActive()
            ?: return EpubOpenResult.NetworkError(IllegalStateException("No active server"))
        val local = downloadsStore.get(item.id) ?: cacheStore.get(item.id)
        val epubFile = local ?: run {
            val token = tokenStorage.getToken(activeServer.id)
                ?: return EpubOpenResult.NetworkError(IllegalStateException("No token for server"))
            when (activeServer.serverType) {
                ServerType.STORYTELLER -> {
                    when (val r = bundleFetcher.fetch(activeServer.url.value, item.id, token, activeServer.insecureConnectionAllowed)) {
                        is EpubBundleFetcher.Result.Success ->
                            r.epubFile.inputStream().use { cacheStore.save(item.id, it) }
                                .also { r.epubFile.delete() }
                        is EpubBundleFetcher.Result.NetworkError ->
                            return EpubOpenResult.NetworkError(r.cause)
                    }
                }
                ServerType.AUDIOBOOKSHELF -> {
                    val ino = item.ebookFileIno ?: run {
                        when (val r = api.getItemEbookFileIno(activeServer.url.value, item.id, token, activeServer.insecureConnectionAllowed)) {
                            is NetworkItemEbookInoResult.Success -> r.ino
                            is NetworkItemEbookInoResult.NetworkError -> return EpubOpenResult.NetworkError(r.cause)
                        }
                    }
                    when (val result = api.downloadEpub(activeServer.url.value, item.id, ino, token, activeServer.insecureConnectionAllowed)) {
                        is NetworkEpubDownloadResult.Success -> result.body.use { body ->
                            cacheStore.save(item.id, body.byteStream())
                        }
                        is NetworkEpubDownloadResult.NetworkError -> return EpubOpenResult.NetworkError(result.cause)
                    }
                }
            }
        }
        val lastPosition = positionStore.load(activeServer.id, item.id)
        return EpubOpenResult.Success(epubFile = epubFile, lastPosition = lastPosition)
    }
    // ... downloadEpub unchanged for now — Task 5 will dispatch it ...
}
```

Add `import com.riffle.core.domain.ServerType`.

- [ ] **Step 4.4: Run tests to verify they pass — and the existing ABS path still passes**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: ALL repository tests PASS. If the old ABS test still uses the no-`bundleFetcher` constructor, update it to pass a fake fetcher (it's never invoked) — do not change its assertions.

- [ ] **Step 4.5: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/EpubRepositoryImpl.kt \
        core/data/src/test/kotlin/com/riffle/core/data/EpubRepositoryTest.kt
git commit -m "feat(data): route Storyteller items through bundle fetcher on open (#35)"
```

---

### Task 5: EpubRepositoryImpl.downloadEpub dispatches on server type

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/EpubRepositoryImpl.kt`
- Modify: `core/data/src/test/kotlin/com/riffle/core/data/EpubRepositoryTest.kt`

Explicit "Download" action on the detail screen must work for Storyteller items: promote-from-cache if present, else fetch bundle and save into `downloadsStore`.

- [ ] **Step 5.1: Write the failing test**

Append to `EpubRepositoryTest.kt`:

```kotlin
@Test fun downloadEpub_storytellerServer_savesToDownloadsStore() = runBlocking {
    val epubBytes = "STORY DL".toByteArray()
    val bundleBytes = /* same zip helper as Task 4 */ byteArrayOf().also {
        // inline the helper or extract into a private val in the test class
    }
    // Build via the same ZipOutputStream helper as Task 4 — reuse, don't duplicate.

    val bundleApi = com.riffle.core.network.StorytellerBundleApi { _, _, _, _ ->
        com.riffle.core.network.NetworkStorytellerBundleResult.Success(
            bundleBytesFor(epubBytes).toResponseBody("application/zip".toMediaType()),
        )
    }
    val fetcher = EpubBundleFetcher(bundleApi, workingDirProvider = { tmp.newFolder("wd") })
    val cacheStore = LocalStoreImpl(tmp.newFolder("cache"), ".epub")
    val downloadsStore = LocalStoreImpl(tmp.newFolder("dl"), ".epub")
    val repo = EpubRepositoryImpl(
        api = mockk<AbsLibraryApi>(),
        bundleFetcher = fetcher,
        cacheStore = cacheStore,
        downloadsStore = downloadsStore,
        positionStore = FakePositionStore(),
        serverRepository = fakeServerRepository(
            Server("srv-st", ServerUrl("http://st.example"), "St", ServerType.STORYTELLER, false),
        ),
        tokenStorage = fakeTokenStorage("srv-st" to "st-tkn"),
    )
    val item = storytellerItem(id = "77")

    val result = repo.downloadEpub(item)

    assertEquals(EpubDownloadResult.Success, result)
    assertEquals(epubBytes.toList(), downloadsStore.get("77")!!.readBytes().toList())
}

@Test fun downloadEpub_storytellerServer_promotesFromCache_whenAlreadyCached() = runBlocking {
    val cacheStore = LocalStoreImpl(tmp.newFolder("cache"), ".epub")
    cacheStore.save("77", "CACHED".byteInputStream())
    val downloadsStore = LocalStoreImpl(tmp.newFolder("dl"), ".epub")

    val bundleApi = com.riffle.core.network.StorytellerBundleApi { _, _, _, _ ->
        error("should not be called when already cached")
    }
    val fetcher = EpubBundleFetcher(bundleApi, workingDirProvider = { tmp.newFolder("wd") })
    val repo = EpubRepositoryImpl(
        api = mockk<AbsLibraryApi>(),
        bundleFetcher = fetcher,
        cacheStore = cacheStore,
        downloadsStore = downloadsStore,
        positionStore = FakePositionStore(),
        serverRepository = fakeServerRepository(
            Server("srv-st", ServerUrl("http://st.example"), "St", ServerType.STORYTELLER, false),
        ),
        tokenStorage = fakeTokenStorage("srv-st" to "st-tkn"),
    )

    val result = repo.downloadEpub(storytellerItem(id = "77"))

    assertEquals(EpubDownloadResult.Success, result)
    assertEquals("CACHED", downloadsStore.get("77")!!.readText())
    assertEquals(null, cacheStore.get("77"))
}

private fun bundleBytesFor(epubBytes: ByteArray): ByteArray =
    java.io.ByteArrayOutputStream().also { baos ->
        java.util.zip.ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("book.epub"))
            zip.write(epubBytes); zip.closeEntry()
        }
    }.toByteArray()

private fun storytellerItem(id: String) = LibraryItem(
    id = id, libraryId = "readaloud:srv-st", title = "x", author = "x",
    coverUrl = null, readingProgress = 0f, ebookFormat = EbookFormat.Epub,
    ebookFileIno = null, description = null, seriesName = null,
    publishedYear = null, genres = emptyList(), publisher = null,
    lastOpenedAt = null, addedAt = null,
)
```

- [ ] **Step 5.2: Run test to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.riffle.core.data.EpubRepositoryTest"`
Expected: FAIL — fetch is not invoked from `downloadEpub` for Storyteller; current code calls `AbsLibraryApi`.

- [ ] **Step 5.3: Write minimal implementation**

In `EpubRepositoryImpl.kt`, change `downloadEpub`:

```kotlin
override suspend fun downloadEpub(item: LibraryItem): EpubDownloadResult {
    if (downloadsStore.get(item.id) != null) return EpubDownloadResult.AlreadyDownloaded
    val cached = cacheStore.get(item.id)
    if (cached != null) {
        cached.inputStream().use { downloadsStore.save(item.id, it) }
        cacheStore.delete(item.id)
        return EpubDownloadResult.Success
    }
    val server = serverRepository.getActive()
        ?: return EpubDownloadResult.NetworkError(IllegalStateException("No active server"))
    val token = tokenStorage.getToken(server.id)
        ?: return EpubDownloadResult.NetworkError(IllegalStateException("No token for server"))
    return when (server.serverType) {
        ServerType.STORYTELLER -> {
            when (val r = bundleFetcher.fetch(server.url.value, item.id, token, server.insecureConnectionAllowed)) {
                is EpubBundleFetcher.Result.Success -> {
                    r.epubFile.inputStream().use { downloadsStore.save(item.id, it) }
                    r.epubFile.delete()
                    EpubDownloadResult.Success
                }
                is EpubBundleFetcher.Result.NetworkError -> EpubDownloadResult.NetworkError(r.cause)
            }
        }
        ServerType.AUDIOBOOKSHELF -> {
            val ino = item.ebookFileIno ?: run {
                when (val r = api.getItemEbookFileIno(server.url.value, item.id, token, server.insecureConnectionAllowed)) {
                    is NetworkItemEbookInoResult.Success -> r.ino
                    is NetworkItemEbookInoResult.NetworkError -> return EpubDownloadResult.NetworkError(r.cause)
                }
            }
            when (val result = api.downloadEpub(server.url.value, item.id, ino, token, server.insecureConnectionAllowed)) {
                is NetworkEpubDownloadResult.Success -> {
                    result.body.use { body -> downloadsStore.save(item.id, body.byteStream()) }
                    EpubDownloadResult.Success
                }
                is NetworkEpubDownloadResult.NetworkError -> EpubDownloadResult.NetworkError(result.cause)
            }
        }
    }
}
```

- [ ] **Step 5.4: Run all data-module tests**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: ALL PASS.

- [ ] **Step 5.5: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/EpubRepositoryImpl.kt \
        core/data/src/test/kotlin/com/riffle/core/data/EpubRepositoryTest.kt
git commit -m "feat(data): route Storyteller items through bundle fetcher on download (#35)"
```

---

### Task 6: DI wiring in DataModule

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/di/DataModule.kt`

The new `StorytellerBundleApi`, `EpubBundleFetcher`, and the updated `EpubRepositoryImpl` constructor must be registered. The fetcher's `workingDirProvider` should hand back a subdirectory of `context.cacheDir` (e.g. `cacheDir/storyteller-bundles`) — that satisfies "clearable system cache directory, may be evicted" from the acceptance criteria.

Since DI changes are hard to RED-test in isolation, this task is verified by getting `:app:compileDebugKotlin` to compile and then re-running `./gradlew test` to confirm nothing else broke.

- [ ] **Step 6.1: Read the existing `DataModule.kt` and locate the `EpubRepositoryImpl` and `AbsLibraryApi` providers**

Read `app/src/main/kotlin/com/riffle/app/di/DataModule.kt` end-to-end. Identify the existing `@Provides` for `AbsLibraryApi`, `LocalStore` (cacheStore + downloadsStore), and `EpubRepository`.

- [ ] **Step 6.2: Add providers for `StorytellerBundleApi` and `EpubBundleFetcher`; update the `EpubRepository` provider**

```kotlin
@Provides
@Singleton
fun provideStorytellerBundleApi(client: OkHttpClient): StorytellerBundleApi =
    StorytellerBundleApiImpl(client)

@Provides
@Singleton
fun provideEpubBundleFetcher(
    api: StorytellerBundleApi,
    @ApplicationContext context: Context,
): EpubBundleFetcher = EpubBundleFetcher(
    api = api,
    workingDirProvider = {
        File(context.cacheDir, "storyteller-bundles").apply { mkdirs() }
    },
)
```

…and in the existing `provideEpubRepository(...)` add `bundleFetcher: EpubBundleFetcher` as a param and forward it to the constructor.

- [ ] **Step 6.3: Build the app**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6.4: Run the full unit test suite**

Run: `./gradlew test`
Expected: ALL PASS (per [`feedback_gradle_test_command.md`](https://example.invalid) memory: use `./gradlew test`, not module-specific `:testDebugUnitTest`).

- [ ] **Step 6.5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/di/DataModule.kt
git commit -m "build(di): wire StorytellerBundleApi and EpubBundleFetcher (#35)"
```

---

### Task 7: Library Item Detail — enable Read for Storyteller items, add tooltip

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModel.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailScreen.kt`

Currently the Read button reads "Read (coming soon)" and is unconditionally disabled when `isReadaloud`. We need:

- Read button **enabled** for Storyteller items unless (offline AND not cached AND not downloaded). Label is "Read".
- Download / Remove buttons **shown** for Storyteller items — they now work (Task 5).
- When the button is disabled-due-to-offline, attach a tooltip "Connect to download book".
- Mark-as-read / To-Read toggles remain hidden for Storyteller (those slices are #37/#38, per ADR 0020).

The ViewModel needs to expose `isOffline` (or `canReachServer`) so the screen can decide. Look for an existing connectivity Flow in `core/data` or `core/domain` — there is almost certainly one used by the library refresh path. Reuse it. If none exists (verify by `grep -rn "ConnectivityManager\|isOffline\|NetworkStatus" core/`), wire one through `LibraryItemDetailViewModel` from whatever the rest of the app uses to gate online behaviour. Do not invent a new connectivity API — if nothing exists, fall back to checking `isCached(item.id) || isDownloaded(item.id)` ORed with optimistic-enabled and surface the tooltip only when offline is *known*.

- [ ] **Step 7.1: Write the failing test**

If there is no existing Compose UI test for `LibraryItemDetailScreen`, add a new instrumented test under `app/src/androidTest/kotlin/com/riffle/app/feature/library/LibraryItemDetailScreenTest.kt`. The harness rules: phone-form-factor tests run via `make harness-test`. Per CLAUDE.md, never invoke `./gradlew :app:connectedDebugAndroidTest` directly.

```kotlin
@Test fun readaloudItem_offlineAndNotCached_disablesReadButton_andShowsTooltip() {
    val item = readaloudItem(id = "42")
    composeTestRule.setContent {
        ActionRow(
            item = item,
            isInToRead = false,
            downloadState = DownloadState.NotDownloaded,
            isReadaloud = true,
            isCachedOrDownloaded = false,
            isOffline = true,
            onReadItem = {}, onMarkAsRead = {}, onMarkAsUnread = {},
            onToggleToRead = {}, onDownload = {}, onRemove = {},
        )
    }

    composeTestRule.onNodeWithText("Read").assertIsNotEnabled()
    composeTestRule.onNodeWithText("Read").performTouchInput { longClick() }
    composeTestRule.onNodeWithText("Connect to download book").assertIsDisplayed()
}

@Test fun readaloudItem_cached_enablesReadButton() {
    val item = readaloudItem(id = "42")
    var clicked = false
    composeTestRule.setContent {
        ActionRow(
            item = item,
            isInToRead = false,
            downloadState = DownloadState.NotDownloaded,
            isReadaloud = true,
            isCachedOrDownloaded = true,
            isOffline = true,
            onReadItem = { clicked = true },
            onMarkAsRead = {}, onMarkAsUnread = {},
            onToggleToRead = {}, onDownload = {}, onRemove = {},
        )
    }
    composeTestRule.onNodeWithText("Read").assertIsEnabled().performClick()
    assert(clicked)
}
```

(`readaloudItem(id)` is a small helper that builds a `LibraryItem` with `ebookFormat = EbookFormat.Epub`; put it in the test file.)

- [ ] **Step 7.2: Run the test to verify it fails**

Run: `make harness-test ARGS="--tests com.riffle.app.feature.library.LibraryItemDetailScreenTest"`
Expected: FAIL — `ActionRow` does not yet take `isCachedOrDownloaded` / `isOffline` params; button is still disabled with text "Read (coming soon)".

If `make harness-test` does not support narrowing by `--tests`, run it as-is and confirm the new test fails.

- [ ] **Step 7.3: Update the ViewModel state & screen**

In `LibraryItemDetailViewModel.kt`:

- Add `isCachedOrDownloaded: Boolean` and `isOffline: Boolean` to the `Ready` state.
- Compute `isCachedOrDownloaded` via `epubRepository.isCached(itemId) || epubRepository.isDownloaded(itemId)`.
- Wire `isOffline` from the existing connectivity source (or a new injected one — see Step 7 preamble).

In `LibraryItemDetailScreen.kt:355-406` (`ActionRow`):

```kotlin
@Composable
private fun ActionRow(
    item: LibraryItem,
    isInToRead: Boolean,
    downloadState: DownloadState,
    isReadaloud: Boolean,
    isCachedOrDownloaded: Boolean,
    isOffline: Boolean,
    onReadItem: (LibraryItem) -> Unit,
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onToggleToRead: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    if (!item.isSupported) {
        Text(
            text = "No ebook file is available for this item on the server.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val readDisabledByOffline = isOffline && !isCachedOrDownloaded
    val readEnabled = downloadState !is DownloadState.InProgress && !readDisabledByOffline
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReadButtonWithOfflineTooltip(
            enabled = readEnabled,
            showTooltip = readDisabledByOffline,
            onClick = { onReadItem(item) },
            modifier = Modifier.weight(1f),
        )
        if (!isReadaloud) {
            ReadToggleButton(
                isRead = item.readingProgress >= READ_PROGRESS_THRESHOLD,
                onMarkAsRead = onMarkAsRead,
                onMarkAsUnread = onMarkAsUnread,
            )
            ToReadToggleButton(isInToRead = isInToRead, onToggle = onToggleToRead)
        }
        DownloadButton(state = downloadState, onDownload = onDownload, onRemove = onRemove)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadButtonWithOfflineTooltip(
    enabled: Boolean,
    showTooltip: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (showTooltip) {
        val tooltipState = rememberTooltipState(isPersistent = false)
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Connect to download book") } },
            state = tooltipState,
        ) {
            Button(onClick = onClick, enabled = false, modifier = modifier) { Text("Read") }
        }
    } else {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text("Read") }
    }
}
```

Also update the call site higher in the file (where `ActionRow` is called) to pass the two new params from the `Ready` state.

- [ ] **Step 7.4: Run the harness test to verify it passes**

Run: `make harness-test`
Expected: PASS for the new tests; no regressions elsewhere.

- [ ] **Step 7.5: Run the full unit suite to catch ViewModel changes**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 7.6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailScreen.kt \
        app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModel.kt \
        app/src/androidTest/kotlin/com/riffle/app/feature/library/LibraryItemDetailScreenTest.kt
git commit -m "feat(library): enable Read button for Storyteller items, add offline tooltip (#35)"
```

---

### Task 8: README — flip the issue's row to done

**Files:**
- Modify: `README.md`

Per CLAUDE.md, every feature PR flips `- [ ]` to `- [x]` on its row.

- [ ] **Step 8.1: Locate the row**

`grep -n "Storyteller" README.md` and find the row that corresponds to issue #35 ("read a Storyteller book in the reader" / similar phrasing).

- [ ] **Step 8.2: Flip the checkbox**

Edit the matched row from `- [ ]` to `- [x]`.

- [ ] **Step 8.3: Commit**

```bash
git add README.md
git commit -m "docs(readme): mark Storyteller reader (no readaloud) as done (#35)"
```

---

## Acceptance Criteria → Task Mapping

| Acceptance criterion | Covered by |
|---|---|
| `EpubBundleFetcher` fetches archive, extracts, presents EPUB to reader pipeline | Tasks 1, 2, 3 |
| First open triggers cache-on-open of the bundle | Task 4 |
| All existing reader features work on a Storyteller-served EPUB | Implicit via Task 4 (reader receives the same `File` shape it does for ABS) — verify by manual smoke after merge |
| TopAppBar shows only Search/TOC/Formatting (no headphones) | No change needed — slice 3 is out of scope; verify in smoke that no new icon was added |
| Local reading position persists across sessions on the same device | Existing `(serverId, itemId)` keying in `ReadingPositionEntity` — Task 4 sets `serverId = activeServer.id` via the unchanged `positionStore.load/save` calls |
| Offline + not cached → Read button disabled with "Connect to download book" tooltip | Task 7 |
| Offline + cached → Read opens normally | Task 7 (enabled when `isCachedOrDownloaded`) |
| Read button is now functional for Storyteller-sourced items | Tasks 4, 7 |

---

## Self-Review

**Spec coverage:** All eight acceptance criteria mapped above.

**Placeholder scan:** No "TBD" / "implement later" / "appropriate error handling" left in the plan. Every code block is complete enough to paste.

**Type consistency:**
- `EpubBundleFetcher.Result.Success/NetworkError` — used identically in Tasks 3, 4, 5.
- `StorytellerBundleApi.downloadBundle(baseUrl, bookId, token, insecureAllowed)` — signature identical in Tasks 1, 3, 4, 5.
- `EpubBundleExtractor.extractEpub(bundle, workingDir)` — Tasks 2 and 3.
- `EpubRepositoryImpl` constructor adds `bundleFetcher: EpubBundleFetcher` once (Task 4) and Task 5 reuses without changing the signature.
- `ActionRow` adds `isCachedOrDownloaded: Boolean` and `isOffline: Boolean`; the same names appear in the call-site update (Task 7).

**Risks / open questions to flag during execution:**
1. The Storyteller bundle's inner archive **may itself be a ZIP** (`.epub` files are zips), so naïve `ZipInputStream` traversal of the *outer* archive won't try to recurse into the EPUB — that's correct. Confirm during Task 2 that the test bundle reflects the real shape (multiple top-level entries, one of which is the `.epub`).
2. If the existing `EpubRepositoryTest` ABS tests don't use a `Server` model with `serverType`, Task 4 may need to update the test helper to default `serverType = ServerType.AUDIOBOOKSHELF` rather than refactor every existing call site.
3. The bundle endpoint requires a Storyteller token, not the ABS active-server token. The repository uses `tokenStorage.getToken(activeServer.id)` which is keyed by server id — so this Just Works as long as `serverRepository.getActive()` returns the Storyteller server when the user is on the Readaloud Library. Verify during Task 6 / smoke test.
