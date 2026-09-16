# Android ↔ iOS Parity Review — 2026-09-16

Second full review, following the 2026-09-05 audit (`android-ios-parity-audit.md`) and the
seven iOS PRs that landed since (#1024, #1027, #1034, #1035, #1037, #1039, #1040, #1041).
Scope: functionality, code sharing/duplication, and tests. Everything below was verified by
reading the code on `main` at `1874aa6c2`; one defect was additionally reproduced with a
throwaway Kotlin/Native test on `iosSimulatorArm64` (see §1).

## 0. Verdict

iOS is not equivalent to Android. It is a **second, thinner app** that happens to share the
data models, the catalogs, and eight consolidated ViewModels. The rest — the whole reader
feature set, readaloud, most of the audiobook player, annotations authoring, sync, the library
UI, theming, localization — is either a stub bound in `shared/iosMain/Koin.kt` or does not
exist. The test suites are similarly asymmetric: ~4,000 Android-only test methods versus
~1,300 that execute on the iOS simulator, and only 8 XCUITests exercise the running iOS app.

The good news is structural: the seams exist (`EpubNavigatorInterface`,
`AudioPlayerInterface`, `PreferenceStore`, `core/sync` fully in `commonMain`, the panel
engine in `commonMain`, Compose Multiplatform proven by `feature/source-ui`). The remaining
work is mostly *moving code to where the seams already are*, not inventing new ones.

## 1. Confirmed live defect — iOS positions and per-book prefs cannot persist

`IosReadingPositionDao`, `IosAudiobookPositionDao`, `IosBookFormattingPreferencesDao` and
`IosBookComicFormattingPreferencesDao` (added in #1013, wired in #1037) write to
`reading_positions`, `audiobook_positions`, `book_formatting_preferences` and
`book_comic_formatting_preferences`. **None of those tables is created by
`IosRiffleDatabaseSchema`** (last touched in #985; still v3 with 14 tables). A probe test
that opened a fresh `NativeSqliteDriver(IosRiffleDatabaseSchema, …)` and called
`readingPositionDao().upsert(...)` failed with:

```
co.touchlab.sqliter.interop.SQLiteExceptionErrorCode: error while compiling:
INSERT OR REPLACE INTO reading_positions … no such table: reading_positions
```

Consequences on device:
- Every EPUB/PDF/CBZ close and every audiobook teardown throws inside a
  `CoroutineScope(SupervisorJob()).launch { … }` with no handler. On Kotlin/Native an
  unhandled coroutine exception terminates the process, so closing a book most likely
  **crashes the app**; at minimum the position is lost and `runSyncCycle` never runs.
- `IosRiffleDatabaseSchemaTest.noSpuriousTablesAreCreated` *pins the wrong set* — it lists
  exactly the 14 tables and would fail if the missing ones were added without updating it.
- `ProgressPipelineTests.swift` (PP-A…PP-E) never caught this because all five scenarios
  `XCTSkip` when no source is configured, which is always the case in CI.

Fix: add the four DDL blocks (mirroring the Room entities), bump iOS schema to v4 with a
migration, update both schema tests, and add a `commonTest`/`iosTest` that round-trips every
DAO `IosRiffleDatabaseAccess` returns a real implementation for.

## 2. Functional parity

Status legend: FULL = same shared code on both; PARTIAL = works with reduced scope;
STUB = class bound but no-op; MISSING = nothing.

### Present on iOS at real parity (FULL)
Source picker / add source / select libraries (shared `feature/source-ui`); ABS + Komga
sources; Komga CBZ streaming; CBZ archive reading; `CbzReaderViewModel`; O'Reilly lazy
publication opening; EPUB paginated + vertical modes; EPUB TOC sheet; logging; Home
start-destination routing; To-Read toggle; Settings screen sections (#1039); the
`SeriesDetail`/`CollectionDetail`/`LibrarySection`/`LibraryItems`/`LibraryItemDetail`/
`Settings`/`Downloads`/`Home`/`AnnotationsList` ViewModels.

### Gaps, ranked by user impact

| # | Gap | iOS state | Fix class |
|---|---|---|---|
| 1 | Positions / per-book prefs never persist (§1) | crash / data loss | fix schema |
| 2 | **No cover art anywhere** — every tile and detail header renders `DefaultCoverPlaceholder`; Coil is wired but only used for favicons | MISSING | wire |
| 3 | **Highlights are read-only** — decorations render, but the Swift bridge sets no `editingActions`/selection delegate; no create/edit/delete/notes/colors/merge | MISSING | Swift seam + port `AnnotationSession`, `HighlightMerge`, `HighlightRangeOverlap` |
| 4 | **Readaloud entirely stubbed** — 9 `IosNoOpReadaloud*` bindings, `followReadaloudSentence`/`measureReadaloudColumns` return `Unavailable`; no player sheet, no matches screen. Note: #1037 documents "readaloud not supported on iOS", which contradicts the project rule that all reading modes ship on iOS | STUB | Swift `evaluateJavaScript` seam (the JS in `ColumnSnap`/`CadenceDomScript` is already shared **and passes in WKWebView in `AutoFollowJsTests.swift`**), then lift `ReadaloudSession` (1110 L) etc. |
| 5 | **Continuous mode missing and silently degrades** — `ReaderOrientation.Continuous` hits the `else` branch in `IosEpubReaderScreen.kt` and renders paginated with no signal | MISSING | new Swift/Compose stacked-WKWebView host (ADR 0066 was promised, not written) |
| 6 | **Audiobook player is a separate 220-line reimplementation** (`IosAudiobookPlayerViewModel`) — no sleep timer, bookmarks, skip/rewind intervals, speed persistence, playlist advance, reconciliation, readaloud handoff; `UIBackgroundModes` is absent from `Info.plist` so audio stops when the app backgrounds; `setSpeed` writes `AVPlayer.rate` directly so changing speed while paused starts playback; `coverUrl` is accepted and ignored for lock-screen artwork | PARTIAL | execute ADR 0065 (lift `feature/player/jvmMain/AudiobookPlayerViewModel` to `commonMain`) |
| 7 | **Item detail is 189 lines vs 1681** — no download, mark-read (`IosNoOpMarkReadAcrossDimensions`), chapters (`IosNoOpAudiobookChapterCacheRepository`), description, series, facets, progress ring, edit-metadata | PARTIAL | share the Compose screen (see §3) |
| 8 | **Downloads/offline beyond EPUB** — `IosNoOpAudiobookDownloadRepository`, `IosNoOpPdfRepository`, `IosNoOpReadaloudAudioRepository`; `IosCbzRepository.downloadCbz` returns `Success` **without downloading** | STUB / fake success | lift `core/data/androidMain` impls (62 files are platform-free) |
| 9 | **Progress durability** — save only in `onDispose`; `PositionSaveCoordinator`, `ProgressFlushScope`, `ProgressSweep`, `DirtyProgressLedger` are all `commonMain` and unused on iOS; `ProgressSyncTrigger { }` | PARTIAL | wire existing |
| 10 | **Zero-config web sources installable but dead** — Chitanka/Gutenberg/radio.es appear in the picker, but `catalogFactoriesBySourceType` registers Komga only, there is no unbounded-browse screen, and `IosNoOpBookImportManager` drops imports → permanently empty libraries. O'Reilly is half-built (reader plumbing exists, source cannot be added) | STUB | lift the four `*BrowseViewModel`s (androidx.lifecycle only) + `UnboundedBrowseViewModel` (509 L, pure) |
| 11 | **Library search, tabs, filter facets, sort** — `IosNoOpLibraryFilterPreferencesStore` drops every write; no `SourceBrowseHeader`/`LibraryTabBar`/chips; no `FilteredBooks` screen | STUB | share the Compose screen |
| 12 | **Panel view / panel report** — engine and iOS decoders are wired, but `IosNoOpPanelMaskService`, `IosNoOpPanelViewPreferencesStore`, `IosNoOpPanelReportRepository`; no thumbnail strip, no comic formatting sheet | STUB | cheapest large win: replace 3 no-ops + add viewer |
| 13 | **Annotation sync** — `IosNoOpAnnotationSyncConfigStore`, `WebdavConnectionTester { UnparseableUrl }`, no worker; Settings row is decorative | STUB | lift `AnnotationSyncController` etc. (pure) |
| 14 | **Reader chrome** — no in-reader settings sheet, chapter rail/map (generator is `commonMain` with iOS-green tests), immersive mode, wake lock (pref stored, `isIdleTimerDisabled` never set), footnotes, figure tap/zoom, return card, auto-scroll, dictionary (`core:dictionary` not even a dependency of `:shared`), search-result decorations (`SearchMark` serialised end-to-end, never applied), body-tap `eventFlow` emitted but never collected | MISSING/STUB | mixed; several are "wire existing" |
| 15 | **Formatting fidelity** — 7 of ~24 `FormattingPreferences` fields honoured; bundled fonts (Literata/Merriweather/OpenDyslexic) passed as bare CSS names with no `@font-face` → silent fallback; `DarkDim` collapses to dark; no theme schedule; `IosNoOpAppearanceCoordinator` hardcodes Dark; per-book overrides have a DAO but no store | PARTIAL | move `BookFormattingPreferencesStore` out of `core/domain/jvmMain`; register fonts in Readium Swift `Configuration` |
| 16 | **PDF** — no TOC, formatting, rail; `setPageChangeCallback` implemented on both sides but never registered, so position is sampled once at dispose; locator parsed with a regex | PARTIAL | wire |
| 17 | **Design system / theming / localization** — `MainViewController` installs no `MaterialTheme`; drawer is `Color.White`; all iOS-reachable copy is hardcoded English `BasicText` while Android ships bg/es; no iPad layout (`ScreenDimensionBucket.PhonePortrait` hardcoded) | MISSING | see §3 |
| 18 | **Drawer** — `DrawerViewModel` (103 L) is a drifted subset of `NavigationDrawerViewModel` (190 L): no library ordering, capability gating, downloads link, Riffle-mode, now-playing | PARTIAL | merge |
| 19 | **Reader routing divergence** — Android `ReaderRouter.kt` ignores `hasAudio`; iOS `readerNavForItem` routes audio-first. Same item opens different screens per platform | drifted | single `commonMain` router |
| 20 | Playlists (data layer real, no UI), crash reports, changelog, dictionary packs settings, debug log, local-files folder management (real `IosLocalFilesScanner` exists but the *no-op* is bound in Settings) | MISSING/STUB | lift + wire |

## 3. Code sharing and duplication

The previous audit's nine duplicated ViewModels are down to one drifted pair
(`DrawerViewModel`/`NavigationDrawerViewModel`) plus the parallel `IosAudiobookPlayerViewModel`.
The duplication has changed shape:

1. **A second Compose UI lives in `:shared`** — ~3,500 lines of `BasicText` screens
   (`SettingsScreen` 866, `HomeScreen` 446, `LibraryItemsScreen` 377, `IosEpubReaderScreen`
   323, `IosAudiobookPlayerScreen` 348, …) mirroring ~11,000 lines of Material3 Compose in
   `app`. Root cause: `:shared` has no `androidTarget` and no `compose.material3` dependency.
   `feature/source-ui` already proves the alternative (androidTarget + iOS + material3 +
   `composeResources`). Five screens are near-identical and can merge first:
   `RiffleScreen` (211/216), `LibrarySectionScreen` (96/93), `SeriesDetailScreen` (116/92),
   `CollectionDetailScreen` (78/92), `DefaultCoverPlaceholder` (119/93); `AudiobookPlayerScreen`
   (379/348) is the next candidate.
2. **Parallel data layer.** ~24 `Ios*StoreImpl` (NSUserDefaults) duplicate ~24 `androidMain`
   DataStore stores key-for-key, codec-for-codec, with comments admitting it. The
   `PreferenceStore` seam (`core/data/commonMain/PreferenceStore.kt`, `IosPreferenceStore`,
   `PrefCodecs`) exists and is used by exactly one store. 62 files / 5,075 lines of
   `core/data/androidMain` import nothing platform-specific and can move to `commonMain`,
   retiring ~15 iOS no-ops at once (`AppearanceCoordinatorImpl`, `ReadaloudReviewRepositoryImpl`,
   `StorytellerReadaloudSyncer`, `LocalFilesScanner`, the annotation-sync stack,
   `ToReadRepositoryImpl`, `PlaylistsRepositoryImpl`, `LibraryRepositoryImpl`).
3. **Two persistence engines.** ADR 0058 chose Room KMP for iOS; the iOS XCFramework link
   then OOM'd, so Room was moved to `nonIosMain` and iOS got 2,742 lines of hand-written
   SQLDelight-driver DAOs with an independent schema (v3, 14 tables) versus Room v73 with 32
   tables and 74 migration tests. §1 is the direct result. This needs an ADR decision, not a
   refactor: either make Room KMP link (investigate the OOM) or adopt one SQL layer for both.
4. **Pure Kotlin marooned in `:app`.** 105 files / 10,156 lines import no Android, Readium or
   Compose. Includes 23 ViewModels (~2,630 lines) whose only framework import is
   `androidx.lifecycle` (KMP-compatible) plus `KoinViewModelModules.kt` (729), and the
   reader domain: `ReaderWebViewScripts` 764, `FormattingSession` 372,
   `CaptionHighlightUpgrader` 366, `ContinuousPositionTracker` 359, `HighlightMerge` 343,
   `FootnoteResolver` 328, `HighlightRangeOverlap` 323, `ContinuousDecorationController` 311,
   `FigureTapScript` 268, `EmphasisDomInjector` 263, `EpubCfiRange` 255, `EbookCfiTranslatorImpl` 115.
5. **`jvmMain` is Android-only in practice.** `feature/reader` and `feature/player` target
   `jvm()` + iOS; `:app` consumes the jvm target. `ReaderSync.kt`, `ReaderSyncFactory.kt`,
   `AudiobookPlayerViewModel.kt` (590 L), `AudiobookReconciliationCoordinator`,
   `AudiobookResumeResolver`, `FollowLoopOrchestrator`, and `core/domain/jvmMain/BookFormattingPreferencesStore`
   are therefore invisible to iOS despite living in a "shared" module.
6. **Swift decision logic that belongs in Kotlin** (Swift total is only 1,032 lines, so this
   is the smallest item): theme/prefs mapping and decoration colour table in
   `ReadiumEpubNavigatorBridge.swift`; hand-rolled JSON codecs on both sides of the bridge
   (`escapeForJson` ↔ `jsonEscaped`, `LazyPublicationShapeDto` mirrors) → replace with
   `kotlinx.serialization` DTOs; absolute↔track position arithmetic in
   `IosAudioPlayerBridgeImpl.seekTo` duplicating `AbsolutePositionPlayer.kt`; O'Reilly
   positions strategy differs (`OriginalLength(1024)` vs `.recommended`) so progress numbers
   for the same book disagree across platforms.
7. **Debt to delete.** 28 forwarder/typealias shim files in `app` (275 lines); identical
   `FetchAudiobookChaptersUseCase` in `app` and `feature/library` (both bound in Koin); 22
   `IosNoOp*` classes that are declared but no longer bound anywhere; `IosNoOpLibraryDetailDeps.kt`
   (303 lines of `Ios*` no-ops sitting in `shared/commonMain`); real `Ios*Impl`s that exist
   but are shadowed by a bound no-op (`IosLocalFilesScanner`, `IosLocalFilesFolderRepository`).

## 4. Tests

### Counts today

| Vehicle | Files | Tests | Runs on iOS |
|---|---:|---:|---|
| `app/src/test` (JVM) | 244 | 2,282 | no |
| `app/src/androidTest` | 89 | 357 | no |
| `core/data/src/androidHostTest` | 133 | 1,122 | no (**114 files / 981 tests are platform-free**) |
| `core/database/src/androidDeviceTest` | 1 | 119 | no |
| other `jvmTest`/`androidHostTest` (non-app) | — | ~900 | no |
| `commonTest` (all modules) | — | ~1,296 | yes (`iosSimulatorArm64Test` in CI for every module) |
| `iosTest` (`core/database`, `core/logging`) | 3 | 21 | yes |
| XCTest `iosAppUnitTests` | 13 | 93 | yes |
| XCTest `iosAppTests` (XCUI) | 9 | 29 | yes, but **21 skip** |

### XCTest quality (122 tests)

| REAL | TRIVIAL | always-SKIP | duplicate of an iOS-green `commonTest` |
|---:|---:|---:|---:|
| 72 (59%) | 12 | 21 | 17 |

- **Only 8 XCUITests exercise the running app.** 21 of 29 skip with "No source configured"
  because iOS has no equivalent of `StubAbsServer`. Worse, 15 of those skips are
  *assertions dressed as skips* (`XCTSkip("Comics reader did not open")`,
  `XCTSkip("Book tile disappeared after close")` …) — a real regression reports green.
- Trivial examples: `testAppThemeStoreDefaultIsSystem` only asserts the store is non-nil and
  never reads the default; `testContentCacheAutoClears` tests `UserDefaults` round-tripping;
  four `SettingsRowsTests` construct a struct from literals and assert the literals back.
- 11 of 18 `AudiobookPlayerViewModelTests` restate `AudiobookProgressUtilsTest` (commonTest)
  assertion-for-assertion; 3 `HighlightColor` tests restate `HighlightColorTest`.
- `ReaderSettingsTests` + 5 `SettingsRowsTests` are the **only** tests anywhere that pin
  `FormattingPreferences.defaults()`; they belong in `commonTest` so Android gets them too.
- Strong suites to keep: `AutoFollowJsTests` (real WKWebView), `AnnotationTests`,
  `EpubReaderTests`, `OReillyLazyPublicationTests`, `OfflineAvailabilityTests`.

### Android → iOS gaps

- **Instrumentation parity is 2.4% effective** (2 of 82 Android suites have an executing
  iOS equivalent: source picker and add-source). The 31 Compose-UI suites (94 tests) have
  nothing on iOS at any tier. Seven XCUITest files deleted in #985 (`NavDrawerTests`,
  `NavigationTests`, `LibraryRefreshTests`, `PlaylistsToReadTests`, `SettingsDownloadsTests`,
  `CollectionsSeriesRefreshTests`, `LibraryItemDetailNavTests`) and `RiffleTests` deleted in
  #1034 were never replaced.
- **Mirroring rule violated 6 of 9 times since 2026-09-05**: `OReillyBrowseViewModelTest`,
  `OReillyLoginCookieTest`, `LazyLocatorNormalizerTest` (guards a real iOS O'Reilly crash
  path), `RadioEsBrowseViewModelTest`, `ListenStartAtSecForFinishedTest`,
  `ShouldShowRiffleSourceTest` have no iOS counterpart. The last two test pure functions
  declared inside Composables — lift + `commonTest`.
- **Three `app/src/test` files already target `commonMain` classes** and can move verbatim:
  `LibraryItemDetailViewModelTest` (59), `LibraryFilterEngineTest` (26), `HomeViewModelTest` (18).
- **Five test files are byte-equivalent duplicates** of `feature/player/commonTest`
  (`PlaybackSpeedTest`, `TimeFormatTest`, `ResumePlaybackGateTest`, `PendingSeekGateTest`,
  `BuildAudiobookFactsTest`, 29 tests) — left behind by #1040 alongside the shims.
- Ten zero-platform-import reader classes carry ~350 tests that would run on iOS the day
  they move: `ContinuousPositionTracker` 76, `ReaderWebViewScripts` 43, `ChapterWindowManager`
  40, `FootnoteResolver` 39, `OwnedItemMatcher` 33, `FormattingSession` 28, `HighlightMerge` 27,
  `HighlightRangeOverlap` 24, `ContinuousPresenter` 21, `FigureBorderDecoration` 17.
- `core/network` is `kotlin.jvm`-only (38 tests incl. `StorytellerBundleApiTest`,
  `AudiobookBundleApiTest`) — readaloud bundle logic is untestable on iOS by construction.
- `IosRiffleDatabaseSchemaTest` column checks are `containsAll`, so wrong types/nullability
  or stale columns pass; iOS schema has 2 migration tests vs Room's 74.

## 5. Recommended order of work

Each item is one PR-sized unit; each ends with `./gradlew test jvmTest <modules>:iosSimulatorArm64Test`
and `xcodebuild test` on both schemes green.

1. **Fix §1** (schema v4 + DAO round-trip tests). Blocks everything that persists state.
2. **iOS stub server + kill assertion-shaped skips** so the 21 dead XCUITests become live;
   delete the 12 trivial and 17 duplicate Swift tests; move the `defaults()` tests to `commonTest`.
3. **`core/data/androidHostTest` → `commonTest`** for the 114 platform-free files (981 tests),
   and the 62 platform-free `androidMain` impls → `commonMain`. Largest single test-parity
   and functional win; retires ~15 iOS no-ops.
4. **Bind the real `Ios*Impl`s that already exist**, delete the 22 unbound `IosNoOp*` classes,
   move `IosNoOpLibraryDetailDeps.kt` out of `commonMain`, delete the 28 shims and the
   duplicate `FetchAudiobookChaptersUseCase` and the 5 duplicate test files.
5. **Execute ADR 0065** (shared `AudiobookPlayerViewModel`), add `UIBackgroundModes: audio`,
   fix `setSpeed`/artwork; make the AVQueuePlayer bridge implement `AudioPlayerInterface`.
6. **Make `:shared` (or a new `feature/*-ui`) androidTarget + material3**, merge the five
   near-identical screens, then `AudiobookPlayerScreen`, `RiffleScreen`, item detail, library.
   This is the only route to covers, theming, localization, iPad layout and Compose-UI test
   parity without a second UI.
7. **Lift the pure reader domain** (§3.4) + the 23 lifecycle-only ViewModels to
   `feature/*/commonMain` with their tests; unify the preference stores on `PreferenceStore`.
8. **Swift JS-eval seam + selection delegate** on `ReadiumEpubNavigatorBridge`, then
   highlights authoring, readaloud sentence follow, footnotes, figure tap, search marks.
9. **Continuous mode on iOS** (write ADR 0066 first), panel view, dictionary, chapter rail.
10. **Persistence ADR**: one SQL layer for both platforms, or Room KMP linking on iOS.

Everything in 3, 4, 7 is mechanical movement guarded by existing tests. 1, 2, 5 are small and
high-value. 6, 8, 9 are the genuinely new iOS work.

## 6. Reporter-observed defects (added after device review) and issue map

Observed on device by the project owner on 2026-09-16, traced in code:

| # | Observation | Root cause | Issue |
|---|---|---|---|
| 1 | Source switcher shows "Audiobookshelf" for every source | `DrawerSheetContent` in `shared/.../HomeScreen.kt` uses `serverType.label` for all rows; Android uses `sourceDisplayName()` (descriptor name for non-ABS) and hides the host unless `hasCredentials` | #1045 |
| 2 | Add-source flow lacks Android's confirmation screen | `SourceOnboardingHost.kt:71` installs singleton descriptors on card tap; Android routes to `AddChitankaScreen` etc. `SourcePickerTests.testGutenbergInstallDoesNotCrash` asserts the wrong behaviour | #1045 |
| 3 | Downloads view has no cache-age configuration | shared `DownloadsScreen.kt` never renders `cacheAutoClear` (VM exposes it); no cleanup worker on iOS | #1046 |
| 4 | UI looks like a prototype, must replicate Android | `:shared` has no androidTarget/material3 → ~3.5k lines of BasicText UI; no theme, no resources | #1046 |
| 5 | Opening a source shows no bottom nav (Home/Library…) | shared `LibraryItemsScreen` has no `NavigationBar`; Android has 6 tabs at `LibraryItemsScreen.kt:1361` | #1046 |
| 6 | UI cut off in corners | zero `WindowInsets`/`safeDrawing` usage in `shared/commonMain`; no root `Scaffold` | #1046 |
| 7 | Cannot add ABS/Komga: "invalid username or password" with valid creds | password `OutlinedTextField` in `AddSourceScreen.kt` lacks `capitalization = None` / `autoCorrectEnabled = false` (URL/username got the guard in #985 with a comment describing exactly this 401 mechanism) | #1045 |

**Would the existing tests have uncovered these?** No. Every reporter defect sits in code that
only iOS executes (hand-rolled drawer, onboarding step machine, shared `BasicText` screens, the
iOS DAO layer) or in device keyboard behaviour. The Android tests that pin the correct behaviour
(`NavigationDrawerSourceSubtitleTest`, `AddChitankaViewModelTest`, `LibraryTabVisibilityTest`,
`MigrationTest`) never touch the iOS implementations, and the XCUITests that could have caught
#7 and the schema defect (`AddAbsSourceFlowTests`, `ProgressPipelineTests`) skip in CI for lack
of a stub server. Until the UI and data layer are single-sourced, more device passes will keep
finding new gaps. Tracking: #1044 #1045 #1046 #1047 #1048 #1049.
