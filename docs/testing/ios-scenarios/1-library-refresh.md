# Scenario 1 — iOS library refresh for all source types

Covers `IosLibraryRefresherImpl` routes for ABS, Komga, Chitanka, Gutenberg, and Radio-ES.

## Scenarios

### 1.1 Komga — libraries fetched from server
- Add a Komga source (URL, username, password).
- Trigger a library refresh.
- **Expected**: the library list shows the libraries returned by `GET /api/v1/libraries` on the Komga server.

### 1.2 Chitanka — static roots
- Add a Chitanka source.
- Trigger a library refresh.
- **Expected**: two libraries appear — "Chitanka" (books) and "gramofonche" (audiobooks).

### 1.3 Gutenberg — static root
- Add a Gutenberg source.
- Trigger a library refresh.
- **Expected**: one library appears — "Books".

### 1.4 Radio-ES — static roots
- Add a Radio-ES source.
- Trigger a library refresh.
- **Expected**: two libraries appear — "Podcasts" and "Radio".

## Test coverage

- The Komga scenario (1.1) requires a live or mock Komga server and is covered at the
  JVM level by `KomgaLibraryApiClientTest`.
- The `SourceType` classification (`isUnboundedCatalog` / `isWebSource`) that drives the
  `IosLibraryRefresherImpl` routing is pinned by `SourceTypeTest`
  (`core/models/src/jvmTest`).
- The static-root scenarios (1.2–1.4) end-to-end (libraries actually appearing after a
  refresh) are a GAP on iOS — they need a driven simulator with a configured source and
  are verified manually for now.

The former `LibraryRefreshTests.swift` XCTest file only re-asserted the `SourceType`
enum classification (duplicating `SourceTypeTest`) and was removed.
