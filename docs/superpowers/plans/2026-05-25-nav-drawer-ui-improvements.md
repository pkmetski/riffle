# Nav Drawer UI Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show username + server version in the drawer header, and pin Downloads/Settings/app-version to the bottom of the navigation drawer.

**Architecture:** Username is persisted with the server entry (DB migration); server version is fetched lazily from `GET /api/server-info` and cached in-memory in the ViewModel. The UI changes are confined to `NavigationDrawerComposable.kt` and `NavigationDrawerViewModel`.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Room (with KSP schema export), Hilt, OkHttp, kotlinx.serialization

---

### Task 1: Add `AbsServerInfoApi` interface + DTO, extend `NetworkLoginResult.Success` with `username`

**Files:**
- Create: `core/network/src/main/kotlin/com/riffle/core/network/AbsServerInfoApi.kt`
- Create: `core/network/src/main/kotlin/com/riffle/core/network/model/AbsServerInfoResponse.kt`
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/NetworkLoginResult.kt` — if it exists as a separate file, otherwise it lives in `AbsApiClient.kt` (lines 30–35); move or edit in place

- [ ] **Step 1: Check where `NetworkLoginResult` is defined**

  It is defined at the top of `core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt` (lines 30–35). It stays there.

- [ ] **Step 2: Add `username` to `NetworkLoginResult.Success`**

  In `core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt`, change:
  ```kotlin
  data class Success(val userId: String, val token: String) : NetworkLoginResult()
  ```
  to:
  ```kotlin
  data class Success(val userId: String, val token: String, val username: String) : NetworkLoginResult()
  ```

- [ ] **Step 3: Create `AbsServerInfoResponse` DTO**

  Create `core/network/src/main/kotlin/com/riffle/core/network/model/AbsServerInfoResponse.kt`:
  ```kotlin
  package com.riffle.core.network.model

  import kotlinx.serialization.Serializable

  @Serializable
  internal data class AbsServerInfoResponse(val version: String)
  ```

- [ ] **Step 4: Create `AbsServerInfoApi` interface**

  Create `core/network/src/main/kotlin/com/riffle/core/network/AbsServerInfoApi.kt`:
  ```kotlin
  package com.riffle.core.network

  interface AbsServerInfoApi {
      suspend fun getServerInfo(
          baseUrl: String,
          token: String,
          insecureAllowed: Boolean,
      ): String?
  }
  ```

- [ ] **Step 5: Commit**

  ```bash
  git add core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt \
          core/network/src/main/kotlin/com/riffle/core/network/AbsServerInfoApi.kt \
          core/network/src/main/kotlin/com/riffle/core/network/model/AbsServerInfoResponse.kt
  git commit -m "feat(network): add AbsServerInfoApi and username to NetworkLoginResult.Success"
  ```

---

### Task 2: Implement `getServerInfo` in `AbsApiClient` and return `username` from `login()`

**Files:**
- Modify: `core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt`

- [ ] **Step 1: Make `AbsApiClient` implement `AbsServerInfoApi`**

  Change the class declaration from:
  ```kotlin
  class AbsApiClient(private val httpClient: OkHttpClient) : AbsApi, AbsLibraryApi, AbsSessionApi {
  ```
  to:
  ```kotlin
  class AbsApiClient(private val httpClient: OkHttpClient) : AbsApi, AbsLibraryApi, AbsSessionApi, AbsServerInfoApi {
  ```

- [ ] **Step 2: Add `model.AbsServerInfoResponse` import**

  Add to the imports block:
  ```kotlin
  import com.riffle.core.network.model.AbsServerInfoResponse
  ```

- [ ] **Step 3: Return `username` from `login()`**

  In the `login()` implementation, change the success branch from:
  ```kotlin
  200 -> {
      val raw = response.body?.string() ?: return@withContext NetworkLoginResult.NetworkError(
          IOException("Empty response body")
      )
      val parsed = json.decodeFromString<AbsLoginResponse>(raw)
      NetworkLoginResult.Success(userId = parsed.user.id, token = parsed.user.token)
  }
  ```
  to:
  ```kotlin
  200 -> {
      val raw = response.body?.string() ?: return@withContext NetworkLoginResult.NetworkError(
          IOException("Empty response body")
      )
      val parsed = json.decodeFromString<AbsLoginResponse>(raw)
      NetworkLoginResult.Success(
          userId = parsed.user.id,
          token = parsed.user.token,
          username = parsed.user.username,
      )
  }
  ```

- [ ] **Step 4: Implement `getServerInfo()`**

  Add this function to `AbsApiClient` (before the private `trustAllCerts` extension):
  ```kotlin
  override suspend fun getServerInfo(
      baseUrl: String,
      token: String,
      insecureAllowed: Boolean,
  ): String? = withContext(Dispatchers.IO) {
      val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
      val request = Request.Builder()
          .url("$baseUrl/api/server-info")
          .addHeader("Authorization", "Bearer $token")
          .get()
          .build()
      try {
          val response = client.newCall(request).execute()
          if (!response.isSuccessful) return@withContext null
          val raw = response.body?.string() ?: return@withContext null
          json.decodeFromString<AbsServerInfoResponse>(raw).version
      } catch (_: Exception) {
          null
      }
  }
  ```

- [ ] **Step 5: Build to verify compilation**

  ```bash
  ./gradlew :core:network:compileDebugKotlin
  ```
  Expected: BUILD SUCCESSFUL with no errors.

- [ ] **Step 6: Commit**

  ```bash
  git add core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt
  git commit -m "feat(network): implement getServerInfo and return username from login"
  ```

---

### Task 3: Add `username` to `Server` domain model and `ServerEntity`

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/Server.kt`
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/ServerEntity.kt`

- [ ] **Step 1: Add `username` to `Server`**

  Replace the contents of `core/domain/src/main/kotlin/com/riffle/core/domain/Server.kt`:
  ```kotlin
  package com.riffle.core.domain

  data class Server(
      val id: String,
      val url: ServerUrl,
      val displayName: String,
      val isActive: Boolean,
      val insecureConnectionAllowed: Boolean,
      val username: String,
  )
  ```

- [ ] **Step 2: Add `username` to `ServerEntity`**

  Replace the contents of `core/database/src/main/kotlin/com/riffle/core/database/ServerEntity.kt`:
  ```kotlin
  package com.riffle.core.database

  import androidx.room.Entity
  import androidx.room.PrimaryKey

  @Entity(tableName = "servers")
  data class ServerEntity(
      @PrimaryKey val id: String,
      val url: String,
      val displayName: String,
      val isActive: Boolean,
      val insecureConnectionAllowed: Boolean,
      val username: String,
  )
  ```

- [ ] **Step 3: Commit**

  ```bash
  git add core/domain/src/main/kotlin/com/riffle/core/domain/Server.kt \
          core/database/src/main/kotlin/com/riffle/core/database/ServerEntity.kt
  git commit -m "feat(domain): add username field to Server and ServerEntity"
  ```

---

### Task 4: Database migration 11 → 12

**Files:**
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt`
- Build to generate: `core/database/schemas/com.riffle.core.database.RiffleDatabase/12.json`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt`
- Modify: `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt`

- [ ] **Step 1: Bump DB version and add `MIGRATION_11_12` in `RiffleDatabase.kt`**

  Change `version = 11` to `version = 12`:
  ```kotlin
  @Database(
      entities = [...],
      version = 12,
      exportSchema = true,
  )
  ```

  Add the migration to the `companion object` after `MIGRATION_10_11`:
  ```kotlin
  val MIGRATION_11_12 = object : Migration(11, 12) {
      override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE `servers` ADD COLUMN `username` TEXT NOT NULL DEFAULT ''")
      }
  }
  ```

- [ ] **Step 2: Build to export schema JSON**

  ```bash
  ./gradlew :core:database:kspDebugKotlin
  ```
  Expected: creates `core/database/schemas/com.riffle.core.database.RiffleDatabase/12.json`.

- [ ] **Step 3: Register migration in `DatabaseModule.kt`**

  In `core/data/src/main/kotlin/com/riffle/core/data/di/DatabaseModule.kt`, add `RiffleDatabase.MIGRATION_11_12` to the `addMigrations(...)` call:
  ```kotlin
  .addMigrations(
      RiffleDatabase.MIGRATION_1_2,
      RiffleDatabase.MIGRATION_2_3,
      RiffleDatabase.MIGRATION_3_4,
      RiffleDatabase.MIGRATION_4_5,
      RiffleDatabase.MIGRATION_5_6,
      RiffleDatabase.MIGRATION_6_7,
      RiffleDatabase.MIGRATION_7_8,
      RiffleDatabase.MIGRATION_8_9,
      RiffleDatabase.MIGRATION_9_10,
      RiffleDatabase.MIGRATION_10_11,
      RiffleDatabase.MIGRATION_11_12,
  )
  ```

- [ ] **Step 4: Write migration test for 11→12**

  In `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt`, add before the `migrateFullChain` test:
  ```kotlin
  @Test
  fun migration11To12() {
      helper.createDatabase(TEST_DB, 11).use { db ->
          db.execSQL(
              "INSERT INTO servers (id, url, displayName, isActive, insecureConnectionAllowed) " +
                  "VALUES ('s1', 'http://localhost', 'My Server', 1, 0)"
          )
      }

      val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, RiffleDatabase.MIGRATION_11_12)

      db.query("SELECT id, url, displayName, username FROM servers WHERE id = 's1'").use { cursor ->
          assertEquals(1, cursor.count)
          cursor.moveToFirst()
          assertEquals("s1", cursor.getString(0))
          assertEquals("http://localhost", cursor.getString(1))
          assertEquals("My Server", cursor.getString(2))
          assertEquals("", cursor.getString(3))
      }
  }
  ```

- [ ] **Step 5: Update `migrateFullChain` to include `MIGRATION_11_12` and target version 12**

  Change:
  ```kotlin
  val db = helper.runMigrationsAndValidate(
      TEST_DB, 11, true,
      RiffleDatabase.MIGRATION_1_2,
      ...
      RiffleDatabase.MIGRATION_10_11,
  )
  ```
  to:
  ```kotlin
  val db = helper.runMigrationsAndValidate(
      TEST_DB, 12, true,
      RiffleDatabase.MIGRATION_1_2,
      RiffleDatabase.MIGRATION_2_3,
      RiffleDatabase.MIGRATION_3_4,
      RiffleDatabase.MIGRATION_4_5,
      RiffleDatabase.MIGRATION_5_6,
      RiffleDatabase.MIGRATION_6_7,
      RiffleDatabase.MIGRATION_7_8,
      RiffleDatabase.MIGRATION_8_9,
      RiffleDatabase.MIGRATION_9_10,
      RiffleDatabase.MIGRATION_10_11,
      RiffleDatabase.MIGRATION_11_12,
  )
  ```

  Also update the cursor assertion block to verify `username` defaults to `""`:
  ```kotlin
  db.query("SELECT url, displayName, username FROM servers WHERE id = 's1'").use { cursor ->
      assertEquals(1, cursor.count)
      cursor.moveToFirst()
      assertEquals("http://localhost", cursor.getString(0))
      assertEquals("My Server", cursor.getString(1))
      assertEquals("", cursor.getString(2))
  }
  ```

- [ ] **Step 6: Run migration tests**

  ```bash
  make harness-test
  ```
  Expected: migration11To12 and migrateFullChain both PASS.

- [ ] **Step 7: Commit**

  ```bash
  git add core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt \
          "core/database/schemas/com.riffle.core.database.RiffleDatabase/12.json" \
          core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt \
          core/data/src/main/kotlin/com/riffle/core/data/di/DatabaseModule.kt
  git commit -m "feat(database): migration 11→12 adds username column to servers table"
  ```

---

### Task 5: Wire `username` through the data layer

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/ServerRepositoryImpl.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt`

- [ ] **Step 1: Register `AbsServerInfoApi` binding in `DataModule`**

  In `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt`, add a new binding in the abstract class body (alongside the other `@Binds` for `AbsApi`, `AbsLibraryApi`, `AbsSessionApi`):
  ```kotlin
  @Binds
  @Singleton
  abstract fun bindAbsServerInfoApi(impl: AbsApiClient): AbsServerInfoApi
  ```

  Add the import at the top of the file:
  ```kotlin
  import com.riffle.core.network.AbsServerInfoApi
  ```

- [ ] **Step 2: Pass `username` when creating `ServerEntity` in `addServer()`**

  In `ServerRepositoryImpl.addServer()`, change the `Success` branch to store username:
  ```kotlin
  is NetworkLoginResult.Success -> {
      val id = UUID.randomUUID().toString()
      val entity = ServerEntity(
          id = id,
          url = url.value,
          displayName = displayNameFrom(url.value),
          isActive = false,
          insecureConnectionAllowed = insecureAllowed,
          username = networkResult.username,
      )
      val inserted = dao.upsertAsFirstIfNoActive(entity)
      tokenStorage.saveToken(id, networkResult.token)
      AddServerResult.Success(inserted.toDomain())
  }
  ```

- [ ] **Step 3: Map `username` in `toDomain()`**

  Change:
  ```kotlin
  private fun ServerEntity.toDomain(): Server {
      val parsedUrl = ServerUrl.parse(url)
          ?: ServerUrl.parse("https://invalid.example.com")!!
      return Server(
          id = id,
          url = parsedUrl,
          displayName = displayName,
          isActive = isActive,
          insecureConnectionAllowed = insecureConnectionAllowed,
      )
  }
  ```
  to:
  ```kotlin
  private fun ServerEntity.toDomain(): Server {
      val parsedUrl = ServerUrl.parse(url)
          ?: ServerUrl.parse("https://invalid.example.com")!!
      return Server(
          id = id,
          url = parsedUrl,
          displayName = displayName,
          isActive = isActive,
          insecureConnectionAllowed = insecureConnectionAllowed,
          username = username,
      )
  }
  ```

- [ ] **Step 4: Build to verify compilation**

  ```bash
  ./gradlew :core:data:compileDebugKotlin :app:compileDebugKotlin
  ```
  Expected: BUILD SUCCESSFUL. Fix any remaining call-sites that construct `Server(...)` directly without `username` — add `username = ""` as a default where needed.

- [ ] **Step 5: Commit**

  ```bash
  git add core/data/src/main/kotlin/com/riffle/core/data/ServerRepositoryImpl.kt \
          core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt
  git commit -m "feat(data): persist and expose username from login response on Server"
  ```

---

### Task 6: Add lazy server version fetch to `NavigationDrawerViewModel`

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/navigation/NavigationDrawerViewModel.kt`

- [ ] **Step 1: Inject `AbsServerInfoApi` and `TokenStorage`**

  Add imports:
  ```kotlin
  import com.riffle.core.domain.TokenStorage
  import com.riffle.core.network.AbsServerInfoApi
  ```

  Change the constructor:
  ```kotlin
  @HiltViewModel
  class NavigationDrawerViewModel @Inject constructor(
      private val serverRepository: ServerRepository,
      private val libraryRepository: LibraryRepository,
      private val visibilityStore: LibraryVisibilityPreferencesStore,
      private val serverInfoApi: AbsServerInfoApi,
      private val tokenStorage: TokenStorage,
  ) : ViewModel() {
  ```

- [ ] **Step 2: Add the version cache and `serverVersion` StateFlow**

  Add after the `activeServer` StateFlow declaration:
  ```kotlin
  private val serverVersionCache = mutableMapOf<String, String?>()
  private val _serverVersion = MutableStateFlow<String?>(null)
  val serverVersion: StateFlow<String?> = _serverVersion.asStateFlow()
  ```

  Add import:
  ```kotlin
  import kotlinx.coroutines.flow.asStateFlow
  ```

- [ ] **Step 3: Add `onDrawerOpened()`**

  Add this function to `NavigationDrawerViewModel`:
  ```kotlin
  fun onDrawerOpened() {
      val server = activeServer.value ?: return
      if (server.id in serverVersionCache) {
          _serverVersion.value = serverVersionCache[server.id]
          return
      }
      viewModelScope.launch {
          val token = tokenStorage.getToken(server.id) ?: return@launch
          val version = serverInfoApi.getServerInfo(
              baseUrl = server.url.value,
              token = token,
              insecureAllowed = server.insecureConnectionAllowed,
          )
          serverVersionCache[server.id] = version
          _serverVersion.value = version
      }
  }
  ```

- [ ] **Step 4: Clear version when active server changes**

  In the `init` block (or add one), update `_serverVersion` whenever the active server changes:
  ```kotlin
  init {
      viewModelScope.launch {
          activeServer.collect { server ->
              _serverVersion.value = serverVersionCache[server?.id]
          }
      }
      // existing redirect logic below...
      viewModelScope.launch {
          visibleLibraries.collect { visible ->
              val lastId = _lastActiveLibraryId.value ?: return@collect
              if (visible.isNotEmpty() && visible.none { it.id == lastId }) {
                  _redirectToLibrary.emit(visible.first())
              }
          }
      }
  }
  ```

- [ ] **Step 5: Build to verify compilation**

  ```bash
  ./gradlew :app:compileDebugKotlin
  ```
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

  ```bash
  git add app/src/main/kotlin/com/riffle/app/feature/navigation/NavigationDrawerViewModel.kt
  git commit -m "feat(nav): lazy-fetch and cache server version in NavigationDrawerViewModel"
  ```

---

### Task 7: Update `NavigationDrawerComposable` — header and bottom layout

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/navigation/NavigationDrawerComposable.kt`

- [ ] **Step 1: Find where `RiffleNavigationDrawer` is called and add the new parameters**

  ```bash
  grep -r "RiffleNavigationDrawer" app/src/
  ```

  Open the call-site (likely `app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt`) and add two new parameters:
  ```kotlin
  RiffleNavigationDrawer(
      ...
      serverVersion = viewModel.serverVersion.collectAsStateWithLifecycle().value,
      onDrawerOpened = viewModel::onDrawerOpened,
      ...
  )
  ```

- [ ] **Step 2: Add imports to `NavigationDrawerComposable.kt`**

  Add:
  ```kotlin
  import androidx.compose.foundation.layout.fillMaxHeight
  import androidx.compose.foundation.layout.fillMaxSize
  import androidx.compose.foundation.rememberScrollState
  import androidx.compose.foundation.verticalScroll
  import androidx.compose.material3.DrawerValue
  import androidx.compose.runtime.LaunchedEffect
  import androidx.compose.ui.Alignment
  import androidx.compose.ui.text.SpanStyle
  import androidx.compose.ui.text.buildAnnotatedString
  import androidx.compose.ui.text.withStyle
  import androidx.compose.ui.unit.sp
  import com.riffle.app.BuildConfig
  ```

- [ ] **Step 3: Update `RiffleNavigationDrawer` signature**

  Add two parameters:
  ```kotlin
  fun RiffleNavigationDrawer(
      drawerState: DrawerState,
      gesturesEnabled: Boolean = true,
      activeServer: Server?,
      allServers: List<Server>,
      visibleLibraries: List<Library>,
      activeLibraryId: String?,
      serverVersion: String?,
      onDrawerOpened: () -> Unit,
      onServerSelected: (Server) -> Unit,
      onLibrarySelected: (Library) -> Unit,
      onDownloadsSelected: () -> Unit,
      onSettingsSelected: () -> Unit,
      content: @Composable () -> Unit,
  )
  ```

- [ ] **Step 4: Add `LaunchedEffect` to trigger `onDrawerOpened` and restructure drawer body**

  Replace the `ModalNavigationDrawer` body:
  ```kotlin
  ModalNavigationDrawer(
      drawerState = drawerState,
      gesturesEnabled = gesturesEnabled,
      drawerContent = {
          ModalDrawerSheet {
              LaunchedEffect(drawerState.currentValue) {
                  if (drawerState.currentValue == DrawerValue.Open) {
                      onDrawerOpened()
                  }
              }
              Column(modifier = Modifier.fillMaxHeight()) {
                  DrawerHeader(
                      activeServer = activeServer,
                      allServers = allServers,
                      serverVersion = serverVersion,
                      onServerSelected = onServerSelected,
                  )
                  Column(
                      modifier = Modifier
                          .weight(1f)
                          .verticalScroll(rememberScrollState()),
                  ) {
                      visibleLibraries.forEach { library ->
                          NavigationDrawerItem(
                              label = { Text(library.name) },
                              selected = library.id == activeLibraryId,
                              onClick = { onLibrarySelected(library) },
                          )
                      }
                  }
                  HorizontalDivider()
                  NavigationDrawerItem(
                      label = { Text("Downloads") },
                      icon = { Icon(Icons.Default.Download, contentDescription = null) },
                      selected = false,
                      onClick = onDownloadsSelected,
                  )
                  NavigationDrawerItem(
                      label = { Text("Settings") },
                      icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                      selected = false,
                      onClick = onSettingsSelected,
                  )
                  Text(
                      text = "Riffle v${BuildConfig.VERSION_NAME}",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                      modifier = Modifier
                          .fillMaxWidth()
                          .padding(bottom = 12.dp, top = 4.dp),
                      textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                  )
              }
          }
      },
      content = content,
  )
  ```

  Add missing imports:
  ```kotlin
  import androidx.compose.foundation.layout.Column
  import androidx.compose.foundation.layout.padding
  import androidx.compose.ui.unit.dp
  ```

- [ ] **Step 5: Update `DrawerHeader` — add `serverVersion` param and update headline/subtitle**

  Change `DrawerHeader` signature:
  ```kotlin
  private fun DrawerHeader(
      activeServer: Server?,
      allServers: List<Server>,
      serverVersion: String?,
      onServerSelected: (Server) -> Unit,
  )
  ```

  Update the `ListItem` headline and supporting content:
  ```kotlin
  ListItem(
      headlineContent = {
          val name = activeServer?.displayName ?: "No server"
          val username = activeServer?.username?.takeIf { it.isNotEmpty() }
          if (username != null) {
              Text(
                  buildAnnotatedString {
                      append(name)
                      append(" ")
                      withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                          append("[$username]")
                      }
                  }
              )
          } else {
              Text(name)
          }
      },
      supportingContent = {
          val url = activeServer?.url?.value ?: ""
          if (serverVersion != null) {
              Text("$url · $serverVersion")
          } else {
              Text(url)
          }
      },
      trailingContent = {
          Icon(
              imageVector = if (switcherExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
              contentDescription = "Toggle server switcher",
          )
      },
      modifier = Modifier.clickable { switcherExpanded = !switcherExpanded },
  )
  ```

- [ ] **Step 6: Update dropdown items to show `[username]` per server**

  Replace the `DropdownMenuItem` text block:
  ```kotlin
  DropdownMenuItem(
      text = {
          Column {
              val username = server.username.takeIf { it.isNotEmpty() }
              if (username != null) {
                  Text(
                      buildAnnotatedString {
                          append(server.displayName)
                          append(" ")
                          withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                              append("[$username]")
                          }
                      }
                  )
              } else {
                  Text(server.displayName)
              }
              Text(
                  text = server.url.value,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
          }
      },
      leadingIcon = if (server.isActive) {
          { Icon(Icons.Default.Check, contentDescription = null) }
      } else null,
      onClick = {
          switcherExpanded = false
          onServerSelected(server)
      },
  )
  ```

- [ ] **Step 7: Build to verify compilation**

  ```bash
  ./gradlew :app:compileDebugKotlin
  ```
  Expected: BUILD SUCCESSFUL. Fix any remaining compilation errors.

- [ ] **Step 8: Commit**

  ```bash
  git add app/src/main/kotlin/com/riffle/app/feature/navigation/NavigationDrawerComposable.kt \
          app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt
  git commit -m "feat(ui): show username + server version in drawer header, pin Downloads/Settings/version to bottom"
  ```

---

### Task 8: Final build and feature verification

- [ ] **Step 1: Full project build**

  ```bash
  ./gradlew assembleDebug
  ```
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run harness tests**

  ```bash
  make harness-test
  ```
  Expected: all tests PASS including the new migration tests.

- [ ] **Step 3: Manual smoke check**

  Install and open the app. Verify:
  - Drawer header shows `displayName [username]` once username is populated (new logins; existing entries show no brackets until re-login)
  - Server version appears next to the URL after the drawer is opened and the fetch completes
  - If server is unreachable, version never appears — no crash or error toast
  - Downloads + Settings are pinned to the bottom of the drawer
  - `Riffle v0.1.0` appears below Settings in small dimmed text
  - Server switcher dropdown shows `[username]` per server entry

- [ ] **Step 4: Final commit (if any loose changes)**

  ```bash
  git status
  # commit any remaining changes
  ```
