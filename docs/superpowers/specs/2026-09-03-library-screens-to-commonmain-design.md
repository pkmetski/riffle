# Library Browsing Screens → commonMain Design

**Date:** 2026-09-03  
**Issue:** #909  
**Approach:** B — interface-driven, iOS no-ops for unimplemented features  

---

## Goal

Move `LibraryItemsScreen`, `LibrarySectionScreen`, and their supporting composables from the
Android-only `app` module to `shared/src/commonMain` so the iOS app renders a fully-structured
library browser (same tabs, search, filtering, section grid) instead of the current
`BasicText("Library: …")` placeholder. Features backed by repositories not yet wired on iOS
render as empty data today; separate issues cover the real iOS implementations.

---

## What does NOT change

- The Android `app` module and its `LibraryNavGraph` are **untouched**. Android continues to
  import its screens from `com.riffle.app.feature.library`.
- No harness tests are deleted or modified. New iOS XCTest scenarios are added alongside them.

---

## Architecture overview

```
feature:library/commonMain
  LibraryItemsViewModel     ← moved here (was app/)
  LibrarySectionViewModel   ← moved here (was app/)

shared/commonMain
  LibraryItemsScreen        ← moved here (KMP-adapted)
  LibrarySectionScreen      ← moved here (KMP-adapted)
  BookCoverTile             ← moved here
  DefaultCoverPlaceholder   ← moved here (pure Canvas, zero Android deps)
  CoverGridSizing           ← moved here (KMP-adapted)
  LocalCoversAreSquare      ← moved here
  RiffleIcons               ← moved here (only Material3 vector aliases)
  HomeScreen (updated)      ← replaces BasicText with LibraryItemsScreen

shared/iosMain/Koin.kt      ← registers no-op impls + new VMs

core/data + core/domain     ← interface moves described below
```

---

## Phase 1: Move domain interfaces to commonMain

Three interfaces currently block the ViewModel migration:

### 1a. `PlaylistsRepository`
Currently `core/data/src/androidMain`. Move interface declaration to `core/data/src/commonMain`.
The implementation (`PlaylistsRepositoryImpl`) stays in `androidMain`. iOS registers:

```kotlin
// iosMain no-op
class PlaylistsRepositoryNoOp : PlaylistsRepository {
    override fun observePlaylists(rootId: String) = flowOf(emptyList())
    override suspend fun refresh(rootId: String) {}
    override suspend fun createPlaylist(name: String, rootId: String) {}
    override suspend fun addToPlaylist(playlistId: String, itemId: String) {}
    override suspend fun removeFromPlaylist(playlistId: String, itemId: String) {}
}
```

### 1b. `ToReadRepository`
Currently `core/data/src/androidMain`. Move interface to `core/data/src/commonMain`.
iOS registers:

```kotlin
class ToReadRepositoryNoOp : ToReadRepository {
    override fun observeToReadItemIds(libraryId: String) = flowOf(emptySet<String>())
    override fun isInToRead(itemId: String) = false
    override suspend fun refresh(libraryId: String) {}
    override suspend fun toggleToRead(item: LibraryItem) {}
}
```

### 1c. `LibraryItemOfflineAvailability`
Currently a concrete class in `core/domain/src/jvmMain`. Extract a one-method interface to
`commonMain`; leave the existing class as the JVM impl; iOS registers:

```kotlin
interface LibraryItemOfflineAvailability {
    fun isAvailableOffline(item: LibraryItem): Boolean
}

// iosMain no-op
class LibraryItemOfflineAvailabilityNoOp : LibraryItemOfflineAvailability {
    override fun isAvailableOffline(item: LibraryItem) = false
}
```

---

## Phase 2: Move use cases to commonMain

`RefreshCollections` and `RefreshSeries` are in `core/domain/src/jvmMain` with `javax.inject`
annotations (Hilt remnants — Hilt was already replaced by Koin in #840). Move both to
`core/domain/src/commonMain`, removing `@Inject` annotations. Existing Android Koin bindings
are unchanged. iOS registers no-op subclasses that return `Success` immediately:

```kotlin
// iOS no-op — collections/series refresh not yet wired
class RefreshCollectionsNoOp : RefreshCollections(...) {
    override suspend fun invoke(libraryId: String) = LibraryRefreshResult.Success
}
```

`RefreshLibraryItems` already has an iOS implementation (registered in `iosLibraryModule` from
PR #906's `IosLibraryRefresherImpl`). Move `RefreshLibraryItems` to commonMain and remove
its `@Inject` annotation too.

---

## Phase 3: Move ViewModels to feature:library/commonMain

### LibrarySectionViewModel
All deps already in commonMain. Changes:
- Remove `androidx.lifecycle.SavedStateHandle` → accept `libraryId: String` +
  `sectionType: LibrarySectionType` as constructor params.
- Remove `@Inject`. Add Koin binding in iOS `iosLibraryModule`.

### LibraryItemsViewModel
After Phase 1 and 2, all blocking deps are in commonMain. Changes:
- Remove `SavedStateHandle` → `libraryId: String` constructor param.
- Remove `@Inject` annotation.
- `PlaylistsRepository`, `ToReadRepository`, `LibraryItemOfflineAvailability` now injected as
  interface; iOS gets no-ops.

The `feature:library/build.gradle.kts` needs `libs.compose.runtime` added (for
`mutableStateOf`) and `api(project(":core:data"))` (for the repo interfaces).

---

## Phase 4: Move composables to shared/commonMain

Target package: `com.riffle.shared.library`.

### Files to move

| From (`app/src/main/…/library/`) | To (`shared/src/commonMain/…/shared/library/`) |
|---|---|
| `LibraryItemsScreen.kt` | ✓ (KMP-adapted, see below) |
| `LibrarySectionScreen.kt` | ✓ |
| `DefaultCoverPlaceholder.kt` (from `app/ui/`) | ✓ (no changes needed — pure Canvas) |
| `CoverGridSizing.kt` | ✓ (KMP-adapted) |
| `RiffleIcons.kt` (from `app/ui/theme/`) | ✓ (no changes needed) |

`BookCoverTile`, `BookSectionGrid`, `SeriesCoverTile`, `LocalCoversAreSquare`,
`coverAspectRatio()` all live inside `LibraryItemsScreen.kt` — they move with it.

### KMP adaptations

| Android-specific | KMP replacement |
|---|---|
| `import androidx.activity.compose.BackHandler` | **Remove.** No back-press on iOS. Sub-screen state (search open, annotations list) is managed by Compose state already; close buttons dismiss them. |
| `import androidx.activity.compose.LocalActivity` + `activity.finish()` | **Remove.** iOS app exit is a SwiftUI/UIKit concern. |
| `org.koin.androidx.compose.koinViewModel()` | `org.koin.compose.koinInject<ViewModel>()` (matches `HomeScreen` pattern). For parameterised VMs: `koinInject { parametersOf(libraryId) }`. |
| `LocalContext.current` in `ImageRequest.Builder(…)` | `LocalPlatformContext.current` from `coil3.compose` — KMP-compatible drop-in. |
| `stringResource(R.string.ui_back)` etc. | Inline English string constants. Follow the `LibrarySectionType.title` pattern — define `private const val STR_*` constants at the top of each file. |
| `painterResource(R.drawable.ic_readaloud)` | `Icons.Default.Headphones` (Material3, available in KMP). |
| `WindowSizeClass` / `toScreenDimensionBucket()` | Use `LocalWindowInfo.current.containerSize` from `compose.ui`; derive `ScreenDimensionBucket` from width dp (≥840dp → Expanded, else Compact). |
| `import com.riffle.app.ui.fadingScrollbar` | **Remove.** Not available in shared; iOS scrollbars are handled natively. |
| `import com.riffle.app.R` | **Remove** after replacing all `R.string.*` / `R.drawable.*` references above. |

### shared/build.gradle.kts additions

```kotlin
implementation(project(":feature:library"))  // already present
implementation(libs.coil.compose)            // add
implementation(libs.compose.material3)       // add — needed for Scaffold, TopAppBar etc.
implementation(libs.compose.material.icons.extended) // add — for Material icon aliases
```

---

## Phase 5: Wire HomeScreen.kt

Replace the `StartDestination.Library` placeholder branch:

```kotlin
is HomeViewModel.StartDestination.Library ->
    LibraryItemsScreen(
        libraryId = dest.libraryId,
        libraryName = dest.libraryName,
        onOpenDrawer = { /* no-op on iOS — no drawer */ },
        onSeriesSelected = { /* TODO: series detail screen (future issue) */ },
        onCollectionSelected = { /* TODO */ },
        onItemSelected = { /* TODO: book detail / reader (future issue) */ },
        onAnnotationSelected = { /* TODO */ },
        onAudiobookBookmarkSelected = { /* TODO */ },
        onShowAllAnnotations = { /* TODO */ },
        onSectionSeeMore = { /* TODO: LibrarySectionScreen (future issue) */ },
        onAnnotatedBookClick = { _, _ -> /* TODO */ },
        backEnabled = false,   // iOS has no app-level back
    )
```

Navigation callbacks are no-ops for now; their implementations ship with future detail-screen issues.

---

## Phase 6: iOS Koin wiring

Add to `shared/iosMain/Koin.kt` → `iosLibraryModule`:

```kotlin
// No-ops
single<PlaylistsRepository> { PlaylistsRepositoryNoOp() }
single<ToReadRepository> { ToReadRepositoryNoOp() }
single<LibraryItemOfflineAvailability> { LibraryItemOfflineAvailabilityNoOp() }
single { RefreshCollectionsNoOp(...) }
single { RefreshSeriesNoOp(...) }

// ViewModels (parameterised by libraryId via Koin parametersOf)
factory { (libraryId: String) -> LibraryItemsViewModel(libraryId, get(), get(), get(), get(), ...) }
factory { (libraryId: String, sectionType: LibrarySectionType) ->
    LibrarySectionViewModel(libraryId, sectionType, get(), get(), get())
}
```

---

## Phase 7: Tests

### Android (unchanged)
All existing library harness tests continue to pass against the `app/` screens — no changes needed.

### iOS XCTest to add (in iosApp/iosAppTests/iosAppTests.swift)
Migrate the library-surface Android tests that cover the composables being ported:

| Android test | iOS XCTest scenario |
|---|---|
| `GoldenTraceHarnessTest.goldenTraceLoginLibraryOpenEpubSync` — library grid appears after login | `testLibraryGridAppearsAfterSourceSetup` — launch app, add ABS stub source, verify book-grid cells appear |
| `BookCoverTileReadaloudTest.shows_readaloud_icon_when_linked` | `testBookCoverTileShowsReadaloudIcon` — render BookCoverTile with hasReadaloudLink=true, assert headphones icon visible |
| `BookSectionGridTest` (section grid two-row rule) | `testBookSectionGridTwoRowPreview` — stub library, verify section grid shows ≤ 2-row preview with SeeMore tile |
| `AdaptiveCoverGridTest` (tablet grid wider than phone) | Defer — iOS doesn't have a dedicated tablet AVD yet. Open a tracking issue. |
| `LibrarySearchHeaderFocusTest` | `testLibrarySearchHeaderFocusBehavior` — open search, type query, assert field receives focus |

Create `docs/testing/ios-scenarios/` directory and add scenario docs per AGENTS.md.

---

## Phase 5 addendum: LibrarySectionScreen navigation

`LibrarySectionScreen` is being moved to `shared/commonMain` in Phase 4. Wire its navigation
within `HomeScreen.kt` immediately — do not leave `onSectionSeeMore` as a no-op:

```kotlin
// HomeScreen.kt — simple in-memory nav state
var screen by remember { mutableStateOf<LibraryScreen>(LibraryScreen.Grid) }

sealed interface LibraryScreen {
    data class Grid(val libraryId: String, val libraryName: String) : LibraryScreen
    data class Section(val type: LibrarySectionType) : LibraryScreen
}

when (val s = screen) {
    is LibraryScreen.Grid -> LibraryItemsScreen(
        ...
        onSectionSeeMore = { type -> screen = LibraryScreen.Section(type) },
        onItemSelected = { /* TODO #915 */ },
        ...
    )
    is LibraryScreen.Section -> LibrarySectionScreen(
        sectionType = s.type,
        onItemSelected = { /* TODO #915 */ },
        onNavigateBack = { screen = LibraryScreen.Grid(...) },
    )
}
```

`onItemSelected` in both screens stays a no-op pending #915, which adds
`LibraryScreen.ItemDetail` and the `LibraryItemDetailScreen`.

---

## Phase 8: GitHub issues for deferred iOS implementations

Issues created with label `multi-platform`:

- **#912** — feat(ios): implement Playlists and To-Read tabs in library browser
- **#913** — feat(ios): implement collection and series refresh in library browser
- **#914** — feat(ios): implement offline availability and download status in library browser
- **#915** — feat(ios): move LibraryItemDetailScreen to commonMain and wire item navigation
  *(critical: without this, tapping a book does nothing on iOS)*
- **#916** — feat(ios): move SeriesDetailScreen and CollectionDetailScreen to commonMain

---

## Spec self-review

- `onSectionSeeMore` is now wired within this PR (Phase 5 addendum), not deferred.
- `onItemSelected` is correctly deferred to #915 which adds the nav state machine extension.
- Phases are ordered by dependency: interfaces → use cases → VMs → screens → wiring → tests → issues.
- Android is untouched throughout; no regressions possible from that side.
- `@Inject` removal from jvmMain use cases: safe because Hilt was replaced by Koin in #840.
- `koinInject { parametersOf(…) }` for parameterised VMs: Koin 3.x supports this pattern in KMP.
- `LocalPlatformContext.current`: available in coil3-compose since 3.0; project is on 3.1.0.
