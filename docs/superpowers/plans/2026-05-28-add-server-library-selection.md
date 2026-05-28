# Add-Server Library Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Force the user to choose which libraries are active when adding a server, atomically with the server add.

**Architecture:** Split `ServerRepository.addServer` into `authenticate` (no persistence; returns a `PendingServer` with the auth token and library list) and `commit` (writes server + token + library cache + per-server hidden-id set together). Introduce a nested `server_setup` nav graph with a graph-scoped `ServerSetupViewModel` holding the `PendingServer`, and a new `SelectLibrariesScreen` between `add_server` and `home`.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, AndroidX Navigation Compose, Room, Coroutines, JUnit4.

**Spec:** `docs/superpowers/specs/2026-05-28-add-server-library-selection-design.md`

---

## File Structure

**Create:**
- `core/domain/src/main/kotlin/com/riffle/core/domain/PendingServer.kt` — `PendingServer` data class.
- `app/src/main/kotlin/com/riffle/app/feature/server/ServerSetupViewModel.kt` — graph-scoped holder.
- `app/src/main/kotlin/com/riffle/app/feature/server/SelectLibrariesViewModel.kt`
- `app/src/main/kotlin/com/riffle/app/feature/server/SelectLibrariesScreen.kt`

**Modify:**
- `core/domain/src/main/kotlin/com/riffle/core/domain/ServerRepository.kt` — replace `addServer` with `authenticate` + `commit`.
- `core/data/src/main/kotlin/com/riffle/core/data/ServerRepositoryImpl.kt` — implement new methods; inject `AbsLibraryApi`, `LibraryDao`, `LibraryVisibilityPreferencesStore`.
- `core/data/src/test/kotlin/com/riffle/core/data/ServerRepositoryTest.kt` — replace `addServer` tests with `authenticate` + `commit` tests.
- `app/src/main/kotlin/com/riffle/app/feature/server/AddServerViewModel.kt` — call `authenticate`, expose `navigateToSelectLibraries` event with the `PendingServer`.
- `app/src/main/kotlin/com/riffle/app/feature/server/AddServerScreen.kt` — wire new event.
- `app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt` — replace `composable(ADD_SERVER)` with a `navigation(...)` block for the `server_setup` graph.
- `README.md` — flip the corresponding feature checkbox once shipped.

---

## Task 1: Add `PendingServer` to the domain layer

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/PendingServer.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.riffle.core.domain

/**
 * In-memory holder for a server that has been authenticated but not yet
 * persisted. Carries the auth token, so never persist or log instances of
 * this type.
 */
data class PendingServer(
    val url: ServerUrl,
    val displayName: String,
    val username: String,
    val userId: String,
    val token: String,
    val insecureConnectionAllowed: Boolean,
    val libraries: List<Library>,
)
```

- [ ] **Step 2: Build the module**

Run: `./gradlew :core:domain:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/PendingServer.kt
git commit -m "feat(domain): add PendingServer holder for two-step server add"
```

---

## Task 2: Reshape the `ServerRepository` interface

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/ServerRepository.kt`

This task only changes the contract. The impl will follow in Task 3; the build will be broken between Tasks 2 and 3 — that's expected and is why we don't commit in between.

- [ ] **Step 1: Replace the interface contents**

Open `core/domain/src/main/kotlin/com/riffle/core/domain/ServerRepository.kt` and replace the `interface ServerRepository { ... }` block with:

```kotlin
interface ServerRepository {
    fun observeAll(): Flow<List<Server>>
    suspend fun getActive(): Server?
    suspend fun authenticate(
        url: ServerUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean = false,
    ): AuthenticateResult
    suspend fun commit(
        pending: PendingServer,
        hiddenLibraryIds: Set<String>,
    ): CommitServerResult
    suspend fun setActive(serverId: String)
    suspend fun remove(serverId: String)
    suspend fun getServerVersion(serverId: String): String?
}
```

- [ ] **Step 2: Replace the `AddServerResult` sealed class**

Still in the same file, replace `sealed class AddServerResult { ... }` with:

```kotlin
sealed class AuthenticateResult {
    data class Success(val pending: PendingServer) : AuthenticateResult()
    data class WrongCredentials(val message: String = "Invalid username or password") : AuthenticateResult()
    data class NetworkError(val cause: Throwable) : AuthenticateResult()
    data class LibraryFetchFailed(val cause: Throwable) : AuthenticateResult()
    data class InsecureConnection(val type: InsecureConnectionType) : AuthenticateResult()
}

sealed class CommitServerResult {
    data class Success(val server: Server) : CommitServerResult()
    data class Failure(val cause: Throwable) : CommitServerResult()
}
```

Note: `AddServerResult` is removed entirely. Don't keep it around.

- [ ] **Step 3: Do not build yet**

The impl is still old. Proceed to Task 3 before building.

---

## Task 3: Implement `authenticate` and `commit` in `ServerRepositoryImpl` (TDD)

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/ServerRepositoryImpl.kt`
- Test: `core/data/src/test/kotlin/com/riffle/core/data/ServerRepositoryTest.kt`

### Sub-task 3a: Authenticate — happy path

- [ ] **Step 1: Write the failing test**

Replace the contents of `core/data/src/test/kotlin/com/riffle/core/data/ServerRepositoryTest.kt` keeping its existing top-of-file imports and `fakeDao()` / `fakeTokenStorage` helpers; then add:

```kotlin
@Test
fun `authenticate success returns PendingServer with libraries and persists nothing`() = runTest {
    val dao = fakeDao()
    val tokens = fakeTokenStorage()
    val libDao = fakeLibraryDao()
    val visibility = fakeVisibilityStore()
    val absApi = AbsApi { _, _, _, _ -> NetworkLoginResult.Success("uid-1", "tok-xyz", "admin") }
    val libsApi = AbsLibraryApi { _, _, _ ->
        NetworkLibrariesResult.Success(
            listOf(
                NetworkLibrary(id = "lib-1", name = "Books", mediaType = "book"),
                NetworkLibrary(id = "lib-2", name = "Audiobooks", mediaType = "book"),
                NetworkLibrary(id = "lib-3", name = "Podcasts", mediaType = "podcast"),
            )
        )
    }
    val repo = ServerRepositoryImpl(dao, tokens, absApi, fakeServerInfoApi, libsApi, libDao, visibility)
    val url = ServerUrl.parse("https://abs.example.com")!!

    val result = repo.authenticate(url, "admin", "pass", insecureAllowed = false)

    assertTrue(result is AuthenticateResult.Success)
    val pending = (result as AuthenticateResult.Success).pending
    assertEquals("tok-xyz", pending.token)
    assertEquals(listOf("lib-1", "lib-2"), pending.libraries.map { it.id }) // podcast filtered out
    assertEquals(0, dao.allCount())
    assertNull(tokens.getToken("any"))
    assertTrue(libDao.allEntities().isEmpty())
}
```

(Replace any prior `addServer` tests as you go — they reference the removed API.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*ServerRepositoryTest.authenticate success returns PendingServer*"`
Expected: FAIL (constructor/signature mismatch or unresolved reference).

- [ ] **Step 3: Update `ServerRepositoryImpl` constructor and add `authenticate`**

Replace the class declaration and `addServer` with:

```kotlin
class ServerRepositoryImpl @Inject constructor(
    private val dao: ServerDao,
    private val tokenStorage: TokenStorage,
    private val absApiClient: AbsApi,
    private val serverInfoApi: AbsServerInfoApi,
    private val libraryApi: AbsLibraryApi,
    private val libraryDao: LibraryDao,
    private val visibilityStore: LibraryVisibilityPreferencesStore,
) : ServerRepository {

    override fun observeAll(): Flow<List<Server>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getActive(): Server? = dao.getActive()?.toDomain()

    override suspend fun authenticate(
        url: ServerUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): AuthenticateResult {
        val loginResult = absApiClient.login(url.value, username, password, insecureAllowed)
        return when (loginResult) {
            is NetworkLoginResult.WrongCredentials -> AuthenticateResult.WrongCredentials(loginResult.message)
            is NetworkLoginResult.NetworkError -> AuthenticateResult.NetworkError(loginResult.cause)
            is NetworkLoginResult.InsecureConnection -> AuthenticateResult.InsecureConnection(loginResult.type)
            is NetworkLoginResult.Success -> {
                when (val libs = libraryApi.getLibraries(url.value, loginResult.token, insecureAllowed)) {
                    is NetworkLibrariesResult.NetworkError -> AuthenticateResult.LibraryFetchFailed(libs.cause)
                    is NetworkLibrariesResult.Success -> AuthenticateResult.Success(
                        PendingServer(
                            url = url,
                            displayName = displayNameFrom(url.value),
                            username = loginResult.username,
                            userId = loginResult.userId,
                            token = loginResult.token,
                            insecureConnectionAllowed = insecureAllowed,
                            libraries = libs.libraries
                                .filter { it.mediaType == "book" }
                                .map { Library(id = it.id, name = it.name, mediaType = it.mediaType, isUnsupported = false) },
                        )
                    )
                }
            }
        }
    }
```

Add the imports the IDE flags: `AbsLibraryApi`, `LibraryDao`, `LibraryVisibilityPreferencesStore`, `AuthenticateResult`, `CommitServerResult`, `PendingServer`, `NetworkLibrariesResult`, `Library`.

- [ ] **Step 4: Add the `fakeLibraryDao` and `fakeVisibilityStore` test helpers**

In `ServerRepositoryTest.kt`, add (top of the test class, alongside `fakeDao`/`fakeTokenStorage`):

```kotlin
private fun fakeLibraryDao() = object : LibraryDao {
    private val rows = mutableMapOf<String, MutableList<LibraryEntity>>()
    override suspend fun replaceAllForServer(serverId: String, libraries: List<LibraryEntity>) {
        rows[serverId] = libraries.toMutableList()
    }
    override suspend fun upsertAll(libraries: List<LibraryEntity>) {
        libraries.forEach { rows.getOrPut(it.serverId) { mutableListOf() }.add(it) }
    }
    override fun observeForServer(serverId: String) = throw NotImplementedError()
    override suspend fun deleteForServer(serverId: String) { rows.remove(serverId) }
    fun allEntities(): List<LibraryEntity> = rows.values.flatten()
}

private fun fakeVisibilityStore() = object : LibraryVisibilityPreferencesStore {
    val hidden = mutableMapOf<String, MutableSet<String>>()
    override fun hiddenLibraryIds(serverId: String): Flow<Set<String>> =
        kotlinx.coroutines.flow.flowOf(hidden[serverId].orEmpty())
    override suspend fun hideLibrary(serverId: String, libraryId: String) {
        hidden.getOrPut(serverId) { mutableSetOf() }.add(libraryId)
    }
    override suspend fun showLibrary(serverId: String, libraryId: String) {
        hidden[serverId]?.remove(libraryId)
    }
}
```

If `LibraryDao` has additional methods, stub them with `throw NotImplementedError()` — keep the fake minimal. Check the file `core/database/src/main/kotlin/com/riffle/core/database/LibraryDao.kt` first.

- [ ] **Step 5: Run the test**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*ServerRepositoryTest.authenticate success returns PendingServer*"`
Expected: PASS.

### Sub-task 3b: Authenticate — failure cases

- [ ] **Step 1: Write tests for each failure**

Add to `ServerRepositoryTest.kt`:

```kotlin
@Test
fun `authenticate wrong credentials surfaces message and persists nothing`() = runTest {
    val dao = fakeDao(); val tokens = fakeTokenStorage()
    val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
    val absApi = AbsApi { _, _, _, _ -> NetworkLoginResult.WrongCredentials("nope") }
    val libsApi = AbsLibraryApi { _, _, _ -> error("should not be called") }
    val repo = ServerRepositoryImpl(dao, tokens, absApi, fakeServerInfoApi, libsApi, libDao, visibility)

    val result = repo.authenticate(ServerUrl.parse("https://x")!!, "u", "p", false)

    assertTrue(result is AuthenticateResult.WrongCredentials)
    assertEquals("nope", (result as AuthenticateResult.WrongCredentials).message)
    assertEquals(0, dao.allCount())
}

@Test
fun `authenticate library fetch failure surfaces LibraryFetchFailed and persists nothing`() = runTest {
    val dao = fakeDao(); val tokens = fakeTokenStorage()
    val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
    val absApi = AbsApi { _, _, _, _ -> NetworkLoginResult.Success("uid", "tok", "u") }
    val cause = RuntimeException("boom")
    val libsApi = AbsLibraryApi { _, _, _ -> NetworkLibrariesResult.NetworkError(cause) }
    val repo = ServerRepositoryImpl(dao, tokens, absApi, fakeServerInfoApi, libsApi, libDao, visibility)

    val result = repo.authenticate(ServerUrl.parse("https://x")!!, "u", "p", false)

    assertTrue(result is AuthenticateResult.LibraryFetchFailed)
    assertSame(cause, (result as AuthenticateResult.LibraryFetchFailed).cause)
    assertNull(tokens.getToken("any"))
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*ServerRepositoryTest.authenticate*"`
Expected: All three PASS (the existing `authenticate success` plus the two new ones).

### Sub-task 3c: Commit — happy path

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `commit writes server token library cache and hidden ids together`() = runTest {
    val dao = fakeDao(); val tokens = fakeTokenStorage()
    val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
    val absApi = AbsApi { _, _, _, _ -> error("not called") }
    val libsApi = AbsLibraryApi { _, _, _ -> error("not called") }
    val repo = ServerRepositoryImpl(dao, tokens, absApi, fakeServerInfoApi, libsApi, libDao, visibility)

    val url = ServerUrl.parse("https://abs.example.com")!!
    val pending = PendingServer(
        url = url, displayName = "abs.example.com",
        username = "admin", userId = "uid-1", token = "tok-xyz",
        insecureConnectionAllowed = false,
        libraries = listOf(
            Library("lib-1", "Books", "book", false),
            Library("lib-2", "Audiobooks", "book", false),
        ),
    )

    val result = repo.commit(pending, hiddenLibraryIds = setOf("lib-2"))

    assertTrue(result is CommitServerResult.Success)
    val server = (result as CommitServerResult.Success).server
    assertEquals("abs.example.com", server.displayName)
    assertTrue(server.isActive) // first server becomes active
    assertEquals("tok-xyz", tokens.getToken(server.id))
    assertEquals(2, libDao.allEntities().size)
    assertEquals(setOf("lib-2"), visibility.hidden[server.id])
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*ServerRepositoryTest.commit writes*"`
Expected: FAIL (`commit` not implemented).

- [ ] **Step 3: Implement `commit`**

Add to `ServerRepositoryImpl`, below `authenticate`:

```kotlin
override suspend fun commit(
    pending: PendingServer,
    hiddenLibraryIds: Set<String>,
): CommitServerResult = try {
    val id = UUID.randomUUID().toString()
    val entity = ServerEntity(
        id = id,
        url = pending.url.value,
        displayName = pending.displayName,
        isActive = false,                    // overridden inside transaction
        insecureConnectionAllowed = pending.insecureConnectionAllowed,
        username = pending.username,
    )
    val inserted = dao.upsertAsFirstIfNoActive(entity)
    tokenStorage.saveToken(id, pending.token)
    libraryDao.replaceAllForServer(
        serverId = id,
        libraries = pending.libraries.map {
            LibraryEntity(id = it.id, name = it.name, mediaType = it.mediaType, serverId = id)
        },
    )
    hiddenLibraryIds.forEach { visibilityStore.hideLibrary(id, it) }
    CommitServerResult.Success(inserted.toDomain())
} catch (t: Throwable) {
    CommitServerResult.Failure(t)
}
```

Imports to add: `com.riffle.core.database.LibraryEntity`.

- [ ] **Step 4: Run the test**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*ServerRepositoryTest.commit writes*"`
Expected: PASS.

### Sub-task 3d: Update DI and run the whole module's tests

- [ ] **Step 1: Verify Hilt graph**

`AbsLibraryApi`, `LibraryDao`, and `LibraryVisibilityPreferencesStore` are already provided in the Hilt graph (they are consumed by `LibraryRepositoryImpl` and `SettingsViewModel`). Nothing to wire up; the constructor injection will resolve them automatically.

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run the whole module's tests**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: All tests PASS.

- [ ] **Step 3: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/ServerRepository.kt \
        core/data/src/main/kotlin/com/riffle/core/data/ServerRepositoryImpl.kt \
        core/data/src/test/kotlin/com/riffle/core/data/ServerRepositoryTest.kt
git commit -m "feat(data): split addServer into authenticate + commit"
```

---

## Task 4: `ServerSetupViewModel` (graph-scoped holder)

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/server/ServerSetupViewModel.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.riffle.app.feature.server

import androidx.lifecycle.ViewModel
import com.riffle.core.domain.PendingServer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Scoped to the `server_setup` nested navigation graph. Holds the
 * authenticated-but-not-yet-persisted PendingServer so AddServerScreen and
 * SelectLibrariesScreen can share it without routing the auth token through
 * nav arguments.
 */
@HiltViewModel
class ServerSetupViewModel @Inject constructor() : ViewModel() {
    var pendingServer: PendingServer? = null
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/server/ServerSetupViewModel.kt
git commit -m "feat(app): add ServerSetupViewModel for two-step server add"
```

---

## Task 5: Update `AddServerViewModel` to call `authenticate` and emit a navigate-to-select-libraries event

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/server/AddServerViewModel.kt`

- [ ] **Step 1: Replace the file body**

```kotlin
package com.riffle.app.feature.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.app.BuildConfig
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.domain.PendingServer
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

    var url by mutableStateOf(BuildConfig.DEV_SERVER_URL)
    var username by mutableStateOf(BuildConfig.DEV_USERNAME)
    var password by mutableStateOf(BuildConfig.DEV_PASSWORD)
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var insecureWarning by mutableStateOf<InsecureConnectionType?>(null)

    private val _navigateToSelectLibraries = Channel<PendingServer>(Channel.CONFLATED)
    val navigateToSelectLibraries = _navigateToSelectLibraries.receiveAsFlow()

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
        doAuthenticate(serverUrl, insecureAllowed = false)
    }

    fun onInsecureWarningAccepted() {
        insecureWarning = null
        val serverUrl = ServerUrl.parse(url.trim()) ?: return
        doAuthenticate(serverUrl, insecureAllowed = true)
    }

    fun onInsecureWarningDismissed() {
        insecureWarning = null
    }

    private fun doAuthenticate(serverUrl: ServerUrl, insecureAllowed: Boolean) {
        viewModelScope.launch {
            isLoading = true
            when (val result = repository.authenticate(serverUrl, username, password, insecureAllowed)) {
                is AuthenticateResult.Success -> _navigateToSelectLibraries.send(result.pending)
                is AuthenticateResult.WrongCredentials -> error = result.message
                is AuthenticateResult.NetworkError -> error = "Connection failed: ${result.cause.message}"
                is AuthenticateResult.LibraryFetchFailed ->
                    error = "Connected, but couldn't load libraries: ${result.cause.message}"
                is AuthenticateResult.InsecureConnection -> insecureWarning = result.type
            }
            isLoading = false
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (AddServerScreen still references `navigateBack`; that'll be fixed in Task 6).

If the IDE complains about an unused `navigateBack`, ignore it for now.

---

## Task 6: Update `AddServerScreen` to consume the new event

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/server/AddServerScreen.kt`

- [ ] **Step 1: Update the screen signature and event wiring**

Change the function signature and the `LaunchedEffect` block:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(
    onNavigateBack: () -> Unit,
    onAuthenticated: (com.riffle.core.domain.PendingServer) -> Unit,
    viewModel: AddServerViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) {
        viewModel.navigateToSelectLibraries.collect { onAuthenticated(it) }
    }
    // ...rest of the file unchanged
}
```

(Add a `PendingServer` import at the top if you'd prefer over the fully-qualified type.)

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAIL — `MainScreen.kt` still calls the old signature. That'll be fixed in Task 9.

Don't commit yet; this task is paired with Tasks 7-9.

---

## Task 7: `SelectLibrariesViewModel`

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/server/SelectLibrariesViewModel.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.riffle.app.feature.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.Library
import com.riffle.core.domain.PendingServer
import com.riffle.core.domain.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SelectLibrariesViewModel @Inject constructor(
    private val repository: ServerRepository,
) : ViewModel() {

    private var pending: PendingServer? = null

    var libraries by mutableStateOf<List<Library>>(emptyList())
        private set
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var isSubmitting by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    private val _navigateHome = Channel<Unit>(Channel.CONFLATED)
    val navigateHome = _navigateHome.receiveAsFlow()

    fun bind(pendingServer: PendingServer) {
        if (pending != null) return
        pending = pendingServer
        libraries = pendingServer.libraries
        selectedIds = pendingServer.libraries.map { it.id }.toSet()
    }

    fun toggle(libraryId: String) {
        selectedIds = if (libraryId in selectedIds) selectedIds - libraryId else selectedIds + libraryId
    }

    val canContinue: Boolean get() = selectedIds.isNotEmpty() && !isSubmitting

    fun onContinue() {
        val p = pending ?: return
        if (selectedIds.isEmpty()) return
        viewModelScope.launch {
            isSubmitting = true
            errorMessage = null
            val hidden = p.libraries.map { it.id }.toSet() - selectedIds
            when (val r = repository.commit(p, hidden)) {
                is CommitServerResult.Success -> _navigateHome.send(Unit)
                is CommitServerResult.Failure -> errorMessage = "Couldn't save server: ${r.cause.message}"
            }
            isSubmitting = false
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: still FAIL until MainScreen is updated. Move on.

---

## Task 8: `SelectLibrariesScreen` composable

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/server/SelectLibrariesScreen.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.riffle.app.feature.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.core.domain.PendingServer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectLibrariesScreen(
    pending: PendingServer,
    onNavigateBack: () -> Unit,
    onContinueComplete: () -> Unit,
    viewModel: SelectLibrariesViewModel = hiltViewModel(),
) {
    LaunchedEffect(pending) { viewModel.bind(pending) }
    LaunchedEffect(Unit) { viewModel.navigateHome.collect { onContinueComplete() } }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select libraries") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (viewModel.libraries.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "This server doesn't expose any book libraries.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onNavigateBack) { Text("Go back") }
                }
                return@Column
            }

            Text(
                text = "Choose which libraries to show in Riffle. You can change this later in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(viewModel.libraries, key = { it.id }) { lib ->
                    ListItem(
                        headlineContent = { Text(lib.name) },
                        trailingContent = {
                            Switch(
                                checked = lib.id in viewModel.selectedIds,
                                onCheckedChange = { viewModel.toggle(lib.id) },
                            )
                        },
                    )
                    HorizontalDivider()
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (viewModel.selectedIds.isEmpty()) {
                    Text(
                        "Select at least one library",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = viewModel::onContinue,
                    enabled = viewModel.canContinue,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continue") }
            }
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: still FAIL until nav graph is updated. Move on to Task 9.

---

## Task 9: Wire the nested `server_setup` nav graph in `MainScreen`

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt`

- [ ] **Step 1: Add new route constants**

Near the other `private const val` declarations:

```kotlin
private const val SERVER_SETUP_GRAPH = "server_setup"
private const val SELECT_LIBRARIES = "select_libraries"
```

- [ ] **Step 2: Add imports**

```kotlin
import androidx.navigation.compose.navigation
import com.riffle.app.feature.server.SelectLibrariesScreen
import com.riffle.app.feature.server.ServerSetupViewModel
```

Keep the existing `AddServerScreen` import.

- [ ] **Step 3: Replace the `composable(ADD_SERVER)` block**

Replace the existing block:

```kotlin
composable(ADD_SERVER) {
    AddServerScreen(
        onNavigateBack = {
            navController.navigate(HOME) {
                popUpTo(HOME) { inclusive = true }
            }
        }
    )
}
```

with:

```kotlin
navigation(startDestination = ADD_SERVER, route = SERVER_SETUP_GRAPH) {
    composable(ADD_SERVER) { entry ->
        val setupVm: ServerSetupViewModel = hiltViewModel(
            navController.getBackStackEntry(SERVER_SETUP_GRAPH)
        )
        AddServerScreen(
            onNavigateBack = {
                navController.navigate(HOME) { popUpTo(HOME) { inclusive = true } }
            },
            onAuthenticated = { pending ->
                setupVm.pendingServer = pending
                navController.navigate(SELECT_LIBRARIES)
            },
        )
    }
    composable(SELECT_LIBRARIES) { entry ->
        val setupVm: ServerSetupViewModel = hiltViewModel(
            navController.getBackStackEntry(SERVER_SETUP_GRAPH)
        )
        val pending = setupVm.pendingServer
        if (pending == null) {
            LaunchedEffect(Unit) { navController.popBackStack() }
            return@composable
        }
        SelectLibrariesScreen(
            pending = pending,
            onNavigateBack = { navController.popBackStack() },
            onContinueComplete = {
                navController.navigate(HOME) { popUpTo(HOME) { inclusive = true } }
            },
        )
    }
}
```

Also update the existing `ADD_SERVER` navigate calls so they go through the graph. The graph route equals its `startDestination` route in practice — `navController.navigate(ADD_SERVER)` still works because Compose Navigation resolves it. No changes needed at the call sites (`HomeScreen` and `SettingsScreen` paths).

- [ ] **Step 4: Build the app**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit Tasks 5-9 together**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/server/AddServerViewModel.kt \
        app/src/main/kotlin/com/riffle/app/feature/server/AddServerScreen.kt \
        app/src/main/kotlin/com/riffle/app/feature/server/SelectLibrariesViewModel.kt \
        app/src/main/kotlin/com/riffle/app/feature/server/SelectLibrariesScreen.kt \
        app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt
git commit -m "feat(server): library selection step in add-server flow"
```

---

## Task 10: Manual verification on emulator

The harness AVD is the right target. Use `make harness-test` for automated tests; for UI smoke-testing, install via `./gradlew :app:installDebug` to whatever device is already connected for development.

- [ ] **Step 1: Install on a running emulator/device**

Run: `./gradlew :app:installDebug`

- [ ] **Step 2: Walk the happy path**

In the app:
1. Launch a fresh install (or "Remove server" if you already have one).
2. Navigate to Add Server.
3. Use known-good ABS credentials (see `reference_abs_server.md`).
4. Tap Connect.
5. The new SelectLibrariesScreen should appear with all book libraries on, Continue enabled.
6. Toggle one off. Continue still enabled.
7. Toggle all off. Continue disabled; helper text shows.
8. Toggle at least one back on. Tap Continue. Should land on Home with only the selected libraries in the drawer.
9. Open Settings → confirm the toggles reflect the choice made during setup.

- [ ] **Step 3: Walk the back-out path**

1. Add another server (or remove this one and add again).
2. On the SelectLibrariesScreen, hit Back.
3. AddServerScreen should reappear with URL/username/password fields preserved.
4. Confirm in Settings → the un-completed server did NOT appear in the server list.

- [ ] **Step 4: Walk the auth-failure path**

1. From AddServerScreen, enter a bad password.
2. Tap Connect → "Invalid username or password" surfaces on AddServerScreen, no nav happens, no server persists.

If anything misbehaves, fix it before continuing. If you cannot test the UI in this environment, say so explicitly.

---

## Task 11: README feature checkbox

Per CLAUDE.md, when an issue is closed (a feature merged), mark it in the Features list. There is no specific feature line for this exact behavior today — `grep` for "library" entries in `README.md`. If a matching `- [ ]` line exists (e.g. "Library selection during onboarding"), flip it to `- [x]` and include the change in the PR. If not, do nothing.

- [ ] **Step 1: Inspect README features list**

Open `README.md` and look at the Features section.

- [ ] **Step 2: Flip the relevant checkbox (only if one exists)**

If a matching line exists, edit it; otherwise skip. Commit only if changed:

```bash
git add README.md
git commit -m "docs(readme): mark library-selection-on-add as complete"
```

---

## Done criteria

- All `core:data` unit tests pass: `./gradlew :core:data:testDebugUnitTest`.
- App builds and installs: `./gradlew :app:assembleDebug`.
- Manual flows in Task 10 behave as described.
- No reference to `AddServerResult` remains in the codebase: `grep -r AddServerResult` returns nothing.
