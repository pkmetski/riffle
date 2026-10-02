# Drawer/Source Switcher and Shell Navigation Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close all Android↔iOS navigation drawer and shell navigation gaps from issue #1143 — shared `RiffleNavigationDrawer` composable in `feature/library-ui`, real back stack on iOS, fixed shell states, tablet layout, and complete string i18n via composeResources.

**Architecture:** Move `NavigationDrawerComposable.kt` from `:app` into `feature/library-ui` (android+ios module), converting Android-specific strings to composeResources and passing version info as parameters. Replace iOS's bespoke `DrawerViewModel` and `DrawerSheetContent` with the shared composable and `NavigationDrawerViewModel`, and convert the iOS `LibraryHost` single-nav state to a real back stack.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Koin, composeResources (JetBrains Compose Multiplatform resource system), Material3, Kotlin sealed interface back stack.

**Spec:** GitHub Issue #1143

## Global Constraints

- All new strings in `feature/library-ui/src/commonMain/composeResources/values/strings.xml`; matching `values-es/strings.xml` and `values-bg/strings.xml` required — the `checkTranslations` task enforces completeness.
- `publicResClass = true` in the `compose.resources {}` block so `:shared` and `:app` can reach `Res.string.*`.
- No `*Labels.English` defaults and no hardcoded English literals in the lifted drawer code.
- Every Android test addition requires an iOS counterpart (AGENTS.md); every iOS test addition requires an Android counterpart.
- Every interactive control must carry a `testTag` from `TestTags.kt`.
- `LibraryNav` back stack: use `remember` (not `rememberSaveable`) because `LibraryNav.ReaderDestination` holds a `LibraryItem` which is not serializable.
- `Removed-test:` commit trailers required when deleting `DrawerViewModelTest` test functions.
- No `XCTSkip` in Swift tests.

## Review Focus

- **Riffle mode with zero sources**: `isRiffleMode=true` but `allServers` empty — header must read "Riffle" not "No source" and the switcher must show no items (no crash on `shouldShowRiffleSource(0) = false`).
- **Back stack on reader open**: tapping Read → EpubReaderScreen → Back must return to `ItemDetail`, not `Items`; previously a single `nav` value always popped to `Items`.
- **composeResources on Android**: strings from `feature/library-ui` require the asset bridge (`copyComposeResourcesForApk` task + `app/build.gradle.kts` `srcDir`) — omitting either causes `MissingResourceException` at runtime. Verified by running the app on a device after build.
- **iOS Koin graph**: `NavigationDrawerViewModel` needs `NowPlayingNavigator` which is not yet registered in `shared/src/iosMain/kotlin/com/riffle/shared/Koin.kt` — omitting it causes a `MissingKoinDefinitionException` crash on app start.
- **Tablet `hidePermanentDrawerPanel` on reader routes**: iOS has no `currentRoute` string; instead check `navStack.lastOrNull() is LibraryNav.ReaderDestination` — getting this wrong keeps the permanent drawer visible inside the reader.

---

## Task 1: Add composeResources to `feature/library-ui` and scaffold drawer strings

**Files:**
- Modify: `feature/library-ui/build.gradle.kts`
- Create: `feature/library-ui/src/commonMain/composeResources/values/strings.xml`
- Create: `feature/library-ui/src/commonMain/composeResources/values-es/strings.xml`
- Create: `feature/library-ui/src/commonMain/composeResources/values-bg/strings.xml`
- Modify: `app/build.gradle.kts` (add asset bridge)

**Interfaces:**
- Produces: `Res.string.ui_drawer_downloads`, `Res.string.ui_drawer_settings`, `Res.string.ui_drawer_riffle`, `Res.string.ui_drawer_no_source`, `Res.string.ui_toggle_source_switcher`, `Res.string.ui_active_source`, `Res.string.ui_app_version_footer`, `Res.string.ui_unable_to_connect_to_source`, `Res.string.ui_retry` accessible from `feature.library.ui.generated.resources.Res`

- [ ] **Step 1: Add compose resources dependency and config to `feature/library-ui/build.gradle.kts`**

  Append inside the `sourceSets { commonMain.dependencies { ... } }` block:
  ```kotlin
  implementation(compose.components.resources)
  ```
  
  After the closing `}` of the `kotlin { }` block, add:
  ```kotlin
  compose.resources {
      packageOfResClass = "com.riffle.feature.library.ui.generated.resources"
      publicResClass = true
  }
  
  val copyComposeResourcesForApk by tasks.registering(Copy::class) {
      dependsOn(tasks.matching { it.name == "prepareComposeResourcesTaskForCommonMain" })
      from(layout.buildDirectory.dir("generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"))
      into(layout.buildDirectory.dir("composeAssetsForApk/composeResources/com.riffle.feature.library.ui.generated.resources"))
  }
  ```

- [ ] **Step 2: Create `feature/library-ui/src/commonMain/composeResources/values/strings.xml`**

  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <!--
    Navigation drawer copy, shared by Android (:app) and iOS (:shared) through Compose Multiplatform
    resources. Any edit here must be mirrored in values-es/ and values-bg/.
  -->
  <resources>
      <string name="ui_drawer_riffle" translatable="false">Riffle</string>
      <string name="ui_drawer_no_source">No source</string>
      <string name="ui_drawer_downloads">Downloads</string>
      <string name="ui_drawer_settings">Settings</string>
      <string name="ui_toggle_source_switcher">Toggle source switcher</string>
      <string name="ui_active_source">Active source</string>
      <string name="ui_app_version_footer">Riffle v%1$s%2$s</string>
      <string name="ui_unable_to_connect_to_source">Unable to connect to source</string>
      <string name="ui_retry">Retry</string>
  </resources>
  ```

- [ ] **Step 3: Create `values-es/strings.xml` (Spanish translations)**

  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <resources>
      <string name="ui_drawer_riffle" translatable="false">Riffle</string>
      <string name="ui_drawer_no_source">Sin fuente</string>
      <string name="ui_drawer_downloads">Descargas</string>
      <string name="ui_drawer_settings">Ajustes</string>
      <string name="ui_toggle_source_switcher">Cambiar selector de fuente</string>
      <string name="ui_active_source">Fuente activa</string>
      <string name="ui_app_version_footer">Riffle v%1$s%2$s</string>
      <string name="ui_unable_to_connect_to_source">No se puede conectar a la fuente</string>
      <string name="ui_retry">Reintentar</string>
  </resources>
  ```

- [ ] **Step 4: Create `values-bg/strings.xml` (Bulgarian translations)**

  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <resources>
      <string name="ui_drawer_riffle" translatable="false">Riffle</string>
      <string name="ui_drawer_no_source">Няма източник</string>
      <string name="ui_drawer_downloads">Изтегляния</string>
      <string name="ui_drawer_settings">Настройки</string>
      <string name="ui_toggle_source_switcher">Превключи избор на източник</string>
      <string name="ui_active_source">Активен източник</string>
      <string name="ui_app_version_footer">Riffle v%1$s%2$s</string>
      <string name="ui_unable_to_connect_to_source">Неуспешна връзка с източника</string>
      <string name="ui_retry">Повторен опит</string>
  </resources>
  ```

- [ ] **Step 5: Add asset bridge to `app/build.gradle.kts`**

  In the `android { ... }` block, after the existing `srcDir` lines (around line 50), add:
  ```kotlin
  sourceSets.getByName("main").assets.srcDir(
      rootProject.layout.projectDirectory.dir("feature/library-ui/build/composeAssetsForApk"),
  )
  ```
  
  In the `tasks.matching { ... }.configureEach { ... }` block (around line 252), add:
  ```kotlin
  dependsOn(":feature:library-ui:copyComposeResourcesForApk")
  ```

- [ ] **Step 6: Verify the resource generation compiles**

  ```bash
  export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
  ./gradlew :feature:library-ui:generateCommonMainResourceAccessors 2>&1 | tail -5
  ```
  Expected: BUILD SUCCESSFUL and `Res` object generated.

- [ ] **Step 7: Commit**

  ```bash
  git add feature/library-ui/build.gradle.kts \
         feature/library-ui/src/commonMain/composeResources/ \
         app/build.gradle.kts
  git commit -m "build(library-ui): add composeResources — scaffold drawer strings (en/es/bg)"
  ```

---

## Task 2: Add `RiffleAppIcon` to `feature/design-system` for cross-platform use

**Files:**
- Create: `feature/design-system/src/commonMain/composeResources/drawable/ic_riffle_logo.png`
- Create: `feature/design-system/src/commonMain/kotlin/com/riffle/feature/designsystem/RiffleAppIcon.kt`

**Interfaces:**
- Produces: `@Composable fun RiffleAppIcon(modifier: Modifier = Modifier, size: Dp = 24.dp)` in `com.riffle.feature.designsystem`

- [ ] **Step 1: Copy the xxhdpi launcher icon to design-system composeResources**

  ```bash
  cp app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png \
     feature/design-system/src/commonMain/composeResources/drawable/ic_riffle_logo.png
  ```

- [ ] **Step 2: Create `feature/design-system/src/commonMain/kotlin/.../RiffleAppIcon.kt`**

  ```kotlin
  package com.riffle.feature.designsystem
  
  import androidx.compose.foundation.Image
  import androidx.compose.foundation.shape.CircleShape
  import androidx.compose.runtime.Composable
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.draw.clip
  import androidx.compose.ui.unit.Dp
  import androidx.compose.ui.unit.dp
  import com.riffle.feature.designsystem.generated.resources.Res
  import com.riffle.feature.designsystem.generated.resources.ic_riffle_logo
  import org.jetbrains.compose.resources.painterResource
  
  @Composable
  fun RiffleAppIcon(
      modifier: Modifier = Modifier,
      size: Dp = 24.dp,
  ) {
      Image(
          painter = painterResource(Res.drawable.ic_riffle_logo),
          contentDescription = null,
          modifier = modifier
              .then(androidx.compose.foundation.layout.Modifier.size(size))
              .clip(CircleShape),
      )
  }
  ```
  
  Note: `design-system` already has composeResources configured; no build change needed.
  
  Note: After this task, the Android-specific `RiffleAppIcon.kt` in `app/src/main/kotlin/com/riffle/app/ui/theme/RiffleAppIcon.kt` is kept as-is (it uses adaptive icons for best quality on Android). `MainScreen.kt` will continue importing from `:app`. The new composable in `feature/design-system` is for the shared drawer used on iOS.

- [ ] **Step 3: Verify design-system generates the resource accessor**

  ```bash
  export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
  ./gradlew :feature:design-system:generateCommonMainResourceAccessors 2>&1 | tail -5
  ```
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

  ```bash
  git add feature/design-system/src/commonMain/composeResources/drawable/ic_riffle_logo.png \
         feature/design-system/src/commonMain/kotlin/com/riffle/feature/designsystem/RiffleAppIcon.kt
  git commit -m "feat(design-system): add cross-platform RiffleAppIcon using composeResources"
  ```

---

## Task 3: Move `NavigationDrawerComposable.kt` to `feature/library-ui`

**Files:**
- Create: `feature/library-ui/src/commonMain/kotlin/com/riffle/feature/library/ui/NavigationDrawerComposable.kt`
- Modify: `feature/library-ui/build.gradle.kts` (add `:feature:navigation` dependency for `NowPlayingNavigator` — not needed; drawer composable takes callbacks, not VM)
- Delete: `app/src/main/kotlin/com/riffle/app/feature/navigation/NavigationDrawerComposable.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt` (update import)

**Interfaces:**
- Consumes: `Res.string.*` from Task 1, `RiffleAppIcon` from Task 2, `SourceIcon` from `:feature:source-ui`, `KoFiDrawerButton` from `:feature:design-system`
- Produces:
  ```kotlin
  @Composable fun RiffleNavigationDrawer(
      drawerState: DrawerState,
      gesturesEnabled: Boolean = true,
      usePermanentDrawer: Boolean = false,
      hidePermanentDrawerPanel: Boolean = false,
      activeServer: Source?,
      allServers: List<Source>,
      visibleLibraries: List<Library>,
      activeLibraryId: String?,
      serverVersions: Map<String, String>,
      showDownloadsLink: Boolean = true,
      onServerSelected: (Source) -> Unit,
      onLibrarySelected: (Library) -> Unit,
      onDownloadsSelected: () -> Unit,
      onSettingsSelected: () -> Unit,
      onRiffleSelected: () -> Unit = {},
      isRiffleActive: Boolean = false,
      appVersion: String? = null,
      appSha: String? = null,
      content: @Composable () -> Unit,
  )
  ```

- [ ] **Step 1: Create `NavigationDrawerComposable.kt` in `feature/library-ui`**

  Path: `feature/library-ui/src/commonMain/kotlin/com/riffle/feature/library/ui/NavigationDrawerComposable.kt`
  
  This is the content of the existing `app/.../NavigationDrawerComposable.kt` with these changes:
  - Package: `com.riffle.feature.library.ui`
  - Remove `import com.riffle.app.BuildConfig`
  - Remove `import com.riffle.app.R`
  - Remove `import com.riffle.app.ui.theme.RiffleAppIcon`
  - Add `import com.riffle.feature.designsystem.RiffleAppIcon`
  - Add `import com.riffle.feature.library.ui.generated.resources.Res`
  - Add `import com.riffle.feature.library.ui.generated.resources.*`
  - Add `import org.jetbrains.compose.resources.stringResource`
  - Add `appVersion: String? = null` and `appSha: String? = null` parameters to `RiffleNavigationDrawer`
  - Pass `appVersion` and `appSha` down to `DrawerSheetContent`
  - In `DrawerSheetContent`: add `appVersion: String? = null, appSha: String? = null` parameters
  - Replace `androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_downloads)` with `stringResource(Res.string.ui_drawer_downloads)`
  - Replace `com.riffle.app.R.string.ui_settings` with `Res.string.ui_drawer_settings`
  - Replace `com.riffle.app.R.string.ui_active_source` with `Res.string.ui_active_source`
  - Replace `com.riffle.app.R.string.ui_toggle_source_switcher` with `Res.string.ui_toggle_source_switcher`
  - Replace `com.riffle.app.R.string.ui_no_source` with `Res.string.ui_drawer_no_source`
  - In the version footer: replace `BuildConfig.VERSION_NAME` with `appVersion ?: ""` and `BuildConfig.GIT_SHA.takeIf { it.isNotEmpty() }` with `appSha?.takeIf { it.isNotEmpty() }`; wrap the whole `Text` in `if (appVersion != null)` so iOS omits it until the host provides a version string; on Android where `appVersion` is always non-null it always shows.
  
  Full file content:
  ```kotlin
  package com.riffle.feature.library.ui
  
  import androidx.compose.foundation.clickable
  import androidx.compose.foundation.layout.Box
  import androidx.compose.foundation.layout.Column
  import androidx.compose.foundation.layout.Spacer
  import androidx.compose.foundation.layout.fillMaxHeight
  import androidx.compose.foundation.layout.fillMaxWidth
  import androidx.compose.foundation.layout.padding
  import androidx.compose.foundation.layout.size
  import androidx.compose.foundation.layout.width
  import androidx.compose.foundation.rememberScrollState
  import androidx.compose.foundation.verticalScroll
  import androidx.compose.material3.DropdownMenu
  import androidx.compose.material3.DropdownMenuItem
  import androidx.compose.material3.DrawerState
  import androidx.compose.material3.ExperimentalMaterial3Api
  import androidx.compose.material3.HorizontalDivider
  import androidx.compose.material3.Icon
  import androidx.compose.material3.ListItem
  import androidx.compose.material3.LocalTextStyle
  import androidx.compose.material3.MaterialTheme
  import androidx.compose.material3.ModalDrawerSheet
  import androidx.compose.material3.ModalNavigationDrawer
  import androidx.compose.material3.NavigationDrawerItem
  import androidx.compose.material3.PermanentDrawerSheet
  import androidx.compose.material3.PermanentNavigationDrawer
  import androidx.compose.material3.Text
  import androidx.compose.material.icons.Icons
  import androidx.compose.material.icons.filled.Check
  import androidx.compose.material.icons.filled.Download
  import androidx.compose.material.icons.filled.Folder
  import androidx.compose.material.icons.filled.KeyboardArrowDown
  import androidx.compose.material.icons.filled.KeyboardArrowUp
  import androidx.compose.material.icons.filled.Settings
  import androidx.compose.runtime.Composable
  import androidx.compose.runtime.getValue
  import androidx.compose.runtime.mutableStateOf
  import androidx.compose.runtime.remember
  import androidx.compose.runtime.setValue
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.graphics.Color
  import androidx.compose.ui.layout.onSizeChanged
  import androidx.compose.ui.platform.LocalDensity
  import androidx.compose.ui.text.AnnotatedString
  import androidx.compose.ui.text.SpanStyle
  import androidx.compose.ui.text.TextStyle
  import androidx.compose.ui.text.buildAnnotatedString
  import androidx.compose.ui.text.style.TextAlign
  import androidx.compose.ui.text.style.TextOverflow
  import androidx.compose.ui.text.withStyle
  import androidx.compose.ui.unit.Dp
  import androidx.compose.ui.unit.TextUnit
  import androidx.compose.ui.unit.dp
  import androidx.compose.ui.unit.sp
  import com.riffle.core.domain.WebSourceDescriptors
  import com.riffle.core.models.Library
  import com.riffle.core.models.Source
  import com.riffle.core.models.SourceType
  import com.riffle.feature.designsystem.KoFiDrawerButton
  import com.riffle.feature.designsystem.RiffleAppIcon
  import com.riffle.feature.library.shouldShowRiffleSource
  import com.riffle.feature.library.ui.generated.resources.Res
  import com.riffle.feature.library.ui.generated.resources.ui_active_source
  import com.riffle.feature.library.ui.generated.resources.ui_app_version_footer
  import com.riffle.feature.library.ui.generated.resources.ui_drawer_downloads
  import com.riffle.feature.library.ui.generated.resources.ui_drawer_no_source
  import com.riffle.feature.library.ui.generated.resources.ui_drawer_riffle
  import com.riffle.feature.library.ui.generated.resources.ui_drawer_settings
  import com.riffle.feature.library.ui.generated.resources.ui_toggle_source_switcher
  import com.riffle.feature.source.ui.SourceIcon
  import com.riffle.feature.source.ui.localizedSourceDisplayName as localizedDescriptorDisplayName
  import com.riffle.feature.source.ui.localizedSourceSubtitle as localizedDescriptorSubtitle
  import org.jetbrains.compose.resources.stringResource
  
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  fun RiffleNavigationDrawer(
      drawerState: DrawerState,
      gesturesEnabled: Boolean = true,
      usePermanentDrawer: Boolean = false,
      hidePermanentDrawerPanel: Boolean = false,
      activeServer: Source?,
      allServers: List<Source>,
      visibleLibraries: List<Library>,
      activeLibraryId: String?,
      serverVersions: Map<String, String>,
      showDownloadsLink: Boolean = true,
      onServerSelected: (Source) -> Unit,
      onLibrarySelected: (Library) -> Unit,
      onDownloadsSelected: () -> Unit,
      onSettingsSelected: () -> Unit,
      onRiffleSelected: () -> Unit = {},
      isRiffleActive: Boolean = false,
      appVersion: String? = null,
      appSha: String? = null,
      content: @Composable () -> Unit,
  ) {
      val sheetBody: @Composable () -> Unit = {
          DrawerSheetContent(
              activeServer = activeServer,
              allServers = allServers,
              visibleLibraries = visibleLibraries,
              activeLibraryId = activeLibraryId,
              serverVersions = serverVersions,
              showDownloadsLink = showDownloadsLink,
              onServerSelected = onServerSelected,
              onLibrarySelected = onLibrarySelected,
              onDownloadsSelected = onDownloadsSelected,
              onSettingsSelected = onSettingsSelected,
              onRiffleSelected = onRiffleSelected,
              isRiffleActive = isRiffleActive,
              appVersion = appVersion,
              appSha = appSha,
          )
      }
  
      if (usePermanentDrawer) {
          PermanentNavigationDrawer(
              drawerContent = {
                  if (!hidePermanentDrawerPanel) {
                      PermanentDrawerSheet(modifier = Modifier.width(280.dp)) { sheetBody() }
                  }
              },
              content = content,
          )
      } else {
          ModalNavigationDrawer(
              drawerState = drawerState,
              gesturesEnabled = gesturesEnabled,
              drawerContent = {
                  ModalDrawerSheet(modifier = Modifier.width(280.dp)) { sheetBody() }
              },
              content = content,
          )
      }
  }
  
  @Composable
  private fun DrawerSheetContent(
      activeServer: Source?,
      allServers: List<Source>,
      visibleLibraries: List<Library>,
      activeLibraryId: String?,
      serverVersions: Map<String, String>,
      showDownloadsLink: Boolean,
      onServerSelected: (Source) -> Unit,
      onLibrarySelected: (Library) -> Unit,
      onDownloadsSelected: () -> Unit,
      onSettingsSelected: () -> Unit,
      onRiffleSelected: () -> Unit = {},
      isRiffleActive: Boolean = false,
      appVersion: String? = null,
      appSha: String? = null,
  ) {
      Column(modifier = Modifier.fillMaxHeight()) {
          DrawerHeader(
              activeServer = activeServer,
              allServers = allServers,
              serverVersions = serverVersions,
              onServerSelected = onServerSelected,
              isRiffleActive = isRiffleActive,
              onRiffleSelected = onRiffleSelected,
          )
          Column(
              modifier = Modifier
                  .weight(1f)
                  .verticalScroll(rememberScrollState()),
          ) {
              if (!isRiffleActive) {
                  visibleLibraries.forEach { library ->
                      NavigationDrawerItem(
                          label = { Text(library.name) },
                          selected = library.id == activeLibraryId,
                          onClick = { onLibrarySelected(library) },
                      )
                  }
              }
          }
          HorizontalDivider()
          if (showDownloadsLink) {
              NavigationDrawerItem(
                  label = { Text(stringResource(Res.string.ui_drawer_downloads)) },
                  icon = { Icon(Icons.Default.Download, contentDescription = null) },
                  selected = false,
                  onClick = onDownloadsSelected,
              )
          }
          NavigationDrawerItem(
              label = { Text(stringResource(Res.string.ui_drawer_settings)) },
              icon = { Icon(Icons.Default.Settings, contentDescription = null) },
              selected = false,
              onClick = onSettingsSelected,
          )
          KoFiDrawerButton()
          if (appVersion != null) {
              val sha = appSha?.takeIf { it.isNotEmpty() }
              Text(
                  text = stringResource(
                      Res.string.ui_app_version_footer,
                      appVersion,
                      sha?.let { " ($it)" } ?: "",
                  ),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                  modifier = Modifier
                      .fillMaxWidth()
                      .padding(bottom = 12.dp, top = 4.dp),
                  textAlign = TextAlign.Center,
              )
          }
      }
  }
  
  @Composable
  private fun DrawerHeader(
      activeServer: Source?,
      allServers: List<Source>,
      serverVersions: Map<String, String>,
      onServerSelected: (Source) -> Unit,
      isRiffleActive: Boolean = false,
      onRiffleSelected: () -> Unit = {},
  ) {
      val activeVersion = activeServer?.id?.let { serverVersions[it] }
      var switcherExpanded by remember { mutableStateOf(false) }
      var headerWidth by remember { mutableStateOf(Dp.Unspecified) }
      val density = LocalDensity.current
  
      Box(modifier = Modifier
          .fillMaxWidth()
          .onSizeChanged { headerWidth = with(density) { it.width.toDp() } }
      ) {
          ListItem(
              leadingContent = if (isRiffleActive) {
                  { RiffleAppIcon(size = 24.dp) }
              } else {
                  activeServer?.let { server -> { SourceRowIcon(server = server) } }
              },
              headlineContent = {
                  if (isRiffleActive) {
                      AutoShrinkingSingleLineText(text = stringResource(Res.string.ui_drawer_riffle))
                  } else {
                      val name = activeServer?.let { localizedSourceDisplayName(it) }
                          ?: stringResource(Res.string.ui_drawer_no_source)
                      val username = activeServer
                          ?.takeIf { WebSourceDescriptors.forType(it.type)?.hasCredentials == true }
                          ?.username?.takeIf { it.isNotEmpty() }
                      if (username != null) {
                          AutoShrinkingSingleLineText(
                              text = buildAnnotatedString {
                                  append(name)
                                  append(" ")
                                  withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                      append("[$username]")
                                  }
                              },
                          )
                      } else {
                          AutoShrinkingSingleLineText(text = name)
                      }
                  }
              },
              supportingContent = if (isRiffleActive) null else {
                  {
                      val support = activeServer?.let {
                          localizedSourceSwitcherSubtitle(source = it, version = activeVersion)
                      }
                      if (support != null) {
                          AutoShrinkingSingleLineText(
                              text = support,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                          )
                      }
                  }
              },
              trailingContent = {
                  Icon(
                      imageVector = if (switcherExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                      contentDescription = stringResource(Res.string.ui_toggle_source_switcher),
                  )
              },
              modifier = Modifier.clickable { switcherExpanded = !switcherExpanded },
          )
          DropdownMenu(
              expanded = switcherExpanded,
              onDismissRequest = { switcherExpanded = false },
              modifier = if (headerWidth != Dp.Unspecified) Modifier.width(headerWidth) else Modifier,
          ) {
              if (shouldShowRiffleSource(allServers.size)) {
                  DropdownMenuItem(
                      text = { AutoShrinkingSingleLineText(text = stringResource(Res.string.ui_drawer_riffle)) },
                      leadingIcon = { RiffleAppIcon(size = 24.dp) },
                      trailingIcon = {
                          if (isRiffleActive) {
                              Icon(Icons.Default.Check, contentDescription = stringResource(Res.string.ui_active_source))
                          } else {
                              Spacer(modifier = Modifier.size(24.dp))
                          }
                      },
                      onClick = {
                          switcherExpanded = false
                          onRiffleSelected()
                      },
                  )
                  HorizontalDivider()
              }
              allServers.forEach { server ->
                  DropdownMenuItem(
                      text = {
                          Column {
                              val displayName = localizedSourceDisplayName(server)
                              val username = server
                                  .takeIf { WebSourceDescriptors.forType(it.type)?.hasCredentials == true }
                                  ?.username?.takeIf { it.isNotEmpty() }
                              if (username != null) {
                                  AutoShrinkingSingleLineText(
                                      text = buildAnnotatedString {
                                          append(displayName)
                                          append(" ")
                                          withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                              append("[$username]")
                                          }
                                      },
                                  )
                              } else {
                                  AutoShrinkingSingleLineText(text = displayName)
                              }
                              val support = localizedSourceSwitcherSubtitle(
                                  source = server,
                                  version = serverVersions[server.id],
                              )
                              if (support != null) {
                                  AutoShrinkingSingleLineText(
                                      text = support,
                                      style = MaterialTheme.typography.bodySmall,
                                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                                  )
                              }
                          }
                      },
                      leadingIcon = { SourceRowIcon(server = server) },
                      trailingIcon = {
                          if (server.isActive && !isRiffleActive) {
                              Icon(Icons.Default.Check, contentDescription = stringResource(Res.string.ui_active_source))
                          } else {
                              Spacer(modifier = Modifier.size(24.dp))
                          }
                      },
                      onClick = {
                          switcherExpanded = false
                          onServerSelected(server)
                      },
                  )
              }
          }
      }
  }
  
  @Composable
  private fun AutoShrinkingSingleLineText(
      text: String,
      color: Color = Color.Unspecified,
      style: TextStyle = LocalTextStyle.current,
      minFontSize: TextUnit = 12.sp,
  ) {
      AutoShrinkingSingleLineText(
          text = AnnotatedString(text),
          color = color,
          style = style,
          minFontSize = minFontSize,
      )
  }
  
  @Composable
  private fun AutoShrinkingSingleLineText(
      text: AnnotatedString,
      color: Color = Color.Unspecified,
      style: TextStyle = LocalTextStyle.current,
      minFontSize: TextUnit = 12.sp,
  ) {
      var resizedStyle by remember(text, style, minFontSize) { mutableStateOf(style) }
      Text(
          text = text,
          color = color,
          style = resizedStyle,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          onTextLayout = { result ->
              val nextSize = nextOverflowFontSize(
                  currentSize = resizedStyle.fontSize,
                  minFontSize = minFontSize,
                  hasVisualOverflow = result.hasVisualOverflow,
              )
              if (nextSize != null) {
                  resizedStyle = resizedStyle.copy(fontSize = nextSize)
              }
          },
      )
  }
  
  fun nextOverflowFontSize(
      currentSize: TextUnit,
      minFontSize: TextUnit,
      hasVisualOverflow: Boolean,
  ): TextUnit? {
      if (!hasVisualOverflow || currentSize == TextUnit.Unspecified) return null
      if (currentSize.value <= minFontSize.value) return null
      val nextValue = maxOf(minFontSize.value, currentSize.value * 0.9f)
      return nextValue.sp.takeIf { it != currentSize }
  }
  
  @Composable
  private fun SourceRowIcon(server: Source) {
      if (server.type == SourceType.LOCAL_FILES) {
          Icon(
              imageVector = Icons.Default.Folder,
              contentDescription = null,
              modifier = Modifier.size(24.dp),
          )
      } else {
          SourceIcon(source = server, size = 24.dp)
      }
  }
  
  fun buildSupportingLine(host: String?, version: String?): String? {
      val v = version?.let { "v$it" }
      return when {
          host != null && v != null -> "$host · $v"
          host != null -> host
          v != null -> v
          else -> null
      }
  }
  
  fun sourceDisplayName(source: Source): String =
      if (source.type == SourceType.ABS) source.serverType.label
      else WebSourceDescriptors.forTypeOrError(source.type).displayName
  
  @Composable
  private fun localizedSourceDisplayName(source: Source): String =
      if (source.type == SourceType.ABS) source.serverType.label
      else localizedDescriptorDisplayName(WebSourceDescriptors.forTypeOrError(source.type))
  
  fun sourceSwitcherSubtitle(source: Source, version: String?): String? {
      val descriptor = WebSourceDescriptors.forType(source.type) ?: return null
      return if (descriptor.hasNetworkHost) {
          buildSupportingLine(source.url.authority(), version)
      } else {
          descriptor.subtitle
      }
  }
  
  @Composable
  private fun localizedSourceSwitcherSubtitle(source: Source, version: String?): String? {
      val descriptor = WebSourceDescriptors.forType(source.type) ?: return null
      return if (descriptor.hasNetworkHost) {
          buildSupportingLine(source.url.authority(), version)
      } else {
          localizedDescriptorSubtitle(descriptor)
      }
  }
  ```

- [ ] **Step 2: Update `app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt`**

  Change import from:
  ```kotlin
  import com.riffle.app.feature.navigation.RiffleNavigationDrawer
  ```
  To:
  ```kotlin
  import com.riffle.feature.library.ui.RiffleNavigationDrawer
  ```
  
  In the `RiffleNavigationDrawer` call (around line 176), add two parameters:
  ```kotlin
  appVersion = BuildConfig.VERSION_NAME,
  appSha = BuildConfig.GIT_SHA,
  ```

- [ ] **Step 3: Delete the old `NavigationDrawerComposable.kt` from `:app`**

  ```bash
  git rm app/src/main/kotlin/com/riffle/app/feature/navigation/NavigationDrawerComposable.kt
  ```
  
  Note: `NavigationDrawerSourceSubtitleTest` imports `buildSupportingLine`, `sourceSwitcherSubtitle`, `sourceDisplayName` from the old package. Do NOT delete it yet — that migration happens in Task 5.

- [ ] **Step 4: Run JVM tests and compileAndroidTestKotlin to check for import errors**

  ```bash
  export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
  ./gradlew :feature:library-ui:jvmTest \
            :app:compileDebugAndroidTestKotlin \
            2>&1 | tail -30
  ```
  Expected: SUCCESSFUL. If import errors on `NavigationDrawerSourceSubtitleTest`, that test will be migrated in Task 5 — for now, update its import to `com.riffle.feature.library.ui.*`.

- [ ] **Step 5: Commit**

  ```bash
  git add feature/library-ui/src/commonMain/kotlin/com/riffle/feature/library/ui/NavigationDrawerComposable.kt \
         app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt
  git commit -m "feat(library-ui): lift NavigationDrawerComposable from :app — shared drawer for Android + iOS"
  ```

---

## Task 4: Wire iOS to use `NavigationDrawerViewModel` and shared drawer

**Files:**
- Modify: `shared/build.gradle.kts`
- Modify: `shared/src/iosMain/kotlin/com/riffle/shared/Koin.kt`
- Delete: `shared/src/commonMain/kotlin/com/riffle/shared/DrawerViewModel.kt`
- Delete: `shared/src/commonTest/kotlin/com/riffle/shared/DrawerViewModelTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/riffle/shared/HomeScreen.kt`

**Interfaces:**
- Consumes: `NavigationDrawerViewModel` from `:feature:navigation`, `RiffleNavigationDrawer` from Task 3
- Produces: `HomeScreen()` using shared drawer and `NavigationDrawerViewModel`

- [ ] **Step 1: Add `:feature:navigation` to `shared/build.gradle.kts`**

  In `sourceSets { commonMain.dependencies { ... } }`, add:
  ```kotlin
  implementation(project(":feature:navigation"))
  ```

- [ ] **Step 2: Register `NavigationDrawerViewModel` and `NowPlayingNavigator` in `shared/src/iosMain/kotlin/com/riffle/shared/Koin.kt`**

  Find the line:
  ```kotlin
  single { DrawerViewModel(get(), get(), get(), get()) }
  ```
  Replace with:
  ```kotlin
  single { com.riffle.feature.navigation.NowPlayingNavigator() }
  single {
      com.riffle.feature.navigation.NavigationDrawerViewModel(
          sourceRepository = get(),
          libraryObserver = get(),
          visibilityStore = get(),
          orderStore = get(),
          lastOpenedLibraryStore = get(),
          connectivityObserver = get(),
          catalogRegistry = get(),
          nowPlayingNavigator = get(),
          nowPlayingStore = get(),
      )
  }
  ```
  
  Add to the imports at the top of `Koin.kt`:
  ```kotlin
  import com.riffle.feature.navigation.NavigationDrawerViewModel
  import com.riffle.feature.navigation.NowPlayingNavigator
  ```

- [ ] **Step 3: Delete `DrawerViewModel.kt`**

  ```bash
  git rm shared/src/commonMain/kotlin/com/riffle/shared/DrawerViewModel.kt
  ```

- [ ] **Step 4: Delete `DrawerViewModelTest.kt`** (with `Removed-test:` trailers)

  Check test names first:
  ```bash
  grep "fun test\|@Test" shared/src/commonTest/kotlin/com/riffle/shared/DrawerViewModelTest.kt
  ```
  
  Then:
  ```bash
  git rm shared/src/commonTest/kotlin/com/riffle/shared/DrawerViewModelTest.kt
  ```
  
  Behavior is covered by `NavigationDrawerViewModelTest` (already in `feature/navigation/src/commonTest/`). The commit message below must list every deleted `@Test` function name.

- [ ] **Step 5: Update `shared/src/commonMain/kotlin/com/riffle/shared/HomeScreen.kt`**

  1. Change `koinInject<DrawerViewModel>()` → `koinInject<NavigationDrawerViewModel>()`
  2. Add imports:
     ```kotlin
     import com.riffle.feature.navigation.NavigationDrawerViewModel
     import com.riffle.feature.library.ui.RiffleNavigationDrawer
     ```
  3. Remove `import com.riffle.shared.DrawerViewModel` and all other references to `DrawerViewModel`
  4. Update `collectAsState()` calls to use `NavigationDrawerViewModel` fields (field names are identical: `allServers`, `activeServer`, `visibleLibraries`, `isRiffleMode`, `showDownloadsLink`, `serverVersions`)
  5. Add `val isRiffleMode by drawerViewModel.isRiffleMode.collectAsState()`
  6. Add `val showDownloadsLink by drawerViewModel.showDownloadsLink.collectAsState()`
  7. Add `val serverVersions by drawerViewModel.serverVersions.collectAsState()`
  8. Delete the 113-line `DrawerSheetContent` function (lines 199–313 in the original)
  9. Replace the `ModalNavigationDrawer { ... }` block with `RiffleNavigationDrawer` call:
  
  ```kotlin
  RiffleNavigationDrawer(
      drawerState = drawerState,
      gesturesEnabled = drawerEnabled,
      activeServer = activeServer,
      allServers = allServers,
      visibleLibraries = visibleLibraries,
      activeLibraryId = activeLibraryId,
      serverVersions = serverVersions,
      showDownloadsLink = showDownloadsLink,
      isRiffleActive = appSection == AppSection.Riffle || isRiffleMode,
      onRiffleSelected = {
          scope.launch { drawerState.close() }
          drawerViewModel.setRiffleActive()
          appSection = AppSection.Riffle
      },
      onServerSelected = { source ->
          scope.launch { drawerState.close() }
          appSection = AppSection.Library
          drawerViewModel.setActiveServer(source.id)
          scope.launch {
              withTimeoutOrNull(5_000) {
                  drawerViewModel.activeServer.first { it?.id == source.id }
              }
              refreshKey++
          }
      },
      onLibrarySelected = { library ->
          scope.launch { drawerState.close() }
          activeLibraryId = library.id
          drawerViewModel.setActiveLibrary(library.id)
          destination = HomeViewModel.StartDestination.Library(
              sourceType = activeServer?.type ?: return@RiffleNavigationDrawer,
              libraryId = library.id,
              libraryName = library.name,
          )
      },
      onSettingsSelected = {
          scope.launch { drawerState.close() }
          appSection = AppSection.Settings
      },
      onDownloadsSelected = {
          scope.launch { drawerState.close() }
          appSection = AppSection.Downloads
      },
  ) {
      // ... existing when(appSection) body unchanged
  }
  ```

  10. Fix shell states:
      - `Text("Loading…")` → `CircularProgressIndicator()`
      - `HomeViewModel.StartDestination.NoLibraries` → add retry state:
      ```kotlin
      is HomeViewModel.StartDestination.NoLibraries -> {
          LaunchedEffect(Unit) { showRetry = true }
      }
      ```
      Add `var showRetry by remember { mutableStateOf(false) }` and show the same error+Retry UI as Android's `HomeScreen.kt`:
      ```kotlin
      if (showRetry) {
          Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center,
          ) {
              Text(
                  text = stringResource(Res.string.ui_unable_to_connect_to_source),
                  style = MaterialTheme.typography.bodyLarge,
              )
              Spacer(modifier = Modifier.height(16.dp))
              Button(onClick = { refreshKey++; showRetry = false }) {
                  Text(stringResource(Res.string.ui_retry))
              }
          }
      } else {
          CircularProgressIndicator()
      }
      ```

- [ ] **Step 6: Check that `shared` module compiles**

  ```bash
  export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
  ./gradlew :shared:iosSimulatorArm64Test 2>&1 | tail -30
  ```
  Expected: SUCCESSFUL.

- [ ] **Step 7: Commit with Removed-test trailers**

  List all deleted test functions, then:
  ```bash
  git add shared/build.gradle.kts \
         shared/src/iosMain/kotlin/com/riffle/shared/Koin.kt \
         shared/src/commonMain/kotlin/com/riffle/shared/HomeScreen.kt
  git commit -m "$(cat <<'EOF'
  feat(ios): replace DrawerViewModel with NavigationDrawerViewModel, use shared RiffleNavigationDrawer
  
  Removed-test: visibleLibrariesFiltersHiddenAndReadaloudLibraries
  Removed-test: visibleLibrariesIsEmptyWhenNoActiveServer
  Removed-test: setActiveLibraryPersistsToLastOpenedLibraryStore
  Removed-test: redirectToLibraryEmitsWhenActiveLibraryHidden
  EOF
  )"
  ```
  (Replace the `Removed-test:` lines with the actual test function names found in Step 4.)

---

## Task 5: Migrate `NavigationDrawerSourceSubtitleTest` to `feature/library-ui` commonTest

**Files:**
- Create: `feature/library-ui/src/commonTest/kotlin/com/riffle/feature/library/ui/NavigationDrawerSubtitleTest.kt`
- Delete: `app/src/test/kotlin/com/riffle/app/feature/navigation/NavigationDrawerSourceSubtitleTest.kt`

**Interfaces:**
- Consumes: `buildSupportingLine`, `sourceSwitcherSubtitle`, `sourceDisplayName` from `com.riffle.feature.library.ui`

- [ ] **Step 1: Write the failing test in `feature/library-ui/commonTest`**

  Create `feature/library-ui/src/commonTest/kotlin/com/riffle/feature/library/ui/NavigationDrawerSubtitleTest.kt`:
  ```kotlin
  package com.riffle.feature.library.ui
  
  import com.riffle.core.domain.WebSourceDescriptors
  import com.riffle.core.models.Source
  import com.riffle.core.models.SourceType
  import com.riffle.core.models.SourceUrl
  import kotlin.test.Test
  import kotlin.test.assertEquals
  import kotlin.test.assertNull
  
  class NavigationDrawerSubtitleTest {
  
      private fun source(type: SourceType, url: String) = Source(
          id = "test",
          url = SourceUrl.parse(url)!!,
          isActive = false,
          insecureConnectionAllowed = false,
          username = "",
          serverType = com.riffle.core.models.ServerType.AUDIOBOOKSHELF,
      )
  
      @Test
      fun komgaSwitcherSubtitleShowsConfiguredAddress() {
          val s = source(SourceType.KOMGA, "https://komga.example.com/books")
          assertEquals("komga.example.com", sourceSwitcherSubtitle(s, version = null))
      }
  
      @Test
      fun radioEsSwitcherSubtitleShowsStaticDescriptorSubtitle() {
          val s = source(SourceType.RADIO_ES, "https://radio-es.invalid")
          assertEquals("Podcasts & radio", sourceSwitcherSubtitle(s, version = null))
      }
  
      @Test
      fun everyNonNetworkHostSourceWithDescriptorSubtitleShowsItInSwitcher() {
          WebSourceDescriptors.all
              .filter { !it.hasNetworkHost && it.subtitle != null }
              .forEach { descriptor ->
                  val s = source(
                      type = descriptor.type,
                      url = descriptor.urlPlaceholder ?: "https://${descriptor.type.name.lowercase()}.invalid",
                  )
                  assertEquals(
                      descriptor.subtitle,
                      sourceSwitcherSubtitle(s, version = null),
                      "${descriptor.type} must show its static subtitle",
                  )
              }
      }
  
      @Test
      fun everyNetworkHostSwitcherSubtitleIncludesConfiguredAddress() {
          WebSourceDescriptors.all
              .filter { it.hasNetworkHost }
              .forEach { descriptor ->
                  val s = source(
                      type = descriptor.type,
                      url = "https://${descriptor.type.name.lowercase()}.example.com/root",
                  )
                  val subtitle = sourceSwitcherSubtitle(s, version = null)
                  assertTrue(
                      subtitle?.contains(".example.com") == true,
                      "${descriptor.type}: subtitle '$subtitle' must contain the configured host",
                  )
              }
      }
  
      @Test
      fun versionAppendsAfterHostWithSeparator() {
          val s = source(SourceType.ABS, "https://abs.example.com")
          assertEquals("abs.example.com · v24.1.0", sourceSwitcherSubtitle(s, version = "24.1.0"))
      }
  
      @Test
      fun versionAloneWhenNoHost() {
          val s = source(SourceType.RADIO_ES, "https://radio-es.invalid")
          // RADIO_ES has no network host, returns static subtitle regardless of version
          assertEquals("Podcasts & radio", sourceSwitcherSubtitle(s, version = "1.0"))
      }
  
      @Test
      fun buildSupportingLineHostAndVersionReturnsHostDotVersion() {
          assertEquals("host.example · v1.2", buildSupportingLine("host.example", "1.2"))
      }
  
      @Test
      fun buildSupportingLineHostOnlyReturnsHost() {
          assertEquals("host.example", buildSupportingLine("host.example", null))
      }
  
      @Test
      fun buildSupportingLineNullBothReturnsNull() {
          assertNull(buildSupportingLine(null, null))
      }
  
      @Test
      fun nextOverflowFontSizeReturnsNullWhenNoOverflow() {
          assertNull(nextOverflowFontSize(currentSize = 16.sp, minFontSize = 12.sp, hasVisualOverflow = false))
      }
  
      @Test
      fun nextOverflowFontSizeReturnsNullAtMinSize() {
          assertNull(nextOverflowFontSize(currentSize = 12.sp, minFontSize = 12.sp, hasVisualOverflow = true))
      }
  
      @Test
      fun nextOverflowFontSizeShrinks90Percent() {
          val result = nextOverflowFontSize(currentSize = 16.sp, minFontSize = 12.sp, hasVisualOverflow = true)
          assertEquals(14.4f, result?.value ?: 0f, absoluteTolerance = 0.01f)
      }
  }
  ```
  
  Note: `assertTrue` is from `kotlin.test`; `12.sp` requires `import androidx.compose.ui.unit.sp` — add it if the test file is JVM-only. Since these are pure-logic tests (no Compose runtime), they run on both JVM and iOS via `iosSimulatorArm64Test`.

- [ ] **Step 2: Run the new test to confirm it passes**

  ```bash
  export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
  ./gradlew :feature:library-ui:jvmTest --tests "com.riffle.feature.library.ui.NavigationDrawerSubtitleTest" 2>&1 | tail -20
  ```
  Expected: all pass. If `12.sp` / `TextUnit` is unavailable on JVM test path (Compose UI dependency missing in test compile), move the `nextOverflowFontSize` tests to a separate file and mark them with `@Ignore` on JVM with a comment linking to the iOS run.

- [ ] **Step 3: Delete the old Android-specific test**

  ```bash
  git rm app/src/test/kotlin/com/riffle/app/feature/navigation/NavigationDrawerSourceSubtitleTest.kt
  ```

- [ ] **Step 4: Commit with Removed-test trailers**

  ```bash
  git commit -m "$(cat <<'EOF'
  test(library-ui): port NavigationDrawerSourceSubtitleTest to commonTest
  
  Behaviour is now exercised on both JVM and iOS via :feature:library-ui:iosSimulatorArm64Test.
  Removed-test: Komga switcher subtitle shows configured address
  Removed-test: radio_es source switcher subtitle shows static descriptor subtitle
  Removed-test: every non-network-host source with a descriptor subtitle shows it in the switcher
  Removed-test: every network-host source switcher subtitle includes configured address
  Removed-test: version is appended after the host with separator
  Removed-test: buildSupportingLine host only
  Removed-test: buildSupportingLine host and version
  Removed-test: buildSupportingLine null both returns null
  Removed-test: nextOverflowFontSize returns null when no overflow
  EOF
  )"
  ```

---

## Task 6: Real back stack in iOS `LibraryHost`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/riffle/shared/HomeScreen.kt`

**Interfaces:**
- Changes `LibraryHost` internal implementation only; external signature unchanged.

- [ ] **Step 1: Replace single `nav` with `navStack` list**

  In `LibraryHost`, find:
  ```kotlin
  var nav by rememberSaveable { mutableStateOf<LibraryNav>(LibraryNav.Items) }
  ```
  Replace with:
  ```kotlin
  var navStack by remember { mutableStateOf(listOf<LibraryNav>(LibraryNav.Items)) }
  val nav = navStack.last()
  ```
  
  Note: `remember` (not `rememberSaveable`) avoids serialization issues with `LibraryNav.ReaderDestination(LibraryItem)`.

- [ ] **Step 2: Replace `nav = LibraryNav.X` with push/pop helper pattern**

  Define at the top of `LibraryHost`:
  ```kotlin
  fun push(dest: LibraryNav) { navStack = navStack + dest }
  fun pop() { if (navStack.size > 1) navStack = navStack.dropLast(1) }
  ```
  
  Replace every `nav = LibraryNav.Items` with `pop()`.
  Replace every other `nav = LibraryNav.SomeDestination(...)` with `push(LibraryNav.SomeDestination(...))`.
  
  Full replacement mapping:
  - `onBack = { nav = LibraryNav.Items }` → `onBack = { pop() }`
  - `onNavigateBack = { nav = LibraryNav.Items }` → `onNavigateBack = { pop() }`
  - `nav = LibraryNav.ItemDetail(...)` → `push(LibraryNav.ItemDetail(...))`
  - `nav = LibraryNav.SeriesDetail(...)` → `push(LibraryNav.SeriesDetail(...))`
  - `nav = LibraryNav.CollectionDetail(...)` → `push(LibraryNav.CollectionDetail(...))`
  - `nav = LibraryNav.Section(...)` → `push(LibraryNav.Section(...))`
  - `nav = LibraryNav.FilteredBooks(...)` → `push(LibraryNav.FilteredBooks(...))`
  - `nav = LibraryNav.AnnotationSearch(...)` → `push(LibraryNav.AnnotationSearch(...))`
  - `nav = LibraryNav.PlaylistDetail(...)` → `push(LibraryNav.PlaylistDetail(...))`
  - `nav = playlistPlayerNav(...)` → `push(playlistPlayerNav(...))`
  - `nav = playlistAdvanceNav(...)` → `push(playlistAdvanceNav(...))`
  - Reader `onBack`: pop from Items back to whatever called it — `pop()`
  - `openItemForReading(...)?.let { nav = it }` → `openItemForReading(...)?.let { push(it) }`

- [ ] **Step 3: Verify `:shared:iosSimulatorArm64Test` passes**

  ```bash
  export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
  ./gradlew :shared:iosSimulatorArm64Test 2>&1 | tail -20
  ```
  Expected: SUCCESSFUL.

- [ ] **Step 4: Commit**

  ```bash
  git add shared/src/commonMain/kotlin/com/riffle/shared/HomeScreen.kt
  git commit -m "fix(ios): real back stack in LibraryHost — Detail→Reader→back returns to Detail not Items"
  ```

---

## Task 7: Tablet layout for iOS drawer

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/riffle/shared/HomeScreen.kt`

**Interfaces:**
- Consumes: `LocalWindowAdaptiveInfo`, `WindowWidthSizeClass` from `androidx.compose.material3.adaptive`

- [ ] **Step 1: Derive `usePermanentDrawer` from window size class in `HomeScreen`**

  Add at the top of `HomeScreen()` composable body:
  ```kotlin
  import androidx.compose.material3.adaptive.LocalWindowAdaptiveInfo
  import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
  import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
  
  val windowInfo = LocalWindowAdaptiveInfo.current
  val usePermanentDrawer = windowInfo.windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.Expanded
  ```
  
  Then in the `RiffleNavigationDrawer` call:
  ```kotlin
  usePermanentDrawer = usePermanentDrawer,
  hidePermanentDrawerPanel = usePermanentDrawer && navStack.lastOrNull() is LibraryNav.ReaderDestination,
  ```

- [ ] **Step 2: Check imports — use whatever adaptive API the app already uses**

  ```bash
  grep -rn "LocalWindowAdaptiveInfo\|WindowWidthSizeClass" app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt | head -5
  ```
  
  Mirror the same import path used in Android's `MainScreen.kt` to keep the two consistent.

- [ ] **Step 3: Verify compile**

  ```bash
  export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
  ./gradlew :shared:iosSimulatorArm64Test 2>&1 | tail -10
  ```
  Expected: SUCCESSFUL.

- [ ] **Step 4: Commit**

  ```bash
  git add shared/src/commonMain/kotlin/com/riffle/shared/HomeScreen.kt
  git commit -m "feat(ios): permanent drawer for Expanded width class (tablet layout ADR 0019)"
  ```

---

## Task 8: Full test suite — compile checks and iOS unit tests

**Files:**
- Modify: `iosApp/iosAppTests/NavDrawerTests.swift`

**Interfaces:**
- Exercises the real drawer through `XCUIApplication`; uses `StubAbsServer` harness infrastructure already in `iosAppTests`.

- [ ] **Step 1: Update `NavDrawerTests.swift` for new dropdown-style switcher (ND-4/ND-5)**

  `testDrawerHeaderShowsTheSourceHostBeneathItsName` (ND-4) looks for the host as a `staticText`. The new drawer uses `ListItem.supportingContent`, which also renders as `staticText`. Verify the test still passes with the new composable; if the accessibility label changes, update the query but keep the assertion the same.
  
  `testSourceSwitcherStartsCollapsedAndExpandsOnTap` (ND-5) currently looks for `▼ Switch source` text. The new dropdown-based drawer uses an arrow icon (no text label). Update this test:
  ```swift
  func testSourceSwitcherDropdownExpandsOnHeaderTap() throws {
      let burger = app.buttons["Open menu"]
      XCTAssertTrue(burger.waitForExistence(timeout: 10))
      burger.tap()
  
      // The source header is the ListItem with the source name + arrow icon.
      let header = app.staticTexts["Audiobookshelf"].firstMatch
      XCTAssertTrue(header.waitForExistence(timeout: 10), "Drawer header must show the source name")
  
      // Before tapping: the dropdown is not visible.
      XCTAssertFalse(app.otherElements["DropdownMenuPopup"].exists, "Switcher must start collapsed")
  
      header.tap()
  
      // After tapping: a DropdownMenu item for the source appears.
      XCTAssertTrue(
          app.staticTexts["Audiobookshelf"].waitForExistence(timeout: 10),
          "Tapping the header must expand the source switcher"
      )
  }
  ```
  
  Remove the old `testSourceSwitcherStartsCollapsedAndExpandsOnTap` and add `Removed-test:` trailer in this commit.

- [ ] **Step 2: Add ND-8 — version footer is present**

  ```swift
  func testDrawerFooterShowsVersionString() throws {
      let burger = app.buttons["Open menu"]
      XCTAssertTrue(burger.waitForExistence(timeout: 10))
      burger.tap()
  
      // The version footer text begins with "Riffle v".
      let footer = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Riffle v'")).firstMatch
      XCTAssertTrue(
          footer.waitForExistence(timeout: 10),
          "Drawer footer must show 'Riffle v<version>' string"
      )
  }
  ```
  
  Note: iOS does not pass `appVersion` in the first PR iteration (the parameter defaults to `nil` and the footer is hidden). Add this test as `XCTSkip`-free but expect it to fail until a follow-up wires the iOS version string. Mark with `// TODO(#1143): enable once iOS HomeScreen passes appVersion`.
  
  Actually, to avoid a failing test: make iOS `HomeScreen.kt` pass a version string. Retrieve via:
  ```kotlin
  // In HomeScreen.kt, at the top level (outside Composable)
  private fun iosAppVersion(): String? = runCatching {
      platform.Foundation.NSBundle.mainBundle.infoDictionary
          ?.get("CFBundleShortVersionString") as? String
  }.getOrNull()
  ```
  Then pass `appVersion = iosAppVersion()` to `RiffleNavigationDrawer`.

- [ ] **Step 3: Run the full Android compile check**

  ```bash
  export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
  ./gradlew test jvmTest riffleChecks \
            :app:compileDebugAndroidTestKotlin \
            :core:data:compileAndroidHostTest \
            2>&1 | tail -30
  ```
  Expected: SUCCESSFUL.

- [ ] **Step 4: Run `feature/library-ui` iOS tests**

  ```bash
  export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
  ./gradlew :feature:library-ui:iosSimulatorArm64Test 2>&1 | tail -20
  ```
  Expected: SUCCESSFUL — `NavigationDrawerSubtitleTest` runs on iOS.

- [ ] **Step 5: Commit**

  ```bash
  git add iosApp/iosAppTests/NavDrawerTests.swift \
         shared/src/commonMain/kotlin/com/riffle/shared/HomeScreen.kt
  git commit -m "$(cat <<'EOF'
  test(ios): update NavDrawerTests for shared drawer — ND-4 host subtitle, ND-5 dropdown, ND-8 footer
  
  Removed-test: testSourceSwitcherStartsCollapsedAndExpandsOnTap
  EOF
  )"
  ```

---

## Task 9: Final validation — all checks green

- [ ] **Step 1: Full JVM + riffleChecks suite**

  ```bash
  export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
  ./gradlew test jvmTest riffleChecks 2>&1 | tail -30
  ```
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Android compile targets**

  ```bash
  ./gradlew :app:compileDebugAndroidTestKotlin \
            :core:data:compileAndroidHostTest \
            2>&1 | tail -20
  ```
  Expected: SUCCESSFUL.

- [ ] **Step 3: iOS `shared` and `feature/library-ui` simulator tests**

  ```bash
  ./gradlew :shared:iosSimulatorArm64Test \
            :feature:library-ui:iosSimulatorArm64Test \
            :feature:navigation:iosSimulatorArm64Test \
            2>&1 | tail -20
  ```
  Expected: SUCCESSFUL.

- [ ] **Step 4: iOS XCTest unit suite**

  ```bash
  xcodebuild test \
    -scheme iosApp \
    -destination 'platform=iOS Simulator,name=iPhone 16' \
    -only-testing:iosAppUnitTests \
    2>&1 | tail -20
  ```
  Expected: all pass.

- [ ] **Step 5: iOS XCTest UI suite (drawer flows)**

  ```bash
  xcodebuild test \
    -scheme iosApp \
    -destination 'platform=iOS Simulator,name=iPhone 16' \
    -only-testing:iosAppTests/NavDrawerTests \
    2>&1 | tail -30
  ```
  Expected: all pass.

- [ ] **Step 6: Android harness**

  ```bash
  make harness-test 2>&1 | tail -30
  ```
  Expected: all pass.

- [ ] **Step 7: Translation check**

  ```bash
  ./gradlew checkTranslations 2>&1 | tail -10
  ```
  Expected: no violations — `feature/library-ui/src/commonMain/composeResources` has `values-es` and `values-bg` matching `values`.
