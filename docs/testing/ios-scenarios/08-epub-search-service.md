# Scenario 08c: EPUB Search Service

**Android reference:** `EpubSearchServiceTest.kt` (11 tests)  
**iOS status:** GAP — `ReadiumSwiftNavigator.search()` returns `emptyFlow()`  
**KMP source:** `app/src/main/kotlin/com/riffle/app/feature/reader/controllers/SearchController.kt`

---

## Context

`EpubSearchServiceTest` builds a multi-chapter in-memory EPUB (chapter 3 has 2,000 filler
paragraphs), opens it through Readium Android's `PublicationOpener`, and exercises:

- Full-text search returning results in the correct chapter.
- Iterator close discipline (finallyAfterSuccessfulSearch, finallyAfterException).
- Memory safety on a large chapter without OOM.
- Full pipeline: search → ViewModel result channel → result navigation.

---

## Scenarios (all deferred)

### 08c-A through 08c-K: All 11 EpubSearchServiceTest scenarios

**Gap reason:** `ReadiumSwiftNavigator.search()` in `iosMain/ReadiumSwiftNavigator.kt` returns
`emptyFlow()` (stub). Readium Swift does expose a `SearchService` API but it has not been
wired up to the KMP search controller layer. Until iOS search is implemented:

- `SearchController` on iOS always returns an empty result list.
- `EpubNavigatorInterface.search()` (commonMain stub) delegates to the navigator which no-ops.

**Acceptance when implemented:**
- Readium Swift's `PublicationSearchService` is wired to `ReadiumSwiftNavigator`.
- `SearchHarnessTests.swift` can drive the full library → open → search → navigate flow.
- Iterator close and memory-safety invariants are verified via equivalent XCTest scenarios.

**Work needed:**
1. Implement `ReadiumSwiftNavigator.search(query:)` using Readium Swift's search API.
2. Wire results into `SearchController` (KMP) via a platform callback.
3. Add `SearchHarnessTests.swift` with a test EPUB bundled in `iosAppTests/`.
4. Update this scenario doc.
