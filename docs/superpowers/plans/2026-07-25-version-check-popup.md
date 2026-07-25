# Version-Check Popup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a startup dialog when a newer GitHub release exists, listing all missed changelogs with "Ignore this version" / "Update" buttons; add an auto-update toggle and full changelog history in Settings.

**Architecture:** Activity-scoped `StartupUpdateViewModel` hoisted at `MainScreen` level reads `AppUpdatePreferencesStore` prefs + calls `AppUpdateRepository.listReleasesSince()` on cold start; dialog renders in `MainScreen` above the `NavHost`. Settings gains an auto-update toggle and a `ChangelogSection` both wired into `SettingsViewModel`.

**Tech Stack:** Kotlin, Hilt, Compose Material3, DataStore Preferences, OkHttp, MockWebServer (tests), `kotlinx-coroutines-test`

## Global Constraints

- Never add `Server` identifiers — use `Source`/`Service` (ADR 0041 / `checkNoServerReferences` CI task).
- All new `core/domain`, `core/net`, `core/sources`, `core/sync`, `core/annotations` files must be pure-Kotlin — no `android.*` or `androidx.*` imports except `androidx.annotation` (`checkNoAndroidImports`).
- Never hard-code string literals that mirror a constant — reference the constant.
- Run `./gradlew test` (not module-specific) to catch pure-JVM test failures.
- `JAVA_HOME` must point to Android Studio's JBR before running Gradle: `export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home`

---

### Task 1: `ReleaseInfo` domain model + `AppUpdatePreferencesStore` interface

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/ReleaseInfo.kt`
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/AppUpdatePreferencesStore.kt`

**Interfaces:**
- Produces: `data class ReleaseInfo(val versionName: String, val versionCode: Int, val changelog: String, val downloadUrl: String, val sizeBytes: Long)`
- Produces: `interface AppUpdatePreferencesStore { val autoUpdateEnabled: Flow<Boolean>; val ignoredVersionCode: Flow<Int>; suspend fun setAutoUpdateEnabled(value: Boolean); suspend fun setIgnoredVersionCode(value: Int) }`

- [ ] **Step 1: Create `ReleaseInfo.kt`**

```kotlin
// core/domain/src/main/kotlin/com/riffle/core/domain/ReleaseInfo.kt
package com.riffle.core.domain

data class ReleaseInfo(
    val versionName: String,
    val versionCode: Int,
    val changelog: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)
```

- [ ] **Step 2: Create `AppUpdatePreferencesStore.kt`**

```kotlin
// core/domain/src/main/kotlin/com/riffle/core/domain/AppUpdatePreferencesStore.kt
package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface AppUpdatePreferencesStore {
    val autoUpdateEnabled: Flow<Boolean>
    val ignoredVersionCode: Flow<Int>
    suspend fun setAutoUpdateEnabled(value: Boolean)
    suspend fun setIgnoredVersionCode(value: Int)
}
```

- [ ] **Step 3: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/ReleaseInfo.kt \
        core/domain/src/main/kotlin/com/riffle/core/domain/AppUpdatePreferencesStore.kt
git commit -m "feat(update): ReleaseInfo model and AppUpdatePreferencesStore interface"
```

---

### Task 2: `GitHubReleaseApi.listReleases()` + `body` field

**Files:**
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/GitHubReleaseApi.kt`
- Modify: `core/network/src/test/kotlin/com/riffle/core/network/GitHubReleaseApiTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces: `suspend fun GitHubReleaseApi.listReleases(repo: String): List<GitHubRelease>` — returns all non-draft non-prerelease releases; `GitHubRelease` gains `body: String`

- [ ] **Step 1: Write failing tests for `listReleases`**

Add to `GitHubReleaseApiTest`:

```kotlin
@Test
fun `listReleases returns all non-draft non-prerelease entries with bodies`() = runTest {
    server.enqueue(
        MockResponse().setBody(
            """
            [
              { "tag_name": "v1.6.0", "draft": false, "prerelease": false, "body": "### What's new\n- Feature A",
                "assets": [{ "name": "riffle-1.6.0.apk", "browser_download_url": "https://x/1.6.0.apk", "size": 5000 }] },
              { "tag_name": "v1.5.0-rc1", "draft": false, "prerelease": true, "body": "RC notes",
                "assets": [{ "name": "riffle-1.5.0-rc1.apk", "browser_download_url": "https://x/rc.apk", "size": 1 }] },
              { "tag_name": "v1.5.0", "draft": false, "prerelease": false, "body": "### Fixes\n- Bug fix",
                "assets": [{ "name": "riffle-1.5.0.apk", "browser_download_url": "https://x/1.5.0.apk", "size": 4200 }] },
              { "tag_name": "v1.4.0-draft", "draft": true, "prerelease": false, "body": "Draft",
                "assets": [] }
            ]
            """.trimIndent()
        )
    )

    val releases = api.listReleases("pkmetski/riffle")

    assertEquals(2, releases.size)
    assertEquals("v1.6.0", releases[0].tagName)
    assertEquals("### What's new\n- Feature A", releases[0].body)
    assertEquals("https://x/1.6.0.apk", releases[0].apkUrl)
    assertEquals(5000L, releases[0].apkSizeBytes)
    assertEquals("v1.5.0", releases[1].tagName)
    assertEquals("### Fixes\n- Bug fix", releases[1].body)
}

@Test
fun `listReleases returns empty list when response is empty`() = runTest {
    server.enqueue(MockResponse().setBody("[]"))
    assertEquals(emptyList<GitHubRelease>(), api.listReleases("pkmetski/riffle"))
}

@Test
fun `listReleases returns empty list on HTTP error`() = runTest {
    server.enqueue(MockResponse().setResponseCode(500).setBody("error"))
    assertEquals(emptyList<GitHubRelease>(), api.listReleases("pkmetski/riffle"))
}

@Test
fun `listReleases includes releases without an apk asset with empty url`() = runTest {
    server.enqueue(
        MockResponse().setBody(
            """
            [
              { "tag_name": "v1.6.0", "draft": false, "prerelease": false, "body": "Notes",
                "assets": [] }
            ]
            """.trimIndent()
        )
    )

    val releases = api.listReleases("pkmetski/riffle")

    assertEquals(1, releases.size)
    assertEquals("", releases[0].apkUrl)
    assertEquals(0L, releases[0].apkSizeBytes)
    assertEquals("Notes", releases[0].body)
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :core:network:test --tests "com.riffle.core.network.GitHubReleaseApiTest" 2>&1 | tail -20
```

Expected: FAIL — `listReleases` not defined.

- [ ] **Step 3: Add `body` to `GitHubRelease` and `ReleaseResponse`, add `listReleases` method**

In `GitHubReleaseApi.kt`:

1. Add `body: String = ""` to `GitHubRelease`:
```kotlin
data class GitHubRelease(
    val tagName: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val body: String = "",
)
```

2. Add `body: String = ""` to `ReleaseResponse`:
```kotlin
@Serializable
private data class ReleaseResponse(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<AssetResponse> = emptyList(),
    val body: String = "",
)
```

3. Add new method `listReleases` after `latestRelease`:
```kotlin
/**
 * Fetches up to 20 non-draft, non-prerelease releases for [repo], newest first. Releases without
 * an APK asset are included (apkUrl = "", apkSizeBytes = 0) so changelogs remain visible even
 * before the build workflow finishes. Returns an empty list on any network or HTTP error.
 */
suspend fun listReleases(repo: String): List<GitHubRelease> = withContext(dispatchers.io) {
    val request = Request.Builder()
        .url("$apiBaseUrl/repos/$repo/releases?per_page=20")
        .header("Accept", "application/vnd.github+json")
        .cacheControl(CacheControl.FORCE_NETWORK)
        .get()
        .build()
    try {
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val raw = response.body?.string() ?: return@use emptyList()
            val parsed = json.decodeFromString<List<ReleaseResponse>>(raw)
            parsed
                .filter { !it.draft && !it.prerelease }
                .map { release ->
                    val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    GitHubRelease(
                        tagName = release.tagName,
                        apkUrl = apk?.downloadUrl ?: "",
                        apkSizeBytes = apk?.size ?: 0L,
                        body = release.body,
                    )
                }
        }
    } catch (e: IOException) {
        emptyList()
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew :core:network:test --tests "com.riffle.core.network.GitHubReleaseApiTest" 2>&1 | tail -20
```

Expected: all tests PASS (existing tests still pass — `body` field defaulted to `""`).

- [ ] **Step 5: Commit**

```bash
git add core/network/src/main/kotlin/com/riffle/core/network/GitHubReleaseApi.kt \
        core/network/src/test/kotlin/com/riffle/core/network/GitHubReleaseApiTest.kt
git commit -m "feat(update): add listReleases to GitHubReleaseApi with body field"
```

---

### Task 3: `AppUpdateRepository.listReleasesSince()` + implementation

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/AppUpdateRepository.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/AppUpdateRepositoryImpl.kt`
- Modify: `core/data/src/test/kotlin/com/riffle/core/data/AppUpdateRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `ReleaseInfo` (Task 1), `GitHubRelease.body` (Task 2), `AppUpdateRepositoryImpl.versionCodeOf()` (existing)
- Produces: `suspend fun AppUpdateRepository.listReleasesSince(sinceVersionCode: Int): List<ReleaseInfo>`

- [ ] **Step 1: Write failing tests**

Add to `AppUpdateRepositoryImplTest.kt` — add a new `FakeGitHubReleaseApi` helper and tests for `listReleasesSince`:

```kotlin
// add at top of the class, after the existing tests:

// Helper to make a GitHubRelease quickly
private fun release(tag: String, body: String = "", apkUrl: String = "https://x/$tag.apk", size: Long = 1000L) =
    GitHubRelease(tagName = tag, apkUrl = apkUrl, apkSizeBytes = size, body = body)

@Test
fun `listReleasesSince returns only releases newer than sinceVersionCode`() = runTest {
    val api = FakeListReleasesApi(
        listOf(
            release("v1.6.0", "Notes 1.6"),
            release("v1.5.0", "Notes 1.5"),
            release("v1.4.0", "Notes 1.4"),
        )
    )
    val repo = AppUpdateRepositoryImpl(
        context = ApplicationProvider.getApplicationContext(),
        releaseApi = api,
        installer = NoOpApkInstaller,
        dispatchers = DefaultDispatcherProvider,
    )

    val result = repo.listReleasesSince(sinceVersionCode = 10500) // installed = 1.5.0

    assertEquals(1, result.size)
    assertEquals("1.6.0", result[0].versionName)
    assertEquals(10600, result[0].versionCode)
    assertEquals("Notes 1.6", result[0].changelog)
    assertEquals("https://x/v1.6.0.apk", result[0].downloadUrl)
}

@Test
fun `listReleasesSince with sinceVersionCode 0 returns all parseable releases`() = runTest {
    val api = FakeListReleasesApi(
        listOf(release("v1.5.0", "Notes 1.5"), release("v1.4.0", "Notes 1.4"))
    )
    val repo = AppUpdateRepositoryImpl(
        context = ApplicationProvider.getApplicationContext(),
        releaseApi = api,
        installer = NoOpApkInstaller,
        dispatchers = DefaultDispatcherProvider,
    )

    val result = repo.listReleasesSince(sinceVersionCode = 0)

    assertEquals(2, result.size)
}

@Test
fun `listReleasesSince skips releases with unparseable tags`() = runTest {
    val api = FakeListReleasesApi(
        listOf(release("nightly"), release("v1.5.0", "Notes"))
    )
    val repo = AppUpdateRepositoryImpl(
        context = ApplicationProvider.getApplicationContext(),
        releaseApi = api,
        installer = NoOpApkInstaller,
        dispatchers = DefaultDispatcherProvider,
    )

    val result = repo.listReleasesSince(sinceVersionCode = 0)

    assertEquals(1, result.size)
    assertEquals("1.5.0", result[0].versionName)
}

@Test
fun `listReleasesSince returns empty list when nothing is newer`() = runTest {
    val api = FakeListReleasesApi(listOf(release("v1.4.0")))
    val repo = AppUpdateRepositoryImpl(
        context = ApplicationProvider.getApplicationContext(),
        releaseApi = api,
        installer = NoOpApkInstaller,
        dispatchers = DefaultDispatcherProvider,
    )

    val result = repo.listReleasesSince(sinceVersionCode = 10500)

    assertTrue(result.isEmpty())
}
```

Also add these helper classes at the bottom of the file (outside the test class):

```kotlin
private class FakeListReleasesApi(private val releases: List<GitHubRelease>) : GitHubReleaseApi(
    httpClient = okhttp3.OkHttpClient(),
    dispatchers = com.riffle.core.domain.DefaultDispatcherProvider,
) {
    override suspend fun listReleases(repo: String): List<GitHubRelease> = releases
}

private object NoOpApkInstaller : com.riffle.core.domain.ApkInstaller {
    override fun install(apk: java.io.File) {}
}
```

Note: `FakeListReleasesApi` extends `GitHubReleaseApi` and overrides only `listReleases`. The existing tests use `latestRelease` via `MockWebServer` and don't need this fake.

**However**, the test imports `ApplicationProvider` from `androidx.test.core.app` — check if the test file already imports it; if not, this test needs to be a JVM unit test avoiding the context. Since `AppUpdateRepositoryImpl.listReleasesSince` doesn't use `context` (only `sweepStaleApks` and `downloadAndInstall` do), refactor as a standalone companion function:

Actually, `AppUpdateRepositoryImpl` takes `context` in its constructor. For JVM tests, use a `Fakes` pattern matching the existing test — which only tests companion functions. Instead, test `listReleasesSince` as a static companion function `listReleasesSince(releases: List<GitHubRelease>, sinceVersionCode: Int): List<ReleaseInfo>`:

Revised test approach:

```kotlin
@Test
fun `listReleasesSince companion returns only releases newer than sinceVersionCode`() {
    val releases = listOf(
        GitHubRelease("v1.6.0", "https://x/1.6.0.apk", 5000L, "Notes 1.6"),
        GitHubRelease("v1.5.0", "https://x/1.5.0.apk", 4200L, "Notes 1.5"),
        GitHubRelease("v1.4.0", "https://x/1.4.0.apk", 3000L, "Notes 1.4"),
    )

    val result = AppUpdateRepositoryImpl.listReleasesSince(releases, sinceVersionCode = 10500)

    assertEquals(1, result.size)
    assertEquals("1.6.0", result[0].versionName)
    assertEquals(10600, result[0].versionCode)
    assertEquals("Notes 1.6", result[0].changelog)
    assertEquals("https://x/1.6.0.apk", result[0].downloadUrl)
    assertEquals(5000L, result[0].sizeBytes)
}

@Test
fun `listReleasesSince companion with sinceVersionCode 0 returns all parseable releases`() {
    val releases = listOf(
        GitHubRelease("v1.5.0", "https://x/1.5.0.apk", 4200L, "Notes 1.5"),
        GitHubRelease("v1.4.0", "https://x/1.4.0.apk", 3000L, "Notes 1.4"),
    )

    val result = AppUpdateRepositoryImpl.listReleasesSince(releases, sinceVersionCode = 0)

    assertEquals(2, result.size)
}

@Test
fun `listReleasesSince companion skips releases with unparseable tags`() {
    val releases = listOf(
        GitHubRelease("nightly", "", 0L, "Notes"),
        GitHubRelease("v1.5.0", "https://x/1.5.0.apk", 4200L, "Notes 1.5"),
    )

    val result = AppUpdateRepositoryImpl.listReleasesSince(releases, sinceVersionCode = 0)

    assertEquals(1, result.size)
    assertEquals("1.5.0", result[0].versionName)
}

@Test
fun `listReleasesSince companion returns empty when nothing is newer`() {
    val releases = listOf(GitHubRelease("v1.4.0", "https://x/1.4.0.apk", 3000L, "Notes"))

    val result = AppUpdateRepositoryImpl.listReleasesSince(releases, sinceVersionCode = 10500)

    assertTrue(result.isEmpty())
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :core:data:test --tests "com.riffle.core.data.AppUpdateRepositoryImplTest" 2>&1 | tail -20
```

Expected: FAIL — `listReleasesSince` companion not defined.

- [ ] **Step 3: Add `listReleasesSince` to the interface**

In `AppUpdateRepository.kt`, add:

```kotlin
/**
 * Returns all non-draft, non-prerelease releases whose version code is strictly greater than
 * [sinceVersionCode], newest first. Pass 0 to retrieve the full recent history.
 * Releases with unrecognisable tags are silently skipped. Returns an empty list on network error.
 */
suspend fun listReleasesSince(sinceVersionCode: Int): List<ReleaseInfo>
```

Also add the import at the top: `import com.riffle.core.domain.ReleaseInfo`

- [ ] **Step 4: Add companion function + implement interface method in `AppUpdateRepositoryImpl`**

In `AppUpdateRepositoryImpl.kt`:

Add import: `import com.riffle.core.domain.ReleaseInfo`

Add to the `companion object`:

```kotlin
fun listReleasesSince(releases: List<GitHubRelease>, sinceVersionCode: Int): List<ReleaseInfo> =
    releases.mapNotNull { release ->
        val versionName = release.tagName.removePrefix("v")
        val versionCode = versionCodeOf(versionName) ?: return@mapNotNull null
        if (versionCode <= sinceVersionCode) return@mapNotNull null
        ReleaseInfo(
            versionName = versionName,
            versionCode = versionCode,
            changelog = release.body,
            downloadUrl = release.apkUrl,
            sizeBytes = release.apkSizeBytes,
        )
    }
```

Add override in the class body:

```kotlin
override suspend fun listReleasesSince(sinceVersionCode: Int): List<ReleaseInfo> =
    listReleasesSince(releaseApi.listReleases(REPO), sinceVersionCode)
```

- [ ] **Step 5: Run tests to confirm they pass**

```bash
./gradlew :core:data:test --tests "com.riffle.core.data.AppUpdateRepositoryImplTest" 2>&1 | tail -20
```

Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/AppUpdateRepository.kt \
        core/data/src/main/kotlin/com/riffle/core/data/AppUpdateRepositoryImpl.kt \
        core/data/src/test/kotlin/com/riffle/core/data/AppUpdateRepositoryImplTest.kt
git commit -m "feat(update): listReleasesSince on AppUpdateRepository"
```

---

### Task 4: `AppUpdatePreferencesStore` factory + DI wiring

**Files:**
- Create: `core/data/src/main/kotlin/com/riffle/core/data/di/AppUpdatePreferencesDataStoreExt.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/modules/PreferencesModule.kt`
- Create factory function in: `core/data/src/main/kotlin/com/riffle/core/data/PreferenceStoreFactories.kt` (append to existing file)

**Interfaces:**
- Consumes: `AppUpdatePreferencesStore` interface (Task 1), `PreferenceStore`, `PrefCodecs` (existing)
- Produces: `AppUpdatePreferencesStore` bound in Hilt graph

- [ ] **Step 1: Create DataStore extension**

```kotlin
// core/data/src/main/kotlin/com/riffle/core/data/di/AppUpdatePreferencesDataStoreExt.kt
package com.riffle.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.appUpdatePreferencesDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "app_update_preferences")
```

- [ ] **Step 2: Add qualifier annotation to `DataModule.kt`**

Add after the last existing qualifier (before the closing brace of the file):

```kotlin
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppUpdatePreferencesDataStore
```

- [ ] **Step 3: Add factory function to `PreferenceStoreFactories.kt`**

Add at the end of `PreferenceStoreFactories.kt`:

```kotlin
fun AppUpdatePreferencesStore(dataStore: DataStore<Preferences>): com.riffle.core.domain.AppUpdatePreferencesStore {
    val autoUpdateStore = preferenceStore(dataStore, PrefCodecs.boolean("auto_update_enabled", default = true))
    val ignoredVersionStore = preferenceStore(dataStore, PrefCodecs.int("ignored_version_code", default = 0))
    return object : com.riffle.core.domain.AppUpdatePreferencesStore {
        override val autoUpdateEnabled = autoUpdateStore.flow
        override val ignoredVersionCode = ignoredVersionStore.flow
        override suspend fun setAutoUpdateEnabled(value: Boolean) = autoUpdateStore.update(value)
        override suspend fun setIgnoredVersionCode(value: Int) = ignoredVersionStore.update(value)
    }
}
```

- [ ] **Step 4: Add `@Provides` to `PreferencesModule.kt`**

In the `companion object` block, add:

```kotlin
@Provides @Singleton @AppUpdatePreferencesDataStore
fun provideAppUpdatePreferencesDataStore(@ApplicationContext c: Context): DataStore<Preferences> =
    c.appUpdatePreferencesDataStore

@Provides
@Singleton
fun provideAppUpdatePreferencesStore(
    @AppUpdatePreferencesDataStore dataStore: DataStore<Preferences>,
): com.riffle.core.domain.AppUpdatePreferencesStore =
    com.riffle.core.data.AppUpdatePreferencesStore(dataStore)
```

Also add the import at the top of `PreferencesModule.kt`:
```kotlin
import com.riffle.core.data.di.AppUpdatePreferencesDataStore
import com.riffle.core.data.di.appUpdatePreferencesDataStore
import com.riffle.core.data.AppUpdatePreferencesStore as createAppUpdatePreferencesStore
```

- [ ] **Step 5: Verify it compiles**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :core:data:compileDebugKotlin 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/di/AppUpdatePreferencesDataStoreExt.kt \
        core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt \
        core/data/src/main/kotlin/com/riffle/core/data/di/modules/PreferencesModule.kt \
        core/data/src/main/kotlin/com/riffle/core/data/PreferenceStoreFactories.kt
git commit -m "feat(update): AppUpdatePreferencesStore factory and DI wiring"
```

---

### Task 5: `StartupUpdateViewModel`

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/update/StartupUpdateViewModel.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/update/StartupUpdateViewModelTest.kt`

**Interfaces:**
- Consumes: `AppUpdateRepository.listReleasesSince()` (Task 3), `AppUpdatePreferencesStore` (Task 4), `ReleaseInfo` (Task 1), `AvailableUpdate`, `UpdateDownloadState` (existing domain)
- Produces:
  - `data class StartupUpdateDialogState(val releases: List<ReleaseInfo>, val update: AvailableUpdate)`
  - `class StartupUpdateViewModel` with:
    - `val dialogState: StateFlow<StartupUpdateDialogState?>`
    - `val downloadState: StateFlow<UpdateDownloadState?>`
    - `fun ignoreVersion(versionCode: Int)`
    - `fun startUpdate(update: AvailableUpdate)`
    - `fun dismissDialog()`

- [ ] **Step 1: Write failing tests**

```kotlin
// app/src/test/kotlin/com/riffle/app/feature/update/StartupUpdateViewModelTest.kt
package com.riffle.app.feature.update

import com.riffle.core.domain.AppUpdatePreferencesStore
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.ReleaseInfo
import com.riffle.core.domain.UpdateCheckResult
import com.riffle.core.domain.UpdateDownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupUpdateViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val currentVersionCode = 10500 // 1.5.0

    private fun releaseInfo(versionName: String, versionCode: Int, changelog: String = "") = ReleaseInfo(
        versionName = versionName,
        versionCode = versionCode,
        changelog = changelog,
        downloadUrl = "https://x/$versionName.apk",
        sizeBytes = 1000L,
    )

    private fun fakePrefs(autoEnabled: Boolean = true, ignored: Int = 0): AppUpdatePreferencesStore {
        val autoFlow = MutableStateFlow(autoEnabled)
        val ignoredFlow = MutableStateFlow(ignored)
        return object : AppUpdatePreferencesStore {
            override val autoUpdateEnabled: Flow<Boolean> = autoFlow
            override val ignoredVersionCode: Flow<Int> = ignoredFlow
            override suspend fun setAutoUpdateEnabled(value: Boolean) { autoFlow.value = value }
            override suspend fun setIgnoredVersionCode(value: Int) { ignoredFlow.value = value }
        }
    }

    private fun fakeRepo(releases: List<ReleaseInfo> = emptyList()): AppUpdateRepository =
        object : AppUpdateRepository {
            override suspend fun checkForUpdate(currentVersionCode: Int): UpdateCheckResult =
                UpdateCheckResult.UpToDate
            override fun downloadAndInstall(update: AvailableUpdate) =
                kotlinx.coroutines.flow.flowOf(UpdateDownloadState.Installing)
            override fun sweepStaleApks() {}
            // sinceVersionCode is ignored — the fake returns whatever was configured.
            // Filtering correctness is tested at the companion-function level in AppUpdateRepositoryImplTest.
            override suspend fun listReleasesSince(sinceVersionCode: Int): List<ReleaseInfo> = releases
        }

    @Test
    fun `dialog is null when auto-update is disabled`() = runTest {
        val vm = StartupUpdateViewModel(
            appUpdateRepository = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            appUpdatePreferencesStore = fakePrefs(autoEnabled = false),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.dialogState.value)
    }

    @Test
    fun `dialog is null when no releases are newer`() = runTest {
        val vm = StartupUpdateViewModel(
            appUpdateRepository = fakeRepo(emptyList()),
            appUpdatePreferencesStore = fakePrefs(),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.dialogState.value)
    }

    @Test
    fun `dialog is null when latest version matches ignoredVersionCode`() = runTest {
        val vm = StartupUpdateViewModel(
            appUpdateRepository = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            appUpdatePreferencesStore = fakePrefs(ignored = 10600),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.dialogState.value)
    }

    @Test
    fun `dialog is shown when a newer unignored version is available`() = runTest {
        val releases = listOf(
            releaseInfo("1.6.0", 10600, "Notes 1.6"),
            releaseInfo("1.5.1", 10501, "Notes 1.5.1"),
        )
        val vm = StartupUpdateViewModel(
            appUpdateRepository = fakeRepo(releases),
            appUpdatePreferencesStore = fakePrefs(),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.dialogState.value
        assertNotNull(state)
        assertEquals(2, state!!.releases.size)
        assertEquals("1.6.0", state.releases[0].versionName)
        assertEquals("https://x/1.6.0.apk", state.update.downloadUrl)
        assertEquals(10600, state.update.versionCode)
    }

    @Test
    fun `ignoreVersion writes pref and clears dialog`() = runTest {
        val prefs = fakePrefs()
        val vm = StartupUpdateViewModel(
            appUpdateRepository = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            appUpdatePreferencesStore = prefs,
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.dialogState.value)

        vm.ignoreVersion(10600)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.dialogState.value)
        assertEquals(10600, prefs.ignoredVersionCode.first())
    }

    @Test
    fun `dismissDialog clears dialog without writing ignoredVersionCode`() = runTest {
        val prefs = fakePrefs()
        val vm = StartupUpdateViewModel(
            appUpdateRepository = fakeRepo(listOf(releaseInfo("1.6.0", 10600))),
            appUpdatePreferencesStore = prefs,
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.dialogState.value)

        vm.dismissDialog()

        assertNull(vm.dialogState.value)
        assertEquals(0, prefs.ignoredVersionCode.first()) // unchanged
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.update.StartupUpdateViewModelTest" 2>&1 | tail -20
```

Expected: FAIL — `StartupUpdateViewModel` not defined.

- [ ] **Step 3: Implement `StartupUpdateViewModel`**

```kotlin
// app/src/main/kotlin/com/riffle/app/feature/update/StartupUpdateViewModel.kt
package com.riffle.app.feature.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.app.BuildConfig
import com.riffle.core.domain.AppUpdatePreferencesStore
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.ReleaseInfo
import com.riffle.core.domain.UpdateDownloadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StartupUpdateDialogState(
    val releases: List<ReleaseInfo>,
    val update: AvailableUpdate,
)

@HiltViewModel
class StartupUpdateViewModel @Inject constructor(
    private val appUpdateRepository: AppUpdateRepository,
    private val appUpdatePreferencesStore: AppUpdatePreferencesStore,
) : ViewModel() {
    // Read directly like SettingsViewModel does — avoids injecting a bare Int into Hilt.
    private val currentVersionCode: Int get() = BuildConfig.VERSION_CODE

    private val _dialogState = MutableStateFlow<StartupUpdateDialogState?>(null)
    val dialogState: StateFlow<StartupUpdateDialogState?> = _dialogState.asStateFlow()

    private val _downloadState = MutableStateFlow<UpdateDownloadState?>(null)
    val downloadState: StateFlow<UpdateDownloadState?> = _downloadState.asStateFlow()

    init {
        viewModelScope.launch {
            val autoEnabled = appUpdatePreferencesStore.autoUpdateEnabled.first()
            if (!autoEnabled) return@launch
            val ignoredCode = appUpdatePreferencesStore.ignoredVersionCode.first()
            val releases = appUpdateRepository.listReleasesSince(currentVersionCode)
            if (releases.isEmpty()) return@launch
            val latest = releases.first()
            if (latest.versionCode == ignoredCode) return@launch
            _dialogState.value = StartupUpdateDialogState(
                releases = releases,
                update = AvailableUpdate(
                    versionName = latest.versionName,
                    versionCode = latest.versionCode,
                    downloadUrl = latest.downloadUrl,
                    sizeBytes = latest.sizeBytes,
                ),
            )
        }
    }

    fun ignoreVersion(versionCode: Int) {
        viewModelScope.launch {
            appUpdatePreferencesStore.setIgnoredVersionCode(versionCode)
            _dialogState.value = null
        }
    }

    fun startUpdate(update: AvailableUpdate) {
        viewModelScope.launch {
            appUpdateRepository.downloadAndInstall(update).collect { step ->
                _downloadState.value = step
            }
        }
    }

    fun dismissDialog() {
        _dialogState.value = null
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.update.StartupUpdateViewModelTest" 2>&1 | tail -20
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/update/StartupUpdateViewModel.kt \
        app/src/test/kotlin/com/riffle/app/feature/update/StartupUpdateViewModelTest.kt
git commit -m "feat(update): StartupUpdateViewModel with ignore/dismiss/download"
```

---

### Task 6: `UpdateAvailableDialog` composable

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/update/UpdateAvailableDialog.kt`

**Interfaces:**
- Consumes: `StartupUpdateDialogState` (Task 5), `UpdateDownloadState` (existing), `AvailableUpdate` (existing)
- Produces: `@Composable fun UpdateAvailableDialog(state, downloadState, onIgnore, onUpdate, onDismiss)`

- [ ] **Step 1: Create `UpdateAvailableDialog.kt`**

```kotlin
// app/src/main/kotlin/com/riffle/app/feature/update/UpdateAvailableDialog.kt
package com.riffle.app.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.UpdateDownloadState

@Composable
internal fun UpdateAvailableDialog(
    state: StartupUpdateDialogState,
    downloadState: UpdateDownloadState?,
    onIgnore: (versionCode: Int) -> Unit,
    onUpdate: (update: AvailableUpdate) -> Unit,
    onDismiss: () -> Unit,
) {
    val isDownloading = downloadState != null
    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isDownloading,
            dismissOnClickOutside = !isDownloading,
        ),
        title = { Text("Update available") },
        text = {
            LazyColumn {
                items(state.releases) { release ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "v${release.versionName}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = release.changelog.ifBlank { "No release notes." },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (state.releases.last() != release) {
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (downloadState) {
                is UpdateDownloadState.Downloading ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            progress = { downloadState.percent / 100f },
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("${downloadState.percent}%", style = MaterialTheme.typography.bodySmall)
                    }
                is UpdateDownloadState.Installing ->
                    Text(
                        "Starting installer…",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                is UpdateDownloadState.Failed ->
                    Button(onClick = { onUpdate(state.update) }) { Text("Retry") }
                null ->
                    Button(onClick = { onUpdate(state.update) }) { Text("Update") }
            }
        },
        dismissButton = {
            if (!isDownloading) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onIgnore(state.update.versionCode) }) {
                        Text("Ignore this version")
                    }
                    TextButton(onClick = onDismiss) { Text("Later") }
                }
            }
        },
    )
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL (no Compose runtime errors at compile time).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/update/UpdateAvailableDialog.kt
git commit -m "feat(update): UpdateAvailableDialog composable"
```

---

### Task 7: Wire startup dialog into `MainScreen.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt`

**Interfaces:**
- Consumes: `StartupUpdateViewModel` (Task 5), `UpdateAvailableDialog` (Task 6)

- [ ] **Step 1: Add imports and VM wiring to `MainScreen.kt`**

At the top of the `MainScreen` composable function body (before `val drawerState`), add:

```kotlin
val startupUpdateVm: StartupUpdateViewModel = hiltViewModel()
val updateDialogState by startupUpdateVm.dialogState.collectAsState()
val updateDownloadState by startupUpdateVm.downloadState.collectAsState()
```

After the `RiffleNavigationDrawer { ... }` block (just before the closing brace of the composable), add:

```kotlin
updateDialogState?.let { dialogState ->
    UpdateAvailableDialog(
        state = dialogState,
        downloadState = updateDownloadState,
        onIgnore = startupUpdateVm::ignoreVersion,
        onUpdate = startupUpdateVm::startUpdate,
        onDismiss = startupUpdateVm::dismissDialog,
    )
}
```

Add these imports at the top of the file:
```kotlin
import com.riffle.app.feature.update.StartupUpdateViewModel
import com.riffle.app.feature.update.UpdateAvailableDialog
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt
git commit -m "feat(update): show startup update dialog from MainScreen"
```

---

### Task 8: Settings — auto-update toggle + `SettingsViewModel` changelog

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsViewModel.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/settings/sections/AppVersionSection.kt`
- Modify: `app/src/test/kotlin/com/riffle/app/feature/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `AppUpdatePreferencesStore` (Task 4), `AppUpdateRepository.listReleasesSince()` (Task 3), `ReleaseInfo` (Task 1)
- Produces: `SettingsViewModel.autoUpdateEnabled: StateFlow<Boolean>`, `SettingsViewModel.setAutoUpdateEnabled()`, `SettingsViewModel.releaseHistory: StateFlow<List<ReleaseInfo>>`

- [ ] **Step 1: Write failing tests for new SettingsViewModel state**

In `SettingsViewModelTest.kt`:

1. The existing `fakeAppUpdateRepo` (line ~215) doesn't have `listReleasesSince` — add it to that existing object:

```kotlin
// Replace the existing fakeAppUpdateRepo with:
private val releaseHistoryResult = mutableListOf<com.riffle.core.domain.ReleaseInfo>()
private val fakeAppUpdateRepo = object : com.riffle.core.domain.AppUpdateRepository {
    override suspend fun checkForUpdate(currentVersionCode: Int) =
        com.riffle.core.domain.UpdateCheckResult.UpToDate
    override fun downloadAndInstall(update: com.riffle.core.domain.AvailableUpdate):
        Flow<com.riffle.core.domain.UpdateDownloadState> = kotlinx.coroutines.flow.emptyFlow()
    override fun sweepStaleApks() = Unit
    override suspend fun listReleasesSince(sinceVersionCode: Int) = releaseHistoryResult.toList()
}
```

2. Add a new `fakeAppUpdatePreferencesStore` field near the other fake stores:

```kotlin
private val autoUpdateEnabledFlow = MutableStateFlow(true)
private val fakeAppUpdatePreferencesStore = object : com.riffle.core.domain.AppUpdatePreferencesStore {
    override val autoUpdateEnabled: Flow<Boolean> = autoUpdateEnabledFlow
    override val ignoredVersionCode: Flow<Int> = MutableStateFlow(0)
    override suspend fun setAutoUpdateEnabled(value: Boolean) { autoUpdateEnabledFlow.value = value }
    override suspend fun setIgnoredVersionCode(value: Int) {}
}
```

3. Update `makeViewModel()` to pass both new params (add them after `appUpdateRepository`):

```kotlin
appUpdateRepository = fakeAppUpdateRepo,
appUpdatePreferencesStore = fakeAppUpdatePreferencesStore,
```

4. Add test cases:

```kotlin
@Test
fun `setAutoUpdateEnabled persists to store`() = runTest {
    val vm = makeViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    vm.setAutoUpdateEnabled(false)
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(vm.autoUpdateEnabled.value)
    assertFalse(autoUpdateEnabledFlow.value)
}

@Test
fun `releaseHistory is populated from listReleasesSince(0)`() = runTest {
    releaseHistoryResult += com.riffle.core.domain.ReleaseInfo("1.6.0", 10600, "Notes", "https://x", 1000L)
    releaseHistoryResult += com.riffle.core.domain.ReleaseInfo("1.5.0", 10500, "Notes", "https://x", 1000L)

    val vm = makeViewModel()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(2, vm.releaseHistory.value.size)
    assertEquals("1.6.0", vm.releaseHistory.value[0].versionName)
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.settings.SettingsViewModelTest" 2>&1 | tail -20
```

Expected: FAIL — `autoUpdateEnabled`, `setAutoUpdateEnabled`, `releaseHistory` not on `SettingsViewModel`.

- [ ] **Step 3: Add to `SettingsViewModel`**

In `SettingsViewModel.kt`:

1. Add `appUpdatePreferencesStore: AppUpdatePreferencesStore` to the constructor (with `@Inject`).
2. Replace the existing `private val appUpdateRepository` with a version also used for `listReleasesSince` (it's already injected).
3. Add the two new `StateFlow` properties and the `releaseHistory` loading in `init {}`:

```kotlin
// In constructor parameters, add:
private val appUpdatePreferencesStore: AppUpdatePreferencesStore,

// New StateFlow for auto-update toggle:
val autoUpdateEnabled: StateFlow<Boolean> = appUpdatePreferencesStore.autoUpdateEnabled
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

fun setAutoUpdateEnabled(value: Boolean) {
    viewModelScope.launch { appUpdatePreferencesStore.setAutoUpdateEnabled(value) }
}

// Release history: fetched once on init
private val _releaseHistory = MutableStateFlow<List<ReleaseInfo>>(emptyList())
val releaseHistory: StateFlow<List<ReleaseInfo>> = _releaseHistory.asStateFlow()
```

In `init {}`, add:

```kotlin
viewModelScope.launch {
    _releaseHistory.value = appUpdateRepository.listReleasesSince(0)
}
```

Add imports:
```kotlin
import com.riffle.core.domain.AppUpdatePreferencesStore
import com.riffle.core.domain.ReleaseInfo
```

- [ ] **Step 4: Update `AppVersionSection.kt` to add the auto-update toggle**

Extend the composable signature:

```kotlin
@Composable
internal fun AppVersionSection(
    installedVersionName: String,
    state: AppUpdateUiState,
    autoUpdateEnabled: Boolean,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onSetAutoUpdateEnabled: (Boolean) -> Unit,
)
```

After the closing `}` of the existing `ListItem`, add:

```kotlin
ListItem(
    headlineContent = { Text("Check for updates on startup") },
    trailingContent = {
        Switch(
            checked = autoUpdateEnabled,
            onCheckedChange = onSetAutoUpdateEnabled,
        )
    },
)
```

Add import: `import androidx.compose.material3.Switch`

- [ ] **Step 5: Update `SettingsScreen.kt` call site**

In `SettingsScreen.kt`, add two lines to the `AppVersionSection(...)` call:

```kotlin
val autoUpdateEnabled by viewModel.autoUpdateEnabled.collectAsState()
// ... (add this line near the other collectAsState calls at the top of the composable)

AppVersionSection(
    installedVersionName = viewModel.installedVersionName,
    state = appUpdateState,
    autoUpdateEnabled = autoUpdateEnabled,
    onCheckForUpdate = viewModel::checkForUpdate,
    onInstallUpdate = viewModel::downloadAndInstallUpdate,
    onSetAutoUpdateEnabled = viewModel::setAutoUpdateEnabled,
)
```

- [ ] **Step 6: Run tests to confirm they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.settings.SettingsViewModelTest" 2>&1 | tail -20
```

Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/settings/SettingsViewModel.kt \
        app/src/main/kotlin/com/riffle/app/feature/settings/sections/AppVersionSection.kt \
        app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt \
        app/src/test/kotlin/com/riffle/app/feature/settings/SettingsViewModelTest.kt
git commit -m "feat(update): auto-update toggle and release history in SettingsViewModel"
```

---

### Task 9: `ChangelogSection` composable + wire into `SettingsScreen`

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/settings/sections/ChangelogSection.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `ReleaseInfo` (Task 1), `SettingsViewModel.releaseHistory` (Task 8)
- Produces: `@Composable internal fun ChangelogSection(releases: List<ReleaseInfo>)`

- [ ] **Step 1: Create `ChangelogSection.kt`**

```kotlin
// app/src/main/kotlin/com/riffle/app/feature/settings/sections/ChangelogSection.kt
package com.riffle.app.feature.settings.sections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.settings.SettingsSectionHeader
import com.riffle.core.domain.ReleaseInfo

@Composable
internal fun ChangelogSection(releases: List<ReleaseInfo>) {
    SettingsSectionHeader("Changelog")
    if (releases.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        return
    }
    releases.forEach { release ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = "v${release.versionName}",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = release.changelog.ifBlank { "No release notes." },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        HorizontalDivider()
    }
}
```

- [ ] **Step 2: Wire into `SettingsScreen.kt`**

In `SettingsScreen.kt`, add a `collectAsState` at the top:

```kotlin
val releaseHistory by viewModel.releaseHistory.collectAsState()
```

After the `AppVersionSection(...)` block and its `HorizontalDivider()`, add:

```kotlin
ChangelogSection(releases = releaseHistory)
HorizontalDivider()
```

Add import:
```kotlin
import com.riffle.app.feature.settings.sections.ChangelogSection
```

- [ ] **Step 3: Verify it compiles**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run all JVM tests**

```bash
./gradlew test 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL — all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/settings/sections/ChangelogSection.kt \
        app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt
git commit -m "feat(update): ChangelogSection in Settings showing full release history"
```

---
