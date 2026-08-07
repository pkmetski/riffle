# Item Details: Container Format + EPUB Version

**Date:** 2026-08-07

## Goal

Show the file container format (EPUB / PDF / CBZ) in item details for every ebook item. For EPUB items, also show the EPUB version (e.g. "EPUB 3.0") once it has been extracted from the file.

## Background

`LibraryItem.ebookFormat` already carries the format enum (`Epub`, `Pdf`, `Cbz`, `Unsupported`). The existing `PublicationFactsLine` composable shows reading time or page count but is invisible when those values are not yet available — it does not show a format label at all. No field for EPUB version exists anywhere in the data model.

## Design

### Container format row

A new `FormatLine` composable is added to `MetadataLines` in `LibraryItemDetailScreen.kt`. It shows unconditionally for `Epub`, `Pdf`, and `Cbz`; it is hidden for `Unsupported`. The label is plain text:

- `Epub` (version unknown or not yet cached): `"EPUB"`
- `Epub` (version cached): `"EPUB 3.0"` / `"EPUB 2.x"` (version string verbatim from OPF)
- `Pdf`: `"PDF"`
- `Cbz`: `"CBZ"`

The version portion is appended only when non-null; the row never shows a loading state.

### EPUB version extraction and caching

The EPUB version is the `version` attribute on the `<package>` element in the OPF file inside the EPUB zip (e.g. `<package version="3.0">`).

**Extraction point:** `ExtractEpubTocUseCase.extractDetails()` already opens the EPUB zip via Readium to extract the TOC and position count. After getting the `File` reference, call `EpubMetadataExtractor.extract(file).epubVersion` before opening via Readium. This keeps all derived-metadata extraction for item details in one call.

**`EpubMetadataExtractor` change:** Add `val epubVersion = opf.getAttribute("version").ifEmpty { null }` in `extractFrom()`. Add `epubVersion: String?` to `EpubMetadata` and update `EMPTY`.

**`PublicationMetrics` change:** Add `val epubVersion: String? = null`. Thread through `PublicationMetricsRepositoryImpl.get()` and `save()`, and `PublicationMetricsCacheEntity`.

**Cache invalidation:** `PublicationMetrics` is already keyed by `(sourceId, itemId, ebookFileIno)` and respects `isDerivedCacheStale` TTL. No extra invalidation logic needed — if the file is replaced the inode changes, the cache miss triggers re-extraction.

**`ExtractEpubTocUseCase.Details` change:** Add `epubVersion: String?`. Populated from the extraction result when a fresh read happens; taken from cached `PublicationMetrics.epubVersion` on a cache hit.

### ViewModel

`LibraryItemDetailViewModel` already calls `extractEpubTocUseCase.extractDetails()` and stores the result. Expose `epubVersion: String?` alongside `tocState` so the screen can bind to it.

### Database migration

`PublicationMetricsCacheEntity` gains a nullable `epubVersion TEXT` column. DB version bumps v64 → v65 with:

```sql
ALTER TABLE publication_metrics_cache ADD COLUMN epubVersion TEXT
```

All existing rows get `NULL`, which is correct — the version is re-extracted on next detail-screen open.

## What is not in scope

- Showing format/version in the library grid or list rows.
- Fetching EPUB version from the ABS server (it does not expose it).
- Showing version for PDF or CBZ (no equivalent concept).
- Populating version at download time (lazy extraction on detail open is sufficient).
