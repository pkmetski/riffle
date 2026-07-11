# Library reorder for any source — design

## Problem

PR #170 shipped drawer-library reordering for Audiobookshelf servers only. The user
wants the same up/down controls for every browsable source (Local Files, Chitanka —
and any future source type), so that the ordering of whatever appears in the left
drawer under a given source is under the user's control regardless of source type.

## Current state (why the fix is small)

The domain and data layers are already source-agnostic:

- `LibraryOrderPreferencesStore` (core/domain) is keyed by `sourceId: String`.
- `NavigationDrawerViewModel.visibleLibraries` combines `libraryObserver.observeLibraries()`
  with `orderStore.libraryOrder(activeSourceId)` and calls `orderLibraries(...)` — the
  same code path for every source.
- Local Files creates a per-folder `LibraryEntity` row (see
  `LocalFilesSourceInstaller`), so its folders already surface as `Library` entries in
  the drawer.
- Chitanka installs its own `LibraryEntity` rows via
  `ChitankaLibraryItemUpserter`.

The gap is entirely in Settings:

- `SettingsViewModel.absServers` filters to `SourceType.ABS` before deriving
  `libraryUiItemsByServer`, so non-ABS sources have no ui-items map entry.
- `SettingsScreen` only renders the "Libraries" subsection (with the reorder
  chevrons) inside `ServerRow`, which is only used for ABS sources.

## Design

### `SettingsViewModel` (core/data unchanged)

- Rename `absServers` → `browsableSources`. Keep the `STORYTELLER_SERVICE`
  exclusion (Storyteller is Settings-only, never browsable per ADR 0026). Drop the
  `SourceType.ABS` filter so Local Files and Chitanka sources flow through.
- Rename `libraryUiItemsByServer` → `libraryUiItemsBySource`. Same
  `Map<String, List<LibraryUiItem>>` shape; now populated for every browsable
  source.
- `setLibraryVisible(sourceId, ...)` and `setLibraryOrder(sourceId, ...)` are
  already source-agnostic — no signature change.

### `SettingsScreen` UI

- Extract the per-library editor (visibility switch + up/down chevrons) currently
  inline in `ServerRow` into a reusable `@Composable LibrariesEditor(
  libraryItems: List<LibraryUiItem>, onSetVisible: (libraryId, Boolean) -> Unit,
  onReorder: (orderedIds: List<String>) -> Unit)`.
- `ServerRow`: unchanged externally; internally calls `LibrariesEditor`.
- `LocalFilesSourceRow`: accept `libraryItems: List<LibraryUiItem>`. Render
  `LibrariesEditor` inside the expanded content as a sibling to the existing
  Folders subsection. The Folders subsection stays as-is (add/remove/health is a
  distinct concern from display order).
- `ChitankaSourceRow`: accept `libraryItems`. Render `LibrariesEditor` if
  non-empty; otherwise no-op (Chitanka may install zero or one library).
- Drop the `SourceType.ABS` gate around `servers.filter { ... }` only insofar as
  the `libraryUiItemsBySource[sourceId]` map lookup is now valid for every
  source. The ABS-specific `ServerRow` still gates on `SourceType.ABS` because
  it renders ABS-only surfaces (server version banner, readaloud summary).

### Storage / migration

None. `LibraryOrderPreferencesStore` already keys by `sourceId` and stores order
in an existing DataStore. Local Files and Chitanka sources have their own
`sourceId`s; the preferences file will grow new entries as users reorder.

## Trade-off note (Q1)

For Local Files, folders and libraries are 1:1. The design keeps two subsections
inside the expanded row: **Folders** (add/remove/health) and **Libraries**
(visibility + order). Same names may appear twice. We accept this because:

- Folder-level operations (path selection, missing-folder health) are logically
  distinct from library display preferences.
- Merging them would require threading library ids into `LocalFilesFolderRow`
  and duplicating the reorder/visibility semantics inside a folder-scoped
  widget.
- Parity across source types is the goal for this change; visual refinement of
  the Local Files row can happen separately.

## Testing

All tests are JVM-level (no Readium/WebView involvement).

### `SettingsViewModelTest`

- **Local Files reorder plumbing** — with a `SourceType.LOCAL_FILES` source and
  three `LibraryEntity` rows, assert `libraryUiItemsBySource[sourceId]` exposes
  three `LibraryUiItem`s in insertion order.
- **Local Files order persists** — call `viewModel.setLibraryOrder(sourceId,
  [c, a, b])` and assert the fake `LibraryOrderPreferencesStore` records that
  ordering under `sourceId`.
- **Chitanka reorder plumbing** — analogous, with `SourceType.CHITANKA`.
- **ABS regression** — the existing ABS test path still populates
  `libraryUiItemsBySource` and `setLibraryOrder` still persists for ABS
  sources. Guards against the `absServers` rename accidentally dropping ABS.

### `NavigationDrawerViewModelTest`

- **Custom order applied for a Local Files active source** — set a
  `libraryOrder` for a Local Files source id, assert `visibleLibraries` reflects
  it. Locks in that the drawer path is source-agnostic and future refactors
  that regress this fail loudly.

### Failure-under-revert check (per AGENTS.md)

If someone reverts the `absServers` filter change, the two Local Files /
Chitanka `libraryUiItemsBySource` assertions flip to empty-map lookups and fail.
If someone reverts the drawer-path assumption, the drawer test fails to see the
custom order.

## Out of scope

- Reordering the sources themselves in the drawer / source switcher.
- Cross-source ordering (order is per-source).
- Any drag-based reorder (up/down chevrons are the shipped affordance from
  PR #170 and are retained).
- Any change to Storyteller (never browsable).
