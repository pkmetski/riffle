# Navigation Drawer with Library Switcher — Design Spec

**Issue:** #54  
**Date:** 2026-05-21  
**ADR:** docs/adr/0006-navigation-drawer-replaces-server-and-library-screens.md

---

## Overview

Replace `ServerListScreen` and `LibraryListScreen` with a persistent `ModalNavigationDrawer` accessible from every screen. `LibraryItemsScreen` becomes the effective home screen. The drawer header exposes a Server Switcher dropdown; the drawer body lists visible Libraries for the active Server; the drawer footer links to Settings.

---

## Section 1 — Overall Architecture

`MainActivity` composes a single `MainScreen` composable. `MainScreen` owns a `DrawerState` and a Hilt-injected `NavigationDrawerViewModel`. It wraps a Material3 `ModalNavigationDrawer` around the `NavHost`.

### Route set (post-change)

| Route | Destination |
|---|---|
| `HOME` (startDestination) | `HomeScreen` — invisible redirect |
| `ADD_SERVER` | `AddServerScreen` |
| `SETTINGS` | `SettingsScreen` |
| `LIBRARY_ITEMS/{libraryId}/{libraryName}` | `LibraryItemsScreen` |
| `SERIES_DETAIL/{libraryId}/{seriesId}/{seriesName}` | `SeriesDetailScreen` |
| `COLLECTION_DETAIL/{libraryId}/{collectionId}/{collectionName}` | `CollectionDetailScreen` |
| `EPUB_READER/{itemId}` | `EpubReaderScreen` |

`HomeScreen` is a blank `Box` that runs a one-shot suspend check: if `serverRepository.serverCount() == 0` navigate to `ADD_SERVER`, else navigate to `LIBRARY_ITEMS/{id}/{name}` for the first visible library. Both navigations use `popUpTo(HOME) { inclusive = true }` to remove `HOME` from the back stack.

**Deleted routes:** `SERVER_LIST`, `LIBRARY_LIST`

---

## Section 2 — LibraryVisibilityPreferences Data Layer

### Interface — `core/data/src/main/kotlin/com/riffle/core/data/domain/LibraryVisibilityPreferencesStore.kt`

```kotlin
interface LibraryVisibilityPreferencesStore {
    fun hiddenLibraryIds(serverId: String): Flow<Set<String>>
    suspend fun hideLibrary(serverId: String, libraryId: String)
    suspend fun showLibrary(serverId: String, libraryId: String)
}
```

### DataStore extension — `core/data/src/main/kotlin/com/riffle/core/data/di/LibraryVisibilityPreferencesDataStoreExt.kt`

Creates a `DataStore<Preferences>` named `"library_visibility"`. Follows the exact pattern of `FormattingPreferencesDataStoreExt.kt`.

### Implementation — `LibraryVisibilityPreferencesStoreImpl.kt`

- Key per server: `stringSetPreferencesKey("hidden_$serverId")`
- `hiddenLibraryIds`: returns `data.map { prefs -> prefs[key].orEmpty() }`
- `hideLibrary`: `edit { prefs -> prefs[key] = prefs[key].orEmpty() + libraryId }`
- `showLibrary`: `edit { prefs -> prefs[key] = prefs[key].orEmpty() - libraryId }`
- Minimum-one-visible constraint is **not** enforced here — enforced in the Settings UI

### DI — `DataModule.kt`

- `@Provides` for the new `DataStore<Preferences>` (named `"library_visibility"`)
- `@Binds` `LibraryVisibilityPreferencesStore` → `LibraryVisibilityPreferencesStoreImpl`

---

## Section 3 — NavigationDrawer Composable

### Component tree

```
NavigationDrawer(drawerState, activeServer, servers, visibleLibraries, activeLibraryId, callbacks)
  ├── DrawerHeader(activeServer, onToggleSwitcher)
  │     └── ServerSwitcherDropdown(servers, expanded, onServerSelected)  ← Material3 DropdownMenu
  ├── DrawerLibraryItem(library, isActive, onLibrarySelected)  [×N]
  └── DrawerSettingsFooter(onSettingsSelected)
```

### Behaviour

- **DrawerHeader**: shows active server name + URL; chevron icon toggles `serverSwitcherExpanded` local state; tapping outside the dropdown dismisses it
- **ServerSwitcherDropdown**: `DropdownMenu` anchored to the header; active server shown with a checkmark; tapping a server calls `onServerSelected(server)` which closes the drawer and navigates to the first visible library of the selected server
- **DrawerLibraryItem**: highlighted (filled pill background) when `library.id == activeLibraryId`; tapping closes the drawer and navigates to `LIBRARY_ITEMS/{id}/{name}`
- **DrawerSettingsFooter**: tapping closes the drawer and navigates to `SETTINGS`

### `NavigationDrawerViewModel`

Exposed state:
- `activeServer: StateFlow<Server?>`
- `allServers: StateFlow<List<Server>>`
- `visibleLibraries: StateFlow<List<Library>>` — derived from active server's libraries filtered by `LibraryVisibilityPreferencesStore`

`activeLibraryId` is **not** tracked in the ViewModel. `MainScreen` derives it from `navController.currentBackStackEntryAsState()`: when the current destination is `LIBRARY_ITEMS`, extract the `libraryId` argument and pass it directly to `NavigationDrawer`. This avoids duplicating nav state in the ViewModel.

---

## Section 4 — NavGraph & Screen Changes

### `HomeScreen`

Minimal composable — blank `Box`. In `LaunchedEffect(Unit)`, reads `serverRepository.serverCount()` once and navigates:
- `0` → `navController.navigate(ADD_SERVER) { popUpTo(HOME) { inclusive = true } }`
- `>0` → fetch first visible library, `navController.navigate(LIBRARY_ITEMS/…) { popUpTo(HOME) { inclusive = true } }`

### `LibraryItemsScreen`

- `onNavigateBack` callback removed
- New callback: `onOpenDrawer: () -> Unit`
- TopAppBar leading icon: `Icons.Default.Menu` → calls `onOpenDrawer`
- No other changes

### Deleted files

- `app/.../feature/server/ServerListScreen.kt`
- `app/.../feature/server/ServerListViewModel.kt`
- `app/.../feature/library/LibraryListScreen.kt`
- `app/.../feature/library/LibraryListViewModel.kt`

---

## Section 5 — Settings Screen Changes

`SettingsViewModel` gains injected `ServerRepository` and `LibraryVisibilityPreferencesStore`.

### Servers section (top of Settings)

- Header: "Servers"
- Each server: name + URL row with `SwipeToDismissBox` for delete
- On delete of active server: promote the next available server as active; if none remain, navigate to `ADD_SERVER`
- "Add Server" button at bottom of section → navigate to `ADD_SERVER`

### Libraries section (below Servers)

- Header: "Libraries — {activeServerName}"
- Each library for the active server: name row + `Switch` toggle (on = visible, off = hidden)
- When only one library is visible, that library's `Switch` is `enabled = false` (disabled) — enforces the at-least-one-visible constraint in the UI
- All backed by `LibraryVisibilityPreferencesStore` for the active server ID

### `AddServerScreen`

Currently navigates back on success. Changed to navigate to `HOME` (which redirects to the first library). No other changes.

---

## Section 6 — Testing

### `LibraryVisibilityPreferencesStoreTest` (unit, `core/data`)

Uses `FakeLibraryVisibilityPreferencesStore` backed by `MutableStateFlow<Map<String, Set<String>>>`.

Cases:
- Default: all libraries visible (hidden set is empty)
- `hideLibrary` adds to hidden set
- `showLibrary` removes from hidden set
- Hiding an already-hidden library is idempotent
- Store does not enforce minimum-one-visible (documents that this is the UI's responsibility)

### `NavigationDrawerViewModelTest` (unit, `app`)

Uses `FakeServerRepository` + `FakeLibraryVisibilityPreferencesStore`.

Cases:
- `visibleLibraries` excludes hidden library IDs
- Switching servers updates `visibleLibraries` to the new server's libraries

### `SettingsViewModelTest` (unit, `app`)

Cases:
- Delete non-active server removes it from the list
- Delete active server promotes next server as active
- Delete last server triggers navigation to `ADD_SERVER`
- Toggling a library switch calls `hideLibrary` / `showLibrary`
- Last-visible library Switch is `enabled = false` in UI state

### `NavGraphTest` (harness, new cases)

- Cold start with no servers → `ADD_SERVER` is first screen
- Cold start with servers → navigates to first visible library
- Hamburger button on `LibraryItemsScreen` opens the drawer
