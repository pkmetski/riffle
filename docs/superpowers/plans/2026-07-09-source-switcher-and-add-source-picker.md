# Source Switcher, Add Source flow, Source Type picker — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Issue:** [#435](https://github.com/pkmetski/riffle/issues/435) — Phase 3 of the ADR 0041 Source/Service re-root.

**Goal:** Rename user-facing surfaces from **Server** to **Source** in the Nav Drawer, Add flow, and Settings, and insert a **Source Type picker** as the first step of Add-Source for ABS entry points (Audiobookshelf enabled, Local files disabled placeholder).

**Architecture:** Symbol renames are mechanical (file `git mv` + IDE-safe rename). Copy renames are literal-string edits in three Composables (`NavigationDrawerComposable`, `SettingsScreen`, `AddSourceScreen`). The picker is a new stateless Composable driven by a pure JVM-testable list — no ViewModel, no side effects beyond a navigation callback. Nav-graph rewiring inserts one new destination between the ABS entry points and the connect form; Storyteller and WebDAV keep their existing direct entry.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Compose Navigation, Hilt, JUnit4, Compose UI Test.

**Design spec:** `docs/superpowers/specs/2026-07-09-source-switcher-and-add-source-picker-design.md`.

## Global Constraints

- No user-visible change to Storyteller or WebDAV copy — those are #441's job.
- No domain-layer changes: `SourceType` enum stays at `{ ABS }` (adding `LOCAL_FILES` is #7's job). The picker uses a local sealed interface `SourceTypeChoice` to represent LocalFiles without polluting the domain.
- No `Source.serverType` field changes: the `AUDIOBOOKSHELF`/`STORYTELLER` split is what #441 collapses.
- ABS product-referencing copy stays as-is ("your Audiobookshelf server" in help text, "Couldn't reach the server" errors) — those refer to ABS the product, not Riffle's domain.
- Directory `app/feature/server/` stays — package rename is churn without user impact.
- `AddSourceBackend` enum values stay `AUDIOBOOKSHELF` / `STORYTELLER` / `WEBDAV` — internal, keeps diff small.
- Every rename step uses `git mv` (preserves blame) followed by content edits.
- Follow AGENTS.md: regression tests required, no docstring-as-test, verify on AVD before /finalize.

---

## File Structure

**Renames (file moves via `git mv`):**
- `app/src/main/kotlin/com/riffle/app/feature/server/AddServerScreen.kt` → `AddSourceScreen.kt`
- `app/src/main/kotlin/com/riffle/app/feature/server/AddServerViewModel.kt` → `AddSourceViewModel.kt`
- `app/src/main/kotlin/com/riffle/app/feature/server/ServerSetupViewModel.kt` → `SourceSetupViewModel.kt`
- `app/src/test/kotlin/com/riffle/app/feature/server/AddServerViewModelTest.kt` → `AddSourceViewModelTest.kt`

**New files:**
- `app/src/main/kotlin/com/riffle/app/feature/server/SourceTypePickerScreen.kt` — Composable + pure data (`SourceTypeChoice`, `SourceTypeCard`, `sourceTypeCards()`).
- `app/src/test/kotlin/com/riffle/app/feature/server/SourceTypePickerTest.kt` — JVM test over `sourceTypeCards()`.
- `app/src/androidTest/kotlin/com/riffle/app/feature/server/SourceTypePickerScreenTest.kt` — Compose UI test.

**Modified files:**
- `app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt` — nav-route consts, new picker destination, wire ABS entry points through it.
- `app/src/main/kotlin/com/riffle/app/feature/navigation/NavigationDrawerComposable.kt` — drawer copy (`"No server"` → `"No source"`, contentDescription).
- `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt` — section header, button label, callback rename.
- `app/src/main/kotlin/com/riffle/app/feature/navigation/HomeScreen.kt` — callback param rename.
- `app/src/main/kotlin/com/riffle/app/feature/navigation/HomeViewModel.kt` — `StartDestination.AddServer` → `StartDestination.AddSource`.
- `app/src/test/kotlin/com/riffle/app/feature/navigation/HomeViewModelTest.kt` — sealed-class symbol reference.
- Harness tests that traverse HomeScreen → AddSource: add one tap on the "Audiobookshelf" card.

---

## Task 1: Rename `AddServer*` symbols and files (mechanical, no behavior change)

**Files:**
- Rename: `app/src/main/kotlin/com/riffle/app/feature/server/AddServerScreen.kt` → `AddSourceScreen.kt`
- Rename: `app/src/main/kotlin/com/riffle/app/feature/server/AddServerViewModel.kt` → `AddSourceViewModel.kt`
- Rename: `app/src/main/kotlin/com/riffle/app/feature/server/ServerSetupViewModel.kt` → `SourceSetupViewModel.kt`
- Rename: `app/src/test/kotlin/com/riffle/app/feature/server/AddServerViewModelTest.kt` → `AddSourceViewModelTest.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt:40-42,178,193,195,198,214,218` (imports + references)
- Modify: `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt:76,92,127,184,311,339,466` (imports + `AddServerBackend` → `AddSourceBackend`, callback param name)
- Modify: `app/src/main/kotlin/com/riffle/app/feature/navigation/HomeScreen.kt:28,44` (callback param name)
- Modify: `app/src/main/kotlin/com/riffle/app/feature/navigation/HomeViewModel.kt:27,34,44` (`StartDestination.AddServer` → `StartDestination.AddSource`)
- Modify: `app/src/test/kotlin/com/riffle/app/feature/navigation/HomeViewModelTest.kt:130` (test symbol reference)

**Interfaces produced:**
- `AddSourceScreen(windowSizeClass, onNavigateBack, onAuthenticated, onAutoCompleted, viewModel: AddSourceViewModel = hiltViewModel())`
- `AddSourceViewModel`
- `SourceSetupViewModel`
- `enum class AddSourceBackend { AUDIOBOOKSHELF, STORYTELLER, WEBDAV }`
- `HomeViewModel.StartDestination.AddSource`
- Callback: `onNavigateToAddSource: (backend: AddSourceBackend, editId: String?) -> Unit` in `SettingsScreen`.
- Callback: `onNavigateToAddSource: () -> Unit` in `HomeScreen`.

- [ ] **Step 1: Move files with `git mv` (preserves blame)**

```bash
cd app/src/main/kotlin/com/riffle/app/feature/server
git mv AddServerScreen.kt AddSourceScreen.kt
git mv AddServerViewModel.kt AddSourceViewModel.kt
git mv ServerSetupViewModel.kt SourceSetupViewModel.kt
cd -
git mv app/src/test/kotlin/com/riffle/app/feature/server/AddServerViewModelTest.kt \
       app/src/test/kotlin/com/riffle/app/feature/server/AddSourceViewModelTest.kt
```

- [ ] **Step 2: In each renamed file, rewrite symbol names**

Replace inside `AddSourceScreen.kt`:
- Composable function `AddServerScreen` → `AddSourceScreen`
- Enum `AddServerBackend` → `AddSourceBackend`
- Every reference to `AddServerBackend.` → `AddSourceBackend.`

Replace inside `AddSourceViewModel.kt`:
- Class `AddServerViewModel` → `AddSourceViewModel`
- Every internal reference to `AddServerBackend` → `AddSourceBackend`

Replace inside `SourceSetupViewModel.kt`:
- Class `ServerSetupViewModel` → `SourceSetupViewModel`
- Field name `pendingServer` stays (it's a `PendingSource` — the field-name refactor is orthogonal and out of scope).

Replace inside `AddSourceViewModelTest.kt`:
- Class `AddServerViewModelTest` → `AddSourceViewModelTest`
- `AddServerViewModel(` constructor calls → `AddSourceViewModel(`
- `AddServerBackend.` → `AddSourceBackend.`

- [ ] **Step 3: Update `MainScreen.kt` imports and references**

Change lines 40-42 imports from:
```kotlin
import com.riffle.app.feature.server.AddServerScreen
import com.riffle.app.feature.server.SelectLibrariesScreen
import com.riffle.app.feature.server.ServerSetupViewModel
```
to:
```kotlin
import com.riffle.app.feature.server.AddSourceScreen
import com.riffle.app.feature.server.SelectLibrariesScreen
import com.riffle.app.feature.server.SourceSetupViewModel
```

Change references in the nav-graph body:
- Line 195: `val setupVm: ServerSetupViewModel = hiltViewModel(parentEntry)` → `val setupVm: SourceSetupViewModel = hiltViewModel(parentEntry)`
- Line 198: `AddServerScreen(` → `AddSourceScreen(`
- Line 218: same (second occurrence)

Change callback name at line 168 and line 238 (in the composable body):
- `onNavigateToAddServer =` → `onNavigateToAddSource =`

- [ ] **Step 4: Update `SettingsScreen.kt`**

Change import at line 76:
```kotlin
import com.riffle.app.feature.server.AddServerBackend
```
to:
```kotlin
import com.riffle.app.feature.server.AddSourceBackend
```

Change signature at line 92:
```kotlin
onNavigateToAddServer: (backend: AddServerBackend, editId: String?) -> Unit,
```
to:
```kotlin
onNavigateToAddSource: (backend: AddSourceBackend, editId: String?) -> Unit,
```

Replace every call-site `AddServerBackend.` → `AddSourceBackend.` (lines 127, 184, 311, 339, 466).
Replace every call `onNavigateToAddServer(` → `onNavigateToAddSource(` (same lines).

Also rename the internal sealed-class member if present — `SettingsNavEvent.NavigateToAddServer` → `SettingsNavEvent.NavigateToAddSource`. Search the file for `NavigateToAddServer` and rename in `SettingsScreen.kt` and `SettingsViewModel.kt` together.

- [ ] **Step 5: Update `HomeScreen.kt`**

Change line 28 signature:
```kotlin
onNavigateToAddServer: () -> Unit,
```
to:
```kotlin
onNavigateToAddSource: () -> Unit,
```

Change line 44 branch:
```kotlin
is HomeViewModel.StartDestination.AddServer -> onNavigateToAddServer()
```
to:
```kotlin
is HomeViewModel.StartDestination.AddSource -> onNavigateToAddSource()
```

- [ ] **Step 6: Update `HomeViewModel.kt`**

In the sealed class at line 26:
```kotlin
sealed class StartDestination {
    data object AddServer : StartDestination()
```
becomes:
```kotlin
sealed class StartDestination {
    data object AddSource : StartDestination()
```

Every `StartDestination.AddServer` reference in this file (lines 34, 44) → `StartDestination.AddSource`.

- [ ] **Step 7: Update `HomeViewModelTest.kt`**

Line 130 test name/body:
```kotlin
fun `getStartDestination returns AddServer when no servers`() = runTest {
```
becomes:
```kotlin
fun `getStartDestination returns AddSource when no servers`() = runTest {
```
And any `StartDestination.AddServer` in this test → `StartDestination.AddSource`.

- [ ] **Step 8: Compile and run all JVM tests to confirm mechanical rename is behavior-preserving**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test :app:compileDebugKotlin --no-daemon
```
Expected: PASS. No behavior change; only symbol renames.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "refactor(app): rename AddServer* symbols to AddSource* (#435)"
```

---

## Task 2: Update user-facing copy in Nav Drawer + Settings + AddSourceScreen

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/navigation/NavigationDrawerComposable.kt:200,227`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt:157,189`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/server/AddSourceScreen.kt:203-213`
- Test: `app/src/test/kotlin/com/riffle/app/feature/server/AddSourceCopyTest.kt` (new, JVM)

**Interfaces produced:** none (literal-string edits + `internal` visibility bump on two helper functions).

- [ ] **Step 1: Write the failing JVM regression test that pins the AddSourceScreen ABS strings**

`screenTitle()` and `removeButtonLabel()` are currently `private fun` at `AddSourceScreen.kt:203` and `:209`. Bump them to `internal fun` so the JVM test in the same package can reach them.

Create `app/src/test/kotlin/com/riffle/app/feature/server/AddSourceCopyTest.kt`:

```kotlin
package com.riffle.app.feature.server

import org.junit.Assert.assertEquals
import org.junit.Test

class AddSourceCopyTest {
    @Test
    fun `ABS add title = 'Add Audiobookshelf'`() {
        assertEquals("Add Audiobookshelf", screenTitle(AddSourceBackend.AUDIOBOOKSHELF, isEditing = false))
    }

    @Test
    fun `ABS edit title = 'Edit Audiobookshelf'`() {
        assertEquals("Edit Audiobookshelf", screenTitle(AddSourceBackend.AUDIOBOOKSHELF, isEditing = true))
    }

    @Test
    fun `ABS remove label = 'Remove source'`() {
        assertEquals("Remove source", removeButtonLabel(AddSourceBackend.AUDIOBOOKSHELF))
    }

    @Test
    fun `Storyteller strings unchanged (kept for #441)`() {
        assertEquals("Add Storyteller", screenTitle(AddSourceBackend.STORYTELLER, isEditing = false))
        assertEquals("Remove Storyteller", removeButtonLabel(AddSourceBackend.STORYTELLER))
    }
}
```

`screenTitle` and `removeButtonLabel` need to be visible to this test — bump them from `private fun` to `internal fun` in `AddSourceScreen.kt`.

Run:
```bash
./gradlew :app:test --tests "com.riffle.app.feature.server.AddSourceCopyTest" --no-daemon
```
Expected: FAIL — three assertions on `AUDIOBOOKSHELF` fail because current strings still say "Add Audiobookshelf server" / "Edit Audiobookshelf server" / "Remove server".

- [ ] **Step 2: Update `AddSourceScreen.kt` strings (ABS variant only)**

At `AddSourceScreen.kt:204` replace:
```kotlin
AddSourceBackend.AUDIOBOOKSHELF -> if (isEditing) "Edit Audiobookshelf server" else "Add Audiobookshelf server"
```
with:
```kotlin
AddSourceBackend.AUDIOBOOKSHELF -> if (isEditing) "Edit Audiobookshelf" else "Add Audiobookshelf"
```

At `AddSourceScreen.kt:210` replace:
```kotlin
AddSourceBackend.AUDIOBOOKSHELF -> "Remove server"
```
with:
```kotlin
AddSourceBackend.AUDIOBOOKSHELF -> "Remove source"
```

Also change the two `private fun` declarations at lines 203 and 209 to `internal fun` so the JVM test can reach them.

- [ ] **Step 3: Update `NavigationDrawerComposable.kt` strings**

At line 200 replace:
```kotlin
val name = activeServer?.serverType?.label ?: "No server"
```
with:
```kotlin
val name = activeServer?.serverType?.label ?: "No source"
```

At line 227 replace:
```kotlin
contentDescription = "Toggle server switcher",
```
with:
```kotlin
contentDescription = "Toggle source switcher",
```

- [ ] **Step 4: Update `SettingsScreen.kt` strings**

At line 157 replace:
```kotlin
text = "Audiobookshelf servers",
```
with:
```kotlin
text = "Sources",
```

At line 189 replace:
```kotlin
Text("Add server")
```
with:
```kotlin
Text("Add source")
```

- [ ] **Step 5: Re-run JVM tests — they should now pass**

```bash
./gradlew :app:test --tests "com.riffle.app.feature.server.AddSourceCopyTest" --no-daemon
```
Expected: PASS on all four assertions.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(app): rename user-facing Server → Source copy (#435)"
```

---

## Task 3: Create `SourceTypePickerScreen` (Composable + pure data model + JVM test)

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/server/SourceTypePickerScreen.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/server/SourceTypePickerTest.kt`

**Interfaces produced:**
- `sealed interface SourceTypeChoice { data object Audiobookshelf; data object LocalFiles }`
- `data class SourceTypeCard(val type: SourceTypeChoice, val title: String, val subtitle: String, val enabled: Boolean, val comingSoon: Boolean)`
- `internal fun sourceTypeCards(): List<SourceTypeCard>`
- `@Composable fun SourceTypePickerScreen(windowSizeClass: WindowSizeClass, onNavigateBack: () -> Unit, onPickAudiobookshelf: () -> Unit)`

- [ ] **Step 1: Write the failing JVM test**

Create `app/src/test/kotlin/com/riffle/app/feature/server/SourceTypePickerTest.kt`:

```kotlin
package com.riffle.app.feature.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTypePickerTest {

    @Test
    fun `cards are ordered ABS first then LocalFiles`() {
        val cards = sourceTypeCards()
        assertEquals(2, cards.size)
        assertEquals(SourceTypeChoice.Audiobookshelf, cards[0].type)
        assertEquals(SourceTypeChoice.LocalFiles, cards[1].type)
    }

    @Test
    fun `ABS card is enabled and not coming soon`() {
        val abs = sourceTypeCards().first { it.type is SourceTypeChoice.Audiobookshelf }
        assertTrue(abs.enabled)
        assertFalse(abs.comingSoon)
        assertEquals("Audiobookshelf", abs.title)
    }

    @Test
    fun `LocalFiles card is disabled and coming soon`() {
        val lf = sourceTypeCards().first { it.type is SourceTypeChoice.LocalFiles }
        assertFalse(lf.enabled)
        assertTrue(lf.comingSoon)
        assertEquals("Local files", lf.title)
    }
}
```

Run:
```bash
./gradlew :app:test --tests "com.riffle.app.feature.server.SourceTypePickerTest" --no-daemon
```
Expected: FAIL — unresolved reference `sourceTypeCards` / `SourceTypeChoice`.

- [ ] **Step 2: Create the picker Composable + data model**

Create `app/src/main/kotlin/com/riffle/app/feature/server/SourceTypePickerScreen.kt`:

```kotlin
package com.riffle.app.feature.server

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.riffle.app.ui.TabletContentWidthContainer

sealed interface SourceTypeChoice {
    data object Audiobookshelf : SourceTypeChoice
    data object LocalFiles : SourceTypeChoice
}

data class SourceTypeCard(
    val type: SourceTypeChoice,
    val title: String,
    val subtitle: String,
    val enabled: Boolean,
    val comingSoon: Boolean,
)

internal fun sourceTypeCards(): List<SourceTypeCard> = listOf(
    SourceTypeCard(
        type = SourceTypeChoice.Audiobookshelf,
        title = "Audiobookshelf",
        subtitle = "Stream ebooks and audiobooks from your Audiobookshelf server.",
        enabled = true,
        comingSoon = false,
    ),
    SourceTypeCard(
        type = SourceTypeChoice.LocalFiles,
        title = "Local files",
        subtitle = "Read EPUBs and PDFs from a folder on this device.",
        enabled = false,
        comingSoon = true,
    ),
)

private fun iconFor(type: SourceTypeChoice): ImageVector = when (type) {
    SourceTypeChoice.Audiobookshelf -> Icons.Default.Cloud
    SourceTypeChoice.LocalFiles -> Icons.Default.Folder
}

private fun testTagFor(type: SourceTypeChoice): String = when (type) {
    SourceTypeChoice.Audiobookshelf -> "SourceTypeCard.Audiobookshelf"
    SourceTypeChoice.LocalFiles -> "SourceTypeCard.LocalFiles"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceTypePickerScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onPickAudiobookshelf: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add source") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        TabletContentWidthContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                sourceTypeCards().forEach { card ->
                    SourceTypeCardRow(
                        card = card,
                        onClick = when (card.type) {
                            SourceTypeChoice.Audiobookshelf -> onPickAudiobookshelf
                            SourceTypeChoice.LocalFiles -> null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceTypeCardRow(card: SourceTypeCard, onClick: (() -> Unit)?) {
    val cardModifier = Modifier
        .fillMaxWidth()
        .semantics { testTag = testTagFor(card.type) }
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val contentAlpha = if (card.enabled) 1f else 0.5f
            Icon(
                imageVector = iconFor(card.type),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp).alpha(contentAlpha),
            )
            Column(modifier = Modifier.weight(1f).alpha(contentAlpha)) {
                Text(card.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(2.dp))
                Text(
                    card.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (card.comingSoon) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("Coming soon", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
```

Note the `weight(1f)` inside a `Row` — this is valid because `Row`'s children have `RowScope`. If Kotlin complains, replace with `Modifier.weight(1f, fill = true)` explicitly or use the `RowScope` import.

- [ ] **Step 3: Re-run JVM tests to confirm they pass**

```bash
./gradlew :app:test --tests "com.riffle.app.feature.server.SourceTypePickerTest" --no-daemon
```
Expected: PASS on all three tests.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(app): SourceTypePickerScreen with ABS + LocalFiles-disabled placeholder (#435)"
```

---

## Task 4: Wire the picker into the nav graph and route ABS entry points through it

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt` — rename route consts, add picker composable, redirect ABS entry points.

**Interfaces consumed:**
- `SourceTypePickerScreen(windowSizeClass, onNavigateBack, onPickAudiobookshelf)` from Task 3.
- `AddSourceScreen`, `SourceSetupViewModel`, `AddSourceBackend` from Task 1.
- `StartDestination.AddSource` from Task 1.

**Interfaces produced:**
- Nav route consts `ADD_SOURCE = "add_source"`, `ADD_SOURCE_ROUTE = "add_source?type={type}&editId={editId}"`, `ADD_SOURCE_TYPE_PICKER = "add_source_type_picker"`, `SOURCE_SETUP_GRAPH = "source_setup"`.

- [ ] **Step 1: Rename nav-route constants in `MainScreen.kt:54-57`**

Replace:
```kotlin
private const val SERVER_SETUP_GRAPH = "server_setup"
private const val ADD_SERVER = "add_server"
private const val ADD_SERVER_ROUTE = "add_server?type={type}&editId={editId}"
private const val SELECT_LIBRARIES = "select_libraries"
```
with:
```kotlin
private const val SOURCE_SETUP_GRAPH = "source_setup"
private const val ADD_SOURCE_TYPE_PICKER = "add_source_type_picker"
private const val ADD_SOURCE = "add_source"
private const val ADD_SOURCE_ROUTE = "add_source?type={type}&editId={editId}"
private const val SELECT_LIBRARIES = "select_libraries"
```

Update every reference in this file (search `SERVER_SETUP_GRAPH` → `SOURCE_SETUP_GRAPH`, `ADD_SERVER_ROUTE` → `ADD_SOURCE_ROUTE`, `ADD_SERVER` → `ADD_SOURCE`).

- [ ] **Step 2: Insert the picker composable into the setup nav graph**

Inside `navigation(startDestination = ..., route = SOURCE_SETUP_GRAPH) { ... }`, change the `startDestination` to `ADD_SOURCE_TYPE_PICKER` and add a composable block for it. The end result of that navigation block:

```kotlin
navigation(startDestination = ADD_SOURCE_TYPE_PICKER, route = SOURCE_SETUP_GRAPH) {
    composable(ADD_SOURCE_TYPE_PICKER) {
        val cameFromSettings = navController.previousBackStackEntry
            ?.destination?.route == SETTINGS
        SourceTypePickerScreen(
            windowSizeClass = windowSizeClass,
            onNavigateBack = {
                if (cameFromSettings) navController.popBackStack()
                else navController.navigateAsRoot(HOME)
            },
            onPickAudiobookshelf = {
                navController.navigate("$ADD_SOURCE?type=audiobookshelf") {
                    popUpTo(ADD_SOURCE_TYPE_PICKER) { inclusive = true }
                }
            },
        )
    }
    composable(
        route = ADD_SOURCE_ROUTE,
        arguments = listOf(
            navArgument("type") {
                type = NavType.StringType
                defaultValue = ""
            },
            navArgument("editId") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { backStackEntry ->
        val parentEntry = remember(backStackEntry) {
            navController.getBackStackEntry(SOURCE_SETUP_GRAPH)
        }
        val setupVm: SourceSetupViewModel = hiltViewModel(parentEntry)
        val cameFromSettings = navController.previousBackStackEntry
            ?.destination?.route == SETTINGS
        AddSourceScreen(
            windowSizeClass = windowSizeClass,
            onNavigateBack = {
                if (cameFromSettings) navController.popBackStack()
                else navController.navigateAsRoot(HOME)
            },
            onAuthenticated = { pending ->
                setupVm.pendingServer = pending
                navController.navigate(SELECT_LIBRARIES)
            },
            onAutoCompleted = {
                if (cameFromSettings) navController.popBackStack()
                else navController.navigateAsRoot(HOME)
            },
        )
    }
    composable(SELECT_LIBRARIES) { /* unchanged */ }
}
```

- [ ] **Step 3: Route the ABS entry points through the picker**

At `MainScreen.kt` line 168 (HomeScreen callback), replace:
```kotlin
onNavigateToAddSource = {
    navController.navigateAsRoot("$ADD_SOURCE?type=audiobookshelf")
},
```
with:
```kotlin
onNavigateToAddSource = {
    navController.navigateAsRoot(ADD_SOURCE_TYPE_PICKER)
},
```

At `MainScreen.kt` line ~238 (SettingsScreen callback):
```kotlin
onNavigateToAddSource = { backend, editId ->
    val params = buildList {
        add("type=${backend.name.lowercase()}")
        if (!editId.isNullOrEmpty()) add("editId=${URLEncoder.encode(editId, "UTF-8")}")
    }.joinToString("&")
    navController.navigate("$ADD_SOURCE?$params")
},
```
becomes:
```kotlin
onNavigateToAddSource = { backend, editId ->
    // Only the "add ABS from scratch" flow shows the type picker. Storyteller/WebDAV are
    // Services (not Sources) and still deep-link straight to the form. Editing an existing
    // ABS source also skips the picker (Source Type is already known).
    val isNewAbs = backend == AddSourceBackend.AUDIOBOOKSHELF && editId.isNullOrEmpty()
    if (isNewAbs) {
        navController.navigate(ADD_SOURCE_TYPE_PICKER)
    } else {
        val params = buildList {
            add("type=${backend.name.lowercase()}")
            if (!editId.isNullOrEmpty()) add("editId=${URLEncoder.encode(editId, "UTF-8")}")
        }.joinToString("&")
        navController.navigate("$ADD_SOURCE?$params")
    }
},
```

- [ ] **Step 4: Compile and run all JVM tests**

```bash
./gradlew :app:test :app:compileDebugKotlin --no-daemon
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(app): route ABS entry points through Source Type picker (#435)"
```

---

## Task 5: Instrumentation test for `SourceTypePickerScreen`

**Files:**
- Create: `app/src/androidTest/kotlin/com/riffle/app/feature/server/SourceTypePickerScreenTest.kt`

**Interfaces consumed:**
- `SourceTypePickerScreen` Composable.

- [ ] **Step 1: Write the instrumentation test**

Create `app/src/androidTest/kotlin/com/riffle/app/feature/server/SourceTypePickerScreenTest.kt`:

```kotlin
package com.riffle.app.feature.server

import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceTypePickerScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bothCards_areDisplayed() {
        composeRule.setContent {
            val wsc = calculateWindowSizeClass(composeRule.activity)
            SourceTypePickerScreen(
                windowSizeClass = wsc,
                onNavigateBack = {},
                onPickAudiobookshelf = {},
            )
        }
        composeRule.onNodeWithText("Audiobookshelf").assertIsDisplayed()
        composeRule.onNodeWithText("Local files").assertIsDisplayed()
    }

    @Test
    fun audiobookshelfCard_click_invokesCallback() {
        var pickCount = 0
        composeRule.setContent {
            val wsc = calculateWindowSizeClass(composeRule.activity)
            SourceTypePickerScreen(
                windowSizeClass = wsc,
                onNavigateBack = {},
                onPickAudiobookshelf = { pickCount++ },
            )
        }
        composeRule.onNodeWithTag("SourceTypeCard.Audiobookshelf").assertHasClickAction().performClick()
        assertEquals(1, pickCount)
    }

    @Test
    fun localFilesCard_isDisabled_showsComingSoon() {
        composeRule.setContent {
            val wsc = calculateWindowSizeClass(composeRule.activity)
            SourceTypePickerScreen(
                windowSizeClass = wsc,
                onNavigateBack = {},
                onPickAudiobookshelf = {},
            )
        }
        composeRule.onNodeWithText("Coming soon").assertIsDisplayed()
        composeRule.onNodeWithTag("SourceTypeCard.LocalFiles").assertHasNoClickAction()
    }
}
```

- [ ] **Step 2: Run the harness test**

```bash
make harness-test
```
Then filter output for `SourceTypePickerScreenTest` — all three tests PASS.

Expected: PASS. If a test is skipped because the phone-form-factor filter excludes it, add no `@TabletLayout` annotation (this test is form-factor-agnostic; the default filter includes it).

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test(app): instrumentation tests for SourceTypePickerScreen (#435)"
```

---

## Task 6: Update harness tests that traverse HomeScreen → Add flow

**Files:**
- Modify: `app/src/androidTest/kotlin/com/riffle/app/harness/EpubHarnessTest.kt`
- Modify: `app/src/androidTest/kotlin/com/riffle/app/harness/PdfHarnessTest.kt`
- Modify: `app/src/androidTest/kotlin/com/riffle/app/harness/SearchHarnessTest.kt`
- Modify: `app/src/androidTest/kotlin/com/riffle/app/harness/WakeLockHarnessTest.kt`
- Modify: `app/src/androidTest/kotlin/com/riffle/app/harness/NavigationSnapHarnessTest.kt`
- Modify: `app/src/androidTest/kotlin/com/riffle/app/feature/reader/OrientationFlipAnnotationHarnessTest.kt`
- Modify: `app/src/androidTest/kotlin/com/riffle/app/feature/reader/ContinuousAnnotationRenderHarnessTest.kt`
- Modify: `app/src/androidTest/kotlin/com/riffle/app/feature/reader/AnnotationFocusHarnessTest.kt`

Any other harness test that opens `HomeScreen` with zero configured sources: same treatment.

- [ ] **Step 1: In each affected harness test, add a picker-tap step before the "Source URL" field appears**

Find the block that comments about HomeScreen auto-navigating to AddServerScreen, e.g. in `EpubHarnessTest.kt:204`:

```kotlin
// With no servers, HomeScreen automatically navigates to AddServerScreen.
```

Change the comment and add a tap step:

```kotlin
// With no sources, HomeScreen automatically navigates to the Source Type picker.
// Tap the Audiobookshelf card to advance to the connect form.
composeTestRule.onNodeWithText("Audiobookshelf")
    .assertIsDisplayed()
    .performClick()
composeTestRule.waitUntil(timeoutMillis = 5_000) {
    composeTestRule.onAllNodesWithText("Source URL").fetchSemanticsNodes().isNotEmpty()
}
```

Do the same edit in each file listed above. Grep with:

```bash
grep -rn "HomeScreen automatically navigates to AddServerScreen" app/src/androidTest/
```
to find every occurrence (there are ~8).

- [ ] **Step 2: Run the harness suite**

```bash
make harness-test
```
Expected: PASS. If any fail with `Audiobookshelf` node not found, verify that HomeScreen's `onNavigateToAddSource` really navigates to `ADD_SOURCE_TYPE_PICKER` (Task 4 Step 3).

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test(harness): tap through Source Type picker in Add flow (#435)"
```

---

## Task 7: Verify on AVD and open PR

- [ ] **Step 1: Ask the user to build and install the APK**

Say to the user: "Please build and install the debug APK on your device or the harness AVD so I can verify the picker + drawer + settings copy end-to-end."

Wait for confirmation. Do not `assembleDebug` or `adb install` yourself (AGENTS.md).

- [ ] **Step 2: Drive the flow yourself with `adb`**

With the installed APK running on the AVD (serial captured via `adb devices`):

1. `adb shell am start -n com.riffle/.MainActivity` — launch the app.
2. Fresh-install state: HomeScreen should redirect to the Source Type picker. Capture: `adb exec-out screencap -p > /tmp/435-picker-fresh.png`.
3. Configure an ABS source, then open the Nav Drawer → verify header reads `"No source"` (with no active source) or the source-type label (with one) and the trailing-icon contentDescription reads `"Toggle source switcher"` (announced by TalkBack; also visible via UI Automator dump).
4. Open Settings → verify the section header reads `"Sources"` and the button reads `"Add source"`. Capture.
5. Tap Settings → Sources → "Add source" → verify picker screen shown; capture.
6. Tap "Audiobookshelf" card → verify the connect form appears with title `"Add Audiobookshelf"`. Capture.
7. Edit an existing ABS source (Settings → the row → tap) → verify the picker is BYPASSED; the connect form appears directly with title `"Edit Audiobookshelf"` and remove button `"Remove source"`. Capture.
8. Tap Settings → Readaloud → "Configure Storyteller" → verify the connect form appears with title `"Add Storyteller"` (unchanged). Capture.

Store captures under `.context/435-verification/`.

- [ ] **Step 3: Fetch logcat for any error markers**

```bash
adb logcat -d | grep -E "AndroidRuntime|FATAL|RIFFLE_" | tail -60
```
Expected: no crashes, no unexpected errors.

- [ ] **Step 4: Confirm every AGENTS.md verification checklist item is green**

- [ ] Fresh install → picker shown (Step 2.2).
- [ ] Drawer copy renamed (Step 2.3).
- [ ] Settings section + button renamed (Step 2.4).
- [ ] Picker → ABS card advances to renamed form (Step 2.5–2.6).
- [ ] Edit skips picker (Step 2.7).
- [ ] Storyteller unchanged (Step 2.8).

- [ ] **Step 5: Invoke `/finalize`**

Do NOT push manually. `/finalize` rebases on main, addresses any lint/CI feedback, and opens the PR with the required `Closes #435` line.

---

## Regression pinning summary — what stops a revert

- `SourceTypePickerTest.localFiles_isDisabled_showsComingSoon()` (JVM) — locks the LocalFiles-disabled contract until #7 lands.
- `SourceTypePickerTest.cards_orderIsAbsThenLocalFiles()` (JVM) — locks card order.
- `SourceTypePickerScreenTest.localFilesCard_isDisabled_showsComingSoon()` (instrumentation) — locks the `"Coming soon"` label + no-click contract.
- `AddSourceCopyTest.'ABS add title = Add Audiobookshelf'` (JVM) — locks the ABS form title rename.
- `AddSourceCopyTest.'ABS remove label = Remove source'` (JVM) — locks the ABS remove-button rename.
- Harness `Audiobookshelf` card tap — locks the picker's presence in the ABS entry-point flow. If someone reverts the nav-graph rewiring (Task 4 Step 3), every harness test flips red on "Audiobookshelf node not found".

Each of these assertions flips red if the corresponding fix were reverted line-for-line.
