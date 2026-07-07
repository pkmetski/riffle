# ADR 0011 — Unified LocalStore replaces EpubCacheManager and PdfCacheManager

> **Amended by [ADR 0041](0041-source-and-service-abstractions-replace-server.md)** — storage is scoped per **Source** rather than globally. The two-tier Cache/Download split is preserved for Sources with remote catalogs; **[LocalFiles]** has one tier (permanent, copy-in) because its files are always local.

**Status:** Accepted

## Context

The codebase has two format-specific cache managers: `EpubCacheManager` and `PdfCacheManager`. Both expose the same operations (get, save, delete, clear) and differ only in the directory they target. Neither supports the permanent downloads directory introduced by the Offline Mode & Downloads feature — each is hardcoded to the system cache directory.

Adding download support by extending both managers separately would produce four parallel implementations (EPUB cache, EPUB downloads, PDF cache, PDF downloads), with duplicated file-access logic and no shared contract.

ADR 0001 already identifies the `EpubLocalStore` interface (get, save, delete, clear) as the right abstraction for both storage tiers. This ADR records the decision to extend that pattern to PDF and retire both original cache managers.

## Decision

Replace `EpubCacheManager` and `PdfCacheManager` with a single generic `LocalStore<T>` interface (or a format-specific variant per `EbookFormat`). Each format gets two DI-injected instances — one targeting the cache directory, one targeting the permanent downloads directory. The calling code selects the appropriate instance; the store itself is directory-agnostic.

`isCached` and `isDownloaded` are derived at runtime from file existence across both instances — not persisted in the database. `isDownloaded` is removed from `LibraryItemEntity`.

## Alternatives considered

- **Extend the existing managers** — add a `permanent: Boolean` flag to each. Rejected: mixes two responsibilities into one class, making the lookup-order logic ambiguous.
- **Keep managers, add a separate DownloadManager per format** — four classes instead of two. Rejected: the duplication is exactly what the `LocalStore` interface eliminates.

## Consequences

- `EpubRepository` and any future `PdfRepository` gain a `downloadEpub()` / `downloadPdf()` method that writes directly to the downloads instance without opening the reader.
- File lookup order (downloads first, cache second, network last) lives in the repository, not the store.
- A database migration is required to drop the `isDownloaded` column from the Library Item table.
- Tests can inject in-memory or temp-directory-backed `LocalStore` instances without touching the real filesystem.
