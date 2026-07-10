# Local Files: library-per-folder

**Status:** Design approved 2026-07-10.

## Goal

Local Files today is already a singleton `Source` per device (`SourceType.LOCAL_FILES`), and users can attach multiple monitored folders to it. But every scanned file is written into a single synthetic library `local:root` named "Local Files", and the Add-Source picker still lets the user tap "Local files" repeatedly (each tap really just adds another folder, misleadingly labeled).

This change makes the Local Files data model match the mental model:

- The Local Files source is a device singleton, and the UI now enforces that — it disappears from the Add-Source picker once installed.
- Each configured folder is its own library, named after the folder.
- New folders are added from **inside** the expanded Local Files row in Settings via a "+ Add folder" affordance.
- A book that lives in more than one monitored folder appears under each of those folders' libraries.

## Non-goals

- No changes to identity hashing / de-duplication of file bytes (`sourceItemId` remains a content hash).
- No changes to how SAF grants, copy-in, or `CopyInService` work.
- No changes to how other Source types (Audiobookshelf, Storyteller) model libraries.
- No new source-picker taxonomy.

## Data model

### `LocalFilesFolderEntity` gains a `libraryId`

Every folder row gets a stable `libraryId` at insert time: `local:folder:<uuid>`. The UUID is generated once and stored, so it's stable across SAF re-grants and folder-URI changes.

```
LocalFilesFolderEntity(
    sourceId, treeUri, displayName, addedAtEpochMs,
    libraryId,   // new: "local:folder:<uuid>"
)
```

### A `LibraryEntity` per folder

When a folder is added, a `LibraryEntity` is created:

- `id = folder.libraryId`
- `name = folder.displayName`
- `sourceId = <LocalFiles source id>`
- `mediaType = "book"`

The synthetic `LOCAL_ROOT_ID = "local:root"` library is retired. `LocalFilesCatalog.LOCAL_ROOT_ID` and every write that references it go away.

### New junction `local_files_file_folders`

Because a book's identity is content-hashed, one `local_files_files` row can be present in multiple monitored folders simultaneously. Membership becomes its own table:

```
local_files_file_folders(
    sourceId, sourceItemId, folderTreeUri, lastSeenAtEpochMs,
    PK (sourceId, sourceItemId, folderTreeUri),
    FK -> local_files_files(sourceId, sourceItemId),
    FK -> local_files_folders(sourceId, treeUri),
)
```

`folderTreeUri` is dropped from `LocalFilesFileEntity`. The per-membership `lastSeenAtEpochMs` replaces the file-row aggregate. `fetchFile` / `openFile` still read `copiedPath` from `local_files_files` (any folder membership suffices — the file bytes are shared).

### `library_items`: one row per (file, folder)

The scanner emits one `LibraryItemEntity` per (sourceItemId, libraryId) — meaning a shared book gets duplicated metadata rows, one per folder library. This keeps every existing catalog query untouched (they already scope by libraryId).

The metadata duplication cost is small (a few text columns) and lets the reader's normal per-library flows work with zero changes. If shared-book duplication ever becomes a scaling problem, a later `library_items_libraries` join can replace it, but that's a bigger refactor and out of scope here.

## Scanner

`LocalFilesScanner.scan(sourceId)`:

1. Walk each configured folder; for every classified file, build the `(sourceItemId, folderTreeUri)` membership set for this pass.
2. Per unique `sourceItemId`:
   - Upsert one `local_files_files` row (metadata, copied bytes, `format`, cover, mtime) — no `folderTreeUri` here.
   - Upsert one `local_files_file_folders` row per folder that contains it, stamped `lastSeenAtEpochMs = scanStart`.
   - Upsert one `library_items` row per folder membership, each with the folder's `libraryId`. Metadata (title, author, cover URL, series…) is identical across the duplicates.
3. Sweep-stale (only if the whole pass ran clean, per the existing guardrail in `LocalFilesScanner.kt:59-64`):
   - Delete every `local_files_file_folders` row with `lastSeenAt < scanStart`, and the corresponding `library_items` row for that `(sourceItemId, libraryId)`.
   - Delete every `local_files_files` row that now has zero remaining memberships, along with its copied EPUB/PDF and cover.

Add-folder and remove-folder paths run a scan afterwards to converge state. Remove-folder also deletes the folder's `LibraryEntity`; the scanner's membership sweep handles cleanup of items that only lived there.

## UI

### Add-Source picker

The "Local files" tile is shown **only when no `SourceEntity` of type `LOCAL_FILES` exists yet**. Once one exists there is nothing left to add at the source level, so the tile disappears from the picker. The Add-Source picker query already reads the Source list — a simple predicate hides the tile.

The first-time flow is unchanged: pick the tile → SAF folder picker → source installer creates the singleton source + first folder + first folder's library → scan → success screen.

### Sources list, expanded Local Files row

Add a **"+ Add folder"** ListItem at the top of the expanded content, above the folder list. On tap:

1. Launch the SAF folder picker.
2. On success, call a shared "attach folder to source" primitive that: (a) inserts a `LocalFilesFolderEntity` with a fresh `libraryId`, (b) inserts the folder's `LibraryEntity`, (c) runs a scan.
3. On cancel/failure, surface the same states the first-run flow uses (cancelled banner, error retry).

Folder rows below stay as they are (health warning, remove-folder confirm). Their per-row display name is now also the drawer library name.

### Drawer / library selector

Each folder library appears under the Local Files source with `name = folder.displayName`. No more "Local Files" umbrella library. `LibraryVisibilityStore` and `LibraryOrderStore` apply per folder library — hide/reorder work identically to Audiobookshelf/Storyteller libraries.

## Migration (Room `N → N+1`)

Follow AGENTS.md checklist: bump `RiffleDatabase.version`, add `MIGRATION_N_(N+1)`, build to export the schema JSON, register in `DataModule.addMigrations(...)`, extend `MigrationTest`.

Migration steps (single transaction):

1. `ALTER TABLE local_files_folders ADD COLUMN libraryId TEXT NOT NULL DEFAULT ''`. Then for each existing folder row, generate a UUID and `UPDATE ... SET libraryId = 'local:folder:' || <uuid>`.
2. `CREATE TABLE local_files_file_folders` with the schema above, plus FKs and indices on `(sourceId, folderTreeUri)`.
3. Backfill the junction: for each existing `local_files_files` row, insert one `local_files_file_folders(sourceId, sourceItemId, folderTreeUri = files.folderTreeUri, lastSeenAtEpochMs = files.lastSeenAtEpochMs)`. Cross-folder duplicates are unknowable historically and will materialize on the next scan.
4. `CREATE TABLE local_files_files_new` without `folderTreeUri`, copy rows over minus that column, drop old, rename.
5. Insert one `LibraryEntity(id = folder.libraryId, name = folder.displayName, sourceId, mediaType = "book")` per folder row.
6. `UPDATE library_items SET libraryId = <folder.libraryId>` where `libraryId = 'local:root'` and the file's `folderTreeUri` maps to a folder. Delete any `library_items` row with `libraryId = 'local:root'` that has no matching file row (orphan).
7. Delete the `LibraryEntity` with `id = 'local:root'`. Delete matching `library_visibility` and `library_order` rows.

Non-DB migration:

- Delete `LocalFilesCatalog.LOCAL_ROOT_ID` constant.
- `LocalFilesCatalog.listRoots()` returns one `CatalogRoot` per configured folder, sourced from `LocalFilesFolderDao`.
- `LocalFilesSourceInstaller.installFolder` refactors: extract a shared `addFolderToSource(sourceId, treeUri, displayName)` primitive used by both the first-install path and the Settings "+ Add folder" path. Both create the folder row + library row + run a scan.
- The Add-Source picker's tile-visibility predicate: `sources.none { it.type == LOCAL_FILES }`.

## Testing

Per AGENTS.md, every behavior change needs an assertion that flips red on revert.

**core:database — `MigrationTest.migrationNToN1()`:**
- Seed a pre-N DB with one Local Files `SourceEntity`, two folder rows (folder A displayName "Reading Now", folder B displayName "Archive"), and three `local_files_files` rows: file X in folder A only, file Y in folder A only, file Z in folder B only. Seed the corresponding `library_items` rows with `libraryId = "local:root"`.
- Run the migration.
- Assert: (a) both folders have non-empty distinct `libraryId`s of the form `local:folder:*`; (b) two `LibraryEntity` rows exist with names "Reading Now" and "Archive"; (c) `local_files_file_folders` has three rows matching the historical `folderTreeUri`s; (d) `library_items.libraryId` for X and Y equals folder A's `libraryId`, for Z equals folder B's `libraryId`; (e) `LibraryEntity` with `id = "local:root"` no longer exists; (f) `local_files_files.folderTreeUri` column is gone. Add the migration to `migrateFullChain`.

**core:data — `LocalFilesScannerTest`:**
- Add a fixture with two folders A and B, one book present only in A, one book present in both A and B (same bytes → same identity hash), one book only in B.
- Run `scan(sourceId)`.
- Assert: `library_items` has four rows (A-only book × 1, shared book × 2 under both `libraryId`s, B-only book × 1); `local_files_file_folders` has four rows; `local_files_files` has three rows.
- Second-pass regression: delete the shared book from folder A only (leave B intact), rerun `scan`. Assert the A-side `library_items` row and A-side junction row are gone; the B-side row and the `local_files_files` row survive.

**core:data — `LocalFilesCatalogTest`:**
- Given two folders, assert `listRoots()` returns two `CatalogRoot`s with the folders' display names, and `browse(rootId = folder A's libraryId, ...)` returns only A's items.

**app — SettingsViewModel:**
- With an existing Local Files source and one folder, calling the "add folder" action attaches to the existing source (no second `SourceEntity` inserted) and creates a new `LibraryEntity`.

**app — Add-Source picker (JVM test on the tile-visibility predicate, extracted if necessary per the AGENTS.md rule about JVM-testable decisions):**
- Predicate returns `true` (tile shown) when the Source list contains no LOCAL_FILES source, `false` otherwise.

## Files touched (indicative)

- `core/database/src/main/kotlin/com/riffle/core/database/LocalFilesFolderEntity.kt` — add `libraryId`.
- `core/database/src/main/kotlin/com/riffle/core/database/LocalFilesFileEntity.kt` — remove `folderTreeUri`.
- `core/database/src/main/kotlin/com/riffle/core/database/LocalFilesFileFolderEntity.kt` — new junction entity + DAO.
- `core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt` — version bump, `MIGRATION_N_(N+1)`, entities list.
- `core/database/schemas/com.riffle.core.database.RiffleDatabase/<N+1>.json` — exported by KSP.
- `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt` — new test + `migrateFullChain` extension.
- `core/data/src/main/kotlin/com/riffle/core/data/localfiles/LocalFilesSourceInstaller.kt` — extract `addFolderToSource`; drop `LOCAL_ROOT_ID` library seeding.
- `core/data/src/main/kotlin/com/riffle/core/data/localfiles/LocalFilesScanner.kt` — junction-based ingest + membership sweep.
- `core/data/src/main/kotlin/com/riffle/core/data/localfiles/LocalFilesCatalog.kt` — per-folder `listRoots()`, drop `LOCAL_ROOT_ID`.
- `core/data/src/main/kotlin/com/riffle/core/data/localfiles/LocalFilesFolderRepository.kt` — create/delete `LibraryEntity` alongside folder rows.
- `core/data/src/main/kotlin/com/riffle/core/data/di/DatabaseModule.kt` — register migration.
- `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt` — "+ Add folder" row in expanded Local Files section.
- `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsViewModel.kt` — "add folder" action wired to `addFolderToSource`.
- Add-Source picker screen — hide LOCAL_FILES tile predicate.
