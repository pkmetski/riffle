# LocalFiles ingestion pipeline

**Issue:** #437 (part of ADR 0041, phase 5a).
**Blocks:** #7 (LocalFilesCatalog + Add-Source LocalFiles UI).

## Goal

Given one or more user-picked local folders, produce populated `library_items` rows scoped to a LocalFiles `Source`, with cover images and copied-in book bytes stored in Riffle-private storage. Re-scans are idempotent; deletions from the source folder remove the corresponding library item.

Explicitly **out of scope**: any UI to trigger the pipeline, the `LocalFilesCatalog` Catalog implementation, the "Add Source: LocalFiles" flow. Those all belong to #7. This PR wires the plumbing so #7 has a working scanner to invoke.

## Architecture

```
┌─────────────────────────┐   ACTION_OPEN_DOCUMENT_TREE
│  PickFolderContract     │◀──────────────────────────── (call site: app/)
│  (ActivityResultContract)│
└──────────┬──────────────┘
           │ Uri (persistable)
           ▼
┌─────────────────────────┐
│ LocalFilesFolderRepo    │──▶ takePersistableUriPermission
│                         │──▶ upsert LocalFilesFolderEntity
└──────────┬──────────────┘
           │ LocalFilesFolderEntity[]
           ▼
┌─────────────────────────┐
│  LocalFilesScanner      │
│  scan(sourceId)         │
└──────────┬──────────────┘
           │ for each folder
           ▼
┌─────────────────────────┐     ┌──────────────────────────┐
│ FolderWalker            │────▶│ FileClassifier           │
│ (DocumentFile-abstracted)│     │ (ext + magic bytes)      │
└─────────────────────────┘     └──────────┬───────────────┘
                                            │ (kind, uri)
                                            ▼
                          ┌────────────────────────────────┐
                          │ IdentityHasher                 │
                          │ SHA-1(first 64KB) + size       │
                          └────────────────┬───────────────┘
                                           │ sourceItemId
                                           ▼
                       ┌──────────────────────────────────┐
                       │ if row absent → CopyIn + Extract │
                       │ if row present → touch lastSeenAt│
                       └────────────────┬─────────────────┘
                                        │
                          ┌─────────────┴──────────────┐
                          ▼                            ▼
              ┌────────────────────┐        ┌────────────────────┐
              │ EpubMetadataExtractor │      │ PdfMetadataExtractor │
              │ (pure JVM)         │        │ (interface + Pdfium  │
              │                    │        │  impl; JVM fake in   │
              │                    │        │  tests)              │
              └──────────┬─────────┘        └──────────┬─────────┘
                         │                             │
                         └─────────────┬───────────────┘
                                       ▼
                           ┌───────────────────────┐
                           │ LibraryItemDao.upsert │
                           │ LocalFilesFileDao.upsert │
                           └───────────────────────┘

after walk complete:
  rows with lastSeenAt < scanStart → hard-delete library_item + copied bytes
```

## Data model

### `SourceType.LOCAL_FILES`

Add `LOCAL_FILES` enum variant. Extend `SourceStorageModel.hasCacheTier` to return `false` for `LOCAL_FILES`.

### `LocalFilesFolderEntity`

Composite PK `(sourceId, treeUri)`. FK `sourceId → sources(id)` cascade delete.

| column | type | notes |
|--------|------|-------|
| `sourceId` | TEXT NOT NULL | FK |
| `treeUri` | TEXT NOT NULL | persisted SAF tree URI |
| `displayName` | TEXT NOT NULL | resolved from `DocumentFile.name` at insert time |
| `addedAtEpochMs` | INTEGER NOT NULL | |

Index on `sourceId`.

### `LocalFilesFileEntity`

Composite PK `(sourceId, sourceItemId)`. FK `sourceId → sources(id)` cascade.

| column | type | notes |
|--------|------|-------|
| `sourceId` | TEXT NOT NULL | FK |
| `sourceItemId` | TEXT NOT NULL | identity hash — see below |
| `folderTreeUri` | TEXT NOT NULL | which folder found it |
| `originalUri` | TEXT NOT NULL | SAF content URI (for user reference; not opened for reading) |
| `copiedPath` | TEXT NOT NULL | absolute path in Riffle-private storage |
| `format` | TEXT NOT NULL | `"EPUB"` or `"PDF"` — mirrors `BookFormat` names |
| `sizeBytes` | INTEGER NOT NULL | |
| `mtimeEpochMs` | INTEGER NOT NULL | source-side mtime at copy time |
| `lastSeenAtEpochMs` | INTEGER NOT NULL | updated every scan |

Index on `sourceId`. No FK to `library_items` (composite PK matches; both cascade from `sources`).

**Identity hash:** `sha1(firstBytes[0..min(65536, size)] ++ size.toString().toByteArray())`. Rationale: cheap (single read), stable across path changes and rename, tolerates SAF re-mounts. Collision risk on 64KB prefixes is theoretical; if it bites in practice we can extend the hash later.

### Room `library_items` row

The scanner upserts `LibraryItemEntity` using the existing entity — no schema change to `library_items`.
- `sourceId` = the LocalFiles source id.
- `id` = `sourceItemId` (the identity hash), matching composite-PK convention.
- `libraryId` = a synthetic root id, `"local:root"` (LocalFiles is a flat-catalog Source per ADR 0041).
- `title` / `author` / `language` / `publisher` / `publishedYear` / `seriesName` / `genres` / `description` / `isbn` / `asin` populated from the metadata extractor (nullable when the file doesn't provide them).
- `coverUrl` = `file://` URI pointing at the copied-in cover bytes.
- `ebookFormat` = `"EPUB"` or `"PDF"`.
- `ebookFileIno` = null. LocalFiles doesn't need ABS's ino tracking.
- `addedAt` = wall-clock at first scan.

### Migration

`v45 → v46`. `MIGRATION_45_46` = `CREATE TABLE local_files_folders (…)` and `CREATE TABLE local_files_files (…)` with the columns above, plus indexes and FKs. No data-touching statements — the migration is additive. `MigrationTest.migrationChain` extended and a `migration45To46` case added asserting both tables exist post-migration and existing `sources` rows are untouched.

## Scanner pipeline

`core/data/src/main/kotlin/com/riffle/core/data/localfiles/LocalFilesScanner.kt`.

```kotlin
class LocalFilesScanner @Inject constructor(
    private val folderDao: LocalFilesFolderDao,
    private val fileDao: LocalFilesFileDao,
    private val libraryItemDao: LibraryItemDao,
    private val walker: FolderWalker,           // interface — real impl uses DocumentFile
    private val classifier: FileClassifier,     // extension + magic-bytes
    private val hasher: IdentityHasher,
    private val copyIn: CopyInService,          // writes bytes to filesDir/localfiles/…
    private val epubMetadata: EpubMetadataExtractor,
    private val pdfMetadata: PdfMetadataExtractor,   // interface
    private val clock: Clock,
) {
    suspend fun scan(sourceId: String): ScanReport { … }
}
```

`ScanReport` = `{ added: Int, refreshed: Int, removed: Int, failures: List<ScanFailure> }` for logging and tests.

### Recursion

**Recursive** walk over each `LocalFilesFolderEntity.treeUri`. Symlink loops aren't a SAF concern (SAF hides the filesystem). Cycle risk = zero. Users organise ebook libraries in nested folders (author/, series/), so flat-only would be surprising.

### Classification

1. Filter by extension (`.epub`, `.pdf`, case-insensitive).
2. Confirm with magic bytes:
   - EPUB: first 4 bytes `PK\x03\x04`, plus `mimetype` file at offset 30 begins with `application/epub+zip` (the standard EPUB sniff).
   - PDF: first 5 bytes `%PDF-`.
3. Mismatch → skip with a warning to `ScanReport.failures`, no library row.

### Copy-in

Real impl copies via `contentResolver.openInputStream(uri)` → `FileOutputStream(filesDir/localfiles/<sourceId>/<hash>.<ext>)`. Test impl works over a fake filesystem. Copy is streaming — no full-file in-memory buffering. Aborts mid-copy delete the partial file.

Cover images extracted from EPUB `<manifest item properties="cover-image">` (EPUB3) or `<meta name="cover">` fallback (EPUB2) written to `filesDir/localfiles/<sourceId>/covers/<hash>.<ext>`. PDF cover extraction not attempted this PR — `coverUrl = null` for PDFs.

### Deletion detection

`scan(sourceId)` runs across **all folders of the source in one pass**, not per-folder. `scanStart = clock.nowEpochMs()` captured once at the top. Every file the walker yields (from any folder) has its `local_files_files.lastSeenAtEpochMs` set to `scanStart`. After all folders have been walked, `LocalFilesFileDao.deleteStale(sourceId, scanStart)` runs in a single `@Transaction`:
1. Look up rows where `sourceId = ? AND lastSeenAtEpochMs < scanStart`.
2. For each, delete the corresponding `library_items` row, the copied book bytes, and the copied cover bytes.
3. Delete the `local_files_files` row.

Hard-delete only — no soft-delete/tombstone. Rationale: the SAF folder set is the source of truth. If a user re-adds the same file later, the identity hash matches what was deleted but the row is gone, so it's treated as a fresh add. Idempotent semantics preserved.

**Multi-folder same-file case:** the same book present in two configured folders resolves to a single `local_files_files` row (PK is `(sourceId, sourceItemId)`; identity hash is content-based). The `folderTreeUri` column stores the folder that most recently sighted the file — arbitrary, treated as a hint, not authoritative membership. Because scans run across all folders in one pass, the file only becomes stale when it's absent from **every** configured folder, which is the desired behaviour.

### Failure semantics

- Classifier failure → skipped with warning; scan continues.
- Copy-in failure → skipped, no partial row written; scan continues.
- Metadata extraction failure → row is written with `title = DocumentFile.name` fallback, other fields null. Not a scan-fatal error.
- Folder walk failure (SAF permission revoked, folder deleted from OS) → mark all rows for that folder as stale AT scanStart (i.e. `lastSeenAt = 0`), and let the post-walk cleanup delete them. This unifies "SAF permission revoked" with "user deleted files".

## Metadata extractors

### `EpubMetadataExtractor` (`core/domain/…/EpubMetadataExtractor.kt`)

Pure JVM. Operates on either `ByteArray` or `File`. Parses:

- `META-INF/container.xml` → OPF path.
- OPF `<metadata>`:
  - `dc:title` (first)
  - `dc:creator` list (joined `", "` for `LibraryItemEntity.author`)
  - `dc:language` (first, BCP-47 as-is)
  - `dc:publisher`
  - `dc:date` (year extracted; nullable if unparseable)
  - `dc:description`
  - `dc:identifier` where `scheme=ISBN` → `isbn`, else `asin` fallback if `scheme=ASIN`
  - `dc:subject` list → `genres`
  - `<meta property="belongs-to-collection">` → `seriesName`; child `collection-type=series` disambiguates from `set`

- Cover:
  - Prefer `<item id=? properties="cover-image">` in manifest (EPUB3).
  - Fallback: `<meta name="cover" content="X">` → look up `<item id="X">` (EPUB2).
  - Fallback: no cover → `coverUrl = null`.

Returns `EpubMetadata` value class; caller merges into `LibraryItemEntity`.

Sibling to existing `EpubContentExtractor.kt`. Shares the `META-INF/container.xml` + OPF walk — factor a small `OpfLocator` helper if the copy-paste feels bad; not required.

### `PdfMetadataExtractor` interface (`core/domain/…/PdfMetadataExtractor.kt`)

```kotlin
interface PdfMetadataExtractor {
    suspend fun extract(file: File): PdfMetadata
}

data class PdfMetadata(
    val title: String?,
    val author: String?,
    val subject: String?,
    val keywords: List<String>,
)
```

Two implementations:
- **Real:** `PdfiumPdfMetadataExtractor` in a module with Android deps (`core/data` or a new `core/localfiles-android` — decide during implementation; `core/data` already has Android deps so it's simplest). Wraps `PdfiumCore.newDocument(pfd) → getDocumentMeta(doc)`. Instrumentation-tested; not JVM-tested.
- **Fake:** `NoOpPdfMetadataExtractor` returns `PdfMetadata(title = null, author = null, …)`. Used in `LocalFilesScannerTest` so the scanner is JVM-testable end-to-end.

DI: bound in a Hilt module; scanner takes the interface.

## SAF plumbing

`app/src/main/kotlin/com/riffle/app/feature/source/localfiles/PickFolderContract.kt`.

```kotlin
class PickFolderContract : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit) =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == RESULT_OK) intent?.data else null
}
```

`LocalFilesFolderRepository.addFolder(sourceId, uri)`:
1. `context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)`.
2. Resolve `displayName` from `DocumentFile.fromTreeUri(context, uri)?.name` (fallback: last path segment).
3. Upsert `LocalFilesFolderEntity`.

Contract + repo ship in this PR. The disabled "Coming soon" card in `SourceTypePickerScreen` **stays disabled**; #7 wires the picker into the real "Add Source: LocalFiles" screen.

## Module layout

- `core/domain/…/SourceType.kt` — add `LOCAL_FILES`.
- `core/domain/…/SourceStorageModel.kt` — add `LOCAL_FILES → false` in `hasCacheTier`.
- `core/domain/…/EpubMetadataExtractor.kt` — new (pure JVM).
- `core/domain/…/PdfMetadataExtractor.kt` — new interface (pure JVM).
- `core/database/…/LocalFilesFolderEntity.kt`, `LocalFilesFolderDao.kt` — new.
- `core/database/…/LocalFilesFileEntity.kt`, `LocalFilesFileDao.kt` — new.
- `core/database/…/RiffleDatabase.kt` — bump to v46, register entities + DAOs + `MIGRATION_45_46`.
- `core/data/…/localfiles/LocalFilesScanner.kt` — new.
- `core/data/…/localfiles/FolderWalker.kt` — new interface + `SafFolderWalker` Android impl.
- `core/data/…/localfiles/FileClassifier.kt` — new (pure JVM).
- `core/data/…/localfiles/IdentityHasher.kt` — new (pure JVM).
- `core/data/…/localfiles/CopyInService.kt` — new interface + Android impl.
- `core/data/…/localfiles/PdfiumPdfMetadataExtractor.kt` — new (Android).
- `core/data/…/di/modules/LocalFilesModule.kt` — new Hilt module wiring everything.
- `core/data/…/LocalFilesFolderRepository.kt` — new.
- `app/…/feature/source/localfiles/PickFolderContract.kt` — new.

## Tests

**JVM:**
- `EpubMetadataExtractorTest` — fixture set:
  - `minimal.epub` (title+author+language only)
  - `full.epub` (all fields present, EPUB3 cover manifest, series, ISBN)
  - `epub2-cover.epub` (EPUB2 `<meta name="cover">` fallback path)
  - `no-metadata.epub` (empty `<metadata>` — should return all-nulls without throwing)
  - `non-ascii-authors.epub` (UTF-8 authors + diacritics)
  - `corrupt-cover.epub` (cover manifest entry points at a nonexistent zip entry — extractor returns metadata with `coverBytes = null`)
- `FileClassifierTest` — extension + magic-byte matrix, mismatch cases.
- `IdentityHasherTest` — determinism, file-size boundary at 64KB (exactly 64KB, 64KB-1, 64KB+1).
- `LocalFilesScannerTest` — with in-memory DAOs + `FakeFolderWalker` + `FakeCopyInService` + `NoOpPdfMetadataExtractor`:
  - Empty folder → no rows, `ScanReport(0,0,0,[])`.
  - Single EPUB added → 1 row, `library_items` populated, `local_files_files` populated.
  - Re-scan identical folder → `refreshed = 1`, no duplicate rows, `lastSeenAt` bumped.
  - File removed from folder → row and copied bytes gone, `removed = 1`.
  - File classification failure → warning in `ScanReport.failures`, no row.
  - Metadata extraction failure → row written with filename fallback title.
  - SAF permission revoked mid-scan (walker throws) → all rows for that folder deleted.
  - Two folders, overlapping identity hashes → per-folder deletion doesn't touch the other folder's row.
- `LocalFilesFolderRepositoryTest` — `addFolder` upserts, takes persistable permission (via a fake `ContentResolver` abstraction).

**AndroidTest (instrumentation):**
- `MigrationTest.migration45To46` — insert a `sources` row at v45, migrate, assert `local_files_folders` and `local_files_files` tables exist with correct columns and defaults; assert the pre-existing `sources` row survives.
- `MigrationTest.migrateFullChain` — extended to include the new migration.
- `LocalFilesFolderDaoTest`, `LocalFilesFileDaoTest` — insert/upsert/delete/deleteStale semantics.

**No AVD verification** for this PR: it's pure data-layer plumbing with zero reader-visible surface. Verification is JVM tests + MigrationTest, per AGENTS.md and the /work-on skill's "non-visual change" path.

## Open follow-ups (explicit non-goals)

- LocalFilesCatalog Catalog implementation — #7.
- Add Source: LocalFiles UI — #7.
- Enabling the disabled "Coming soon" card — #7.
- PDF cover extraction — deferred; PDFs get null covers this PR.
- PDF metadata JVM test coverage — deferred; Pdfium is Android-only.
- Multi-folder same-file: handled in-scope via all-folders-in-one-pass scan semantics (see Deletion detection above). Test case: "same file in two folders, remove from one, remains in library" is included in `LocalFilesScannerTest`.
