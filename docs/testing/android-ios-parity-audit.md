# Android ↔ iOS Test Parity Audit

**Date:** 2026-09-05
**Branch:** `pkmetski/mumbai`
**Purpose:** One-time full audit of the Android test corpus against the iOS test
corpus, to (a) find every coverage gap in both directions, (b) classify each gap
by the work required to close it, (c) surface features that don't yet work on iOS
because of a missing migration, and (d) establish a discipline that keeps the two
suites in sync going forward.

This document is the **tracking artifact** for the parity effort. Each row in the
matrix (Appendix A) moves from ❌ → ✅ as batches land. Update it in the same PR
that closes a gap.

---

## 1. Top-line numbers

| | Files | `@Test`/`func test` |
|---|---:|---:|
| Android instrumentation (`androidTest`) | 89 | 377 |
| Android/JVM unit (`test`) | 293 | 3037 |
| Shared `commonTest` (KMP) | 22 | 142 |
| iOS XCTest (`iosApp/iosAppTests`) | 15 | 72 |

`commonTest` is counted once but compiles for **both** platforms. The rest of the
Android corpus (≈3400 test methods) is Android-only today.

### Classification of every non-shared Android test file

| Cat | Files | Tests | Meaning | Vehicle to close the gap |
|---|---:|---:|---|---|
| **A** | 21 | 136 | Already shared `commonTest` in an iOS-targeted module | *(mostly done — but CI wiring gap, see §3)* |
| **A** *(was A?)* | 1 | 6 | `core/dictionary` iOS target added; `LanguageCatalogTest` now runs on `iosSimulatorArm64` | ✅ done (#945) |
| **B** | 220 | 2140 | `:app` pure Kotlin logic (ViewModels, mappers, resolvers, parsers) | **move production code to `feature/*/commonMain`, port test to `commonTest`** |
| **B/D** | 31 | 464 | `:app` reader "glue" — needs per-file judgment (shared logic vs Android-WebView/Readium) | split: shared parts → `commonTest`; WebView/Readium parts → XCTest |
| **C** | 23 | 361 | Catalog / networking layer — **module has no iOS target at all** | **BIG migration** (see §5) |
| **D** | 84 | 377 | Instrumentation: Compose-UI, Android-WebView-JS, Readium-Android | XCTest UI/bridge tests + `docs/testing/ios-scenarios/*.md` |
| **N/A** | 7 | 72 | `buildSrc` build-logic lint tests | host-only; not iOS-relevant, exclude from parity |

**Bottom line:** iOS "feature parity" is *not* in place at the test level. The
bulk of Riffle's feature logic still lives in the Android-only `:app` module
(category B, ~2100 tests). Until that logic is lifted into the shared `feature/*`
modules, iOS is running a re-implementation (Swift) whose behaviour is unverified
against the Android logic tests. This audit maps the road to closing that.

---

## 2. The four test vehicles (and when to use each)

Decision from project owner (2026-09-05): **prefer `commonTest`** for anything
shareable; hand-write XCTest only for iOS-only Swift glue.

1. **`commonTest`** (KMP, `src/commonTest`) — runs on **every** target incl.
   `iosSimulatorArm64Test`. This is the parity vehicle for all pure logic. Write
   once, covers both platforms. **Preferred.**
2. **XCTest** (`iosApp/iosAppTests/*.swift`) — for iOS-only Swift: the Readium-Swift
   bridge, PDFKit reader, UIKit navigation, SwiftUI screens. Pairs 1:1 with a
   scenario doc.
3. **Scenario docs** (`docs/testing/ios-scenarios/NN-*.md`) — human-readable spec
   of what each XCTest verifies. Required for every new XCTest (CLAUDE.md rule).
4. **`androidTest`** (Compose UI / Espresso / live WebView) — Android-only; its
   iOS counterpart is an XCTest, never a port in place.

---

## 3. CI wiring gap (fix regardless of batches)

The iOS CI (`.github/workflows/ios.yml`) runs `iosSimulatorArm64Test` for **only**
`:core:database` and `:core:sync`. Every other iOS-targeted module with a
`commonTest` — `shared`, `feature/library`, `feature/player`, `feature/reader`,
`feature/source`, `core/net`, `core/models`, `core/sources` — has shared tests that
**compile for iOS but are never executed there**. That is ~100 tests of "shared"
coverage that could silently break on iOS without CI noticing.

**Action (small, do early):** extend the `database-test` job (or add a job) to run
`iosSimulatorArm64Test` for all iOS-targeted modules that have a `commonTest`. This
is the cheapest, highest-leverage parity win and it protects every batch that
follows.

**Update 2026-09-06:** the `unit-test` job now runs `iosSimulatorArm64Test` for all
iOS-targeted modules with a `commonTest`. Two were missing from the task list —
`:feature:settings` (40 tests) and `:feature:downloads` (2 tests) — and
`feature/settings`' commonTest did not even compile for Kotlin/Native (three backtick
test names contained `,`/`()`, illegal in K/N identifiers; renamed mechanically).
Both modules now pass on `iosSimulatorArm64`.

### 3b. Phantom Android coverage: `core/data/src/androidTest` (found 2026-09-06)

`core/data` uses the `com.android.kotlin.multiplatform.library` plugin, whose test
source sets are `androidHostTest` / `androidDeviceTest`. The legacy
`core/data/src/androidTest` directory (`AnnotationSyncControllerIntegrationTest`, 7
tests; `LocalDirectoryTargetTest`, 13 tests) is **not a recognized source set** —
verified via `:core:data:tasks --all`: no task compiles it. These 20 instrumentation
tests (annotation-file I/O and sync-controller integration) have silently never run.
Reviving them requires enabling a device-test compilation (`withDeviceTest {}`),
moving the directory to `src/androidDeviceTest`, and a CI job to execute it — or
rewriting them against Robolectric/host abstractions in `androidHostTest`.

---

## 4. Category detail

### A — Already shared (21 files / 136 tests)
These `commonTest` suites live in iOS-targeted modules and already give real iOS
coverage (see Appendix A). Remaining work is only §3 (make CI run them on iOS).

### ~~A?~~ → A — `core/dictionary` `LanguageCatalogTest` (6) ✅ **#945 resolved**
iOS target (`iosArm64`, `iosSimulatorArm64`) added to `core:dictionary`. `LanguageCatalogTest`
converted from JUnit to `kotlin.test` so it runs in both JVM and iOS simulator. Dictionary /
language-pack feature is confirmed as iOS-bound.

### B — `:app` pure logic → migrate + `commonTest` (220 files / 2140 tests)
The main event. These are Android-only today only because the code-under-test sits
in `:app`. They are ViewModels and pure helpers with no Android-framework
dependency in their logic: e.g. `LibraryItemsViewModelTest` (74),
`EpubReaderViewModelTest` (66), `ContinuousPositionTrackerTest` (76),
`RailSegmentGeneratorTest` (79), `SettingsViewModelTest` (40),
`FootnoteResolverTest` (39), `HighlightMergeTest` (27), `EpubCfiTest` (25),
`AudiobookPlayerViewModelBookmarkTest` (23), the entire `feature/reader/session/*`
orchestrator suite, etc.

For each: move the production class into the matching `feature/*/commonMain` (the
module already targets iOS), then move the test into that module's `commonTest`
verbatim (the CLAUDE.md "prefer commonMain" rule). Where the production code is
small and framework-free, do it now; where the class drags in Android APIs
(`WebView`, `Fragment`, `Context`, Readium-Android), split the pure part out and
leave the Android part behind (that residue becomes category B/D or D).

**Watch for duplicates already migrated:** several classes exist in *both* `:app`
JVM and a `feature/*` `commonTest` (e.g. `CollectionDetailViewModelTest`,
`SeriesDetailViewModelTest`, `LibraryItemDetailViewModelTest`,
`AudiobookProgressUtilsTest`/`BuildAudiobookFactsTest`,
`FormattingPreferencesMapperTest`, `FigureTapScriptTest`). For these the migration
is partly done — reconcile the two, keep the shared one, delete the redundant
`:app` copy (respecting the `Removed-test:` trailer guardrail).

### B/D — `:app` reader glue, needs judgment (31 files / 464 tests)
Files whose name/subject touches WebView/DOM/Readium/Presenter/Renderer/Injector
(e.g. `ReaderWebViewScriptsTest` 43, `ContinuousStyleInjectorTest` 97,
`ContinuousHighlightRendererTest` 26, `CadenceDomScriptTest` 18,
`ReadiumHighlightRendererTest` 14, `ChapterWebViewBinderTest`). Many verify the
**text of injected JS** or **pure DOM-math** — that string/logic is portable to
`commonTest` even though it *runs* against a WebView on device; the iOS reader
injects the same JS into its Readium-Swift/WebKit navigator, so a shared
`commonTest` over the JS-producing Kotlin plus an iOS XCTest that executes it in
WebKit gives true parity. Triage each file into: shared-logic (`commonTest`) vs
device-execution (XCTest).

### C — Catalog / networking → BIG migration (23 files / 361 tests)
See §5.

### D — Instrumentation → XCTest + scenario (84 files / 377 tests)
89 `androidTest` files minus fixtures. Two sub-kinds:
- **Compose-UI** (53 files / 234 tests): tile grids, drawers, settings rows,
  swipe-to-delete, popups, snackbars, badges. iOS counterpart is a SwiftUI XCTest
  UI test per scenario. Gated on the SwiftUI screen existing.
- **WebView/Readium** (31 files / 143 tests): `AutoFollowJsTest`,
  `EpubCfiTranslatorInstrumentedTest`, `EpubSearchServiceTest`,
  `ScrollBoundaryNavigationContainerTest`, `AnnotationFocusHarnessTest`, the
  `harness/*` golden-trace suites. iOS counterpart is a Readium-Swift bridge XCTest;
  gated on the corresponding iOS reader capability existing.

### N/A — `buildSrc` lint (7 files / 72 tests)
`AndroidImportLint`, `ServerReferenceLint`, `TestGuardrailLint`, etc. These test the
Gradle build logic itself and run on the host JVM. No iOS analogue; **excluded** from
parity scope.

---

## 5. BIG migrations blocking iOS feature parity (report)

These are features whose Android logic cannot be shared without a substantial
migration. Per project owner: report them; port the tests but keep them
`@Ignore`d/deferred until the migration lands (note: for category C the module has
no iOS target, so the tests cannot even compile for iOS yet — they stay in place
and this report tracks them).

1. ~~**Web-source catalog + networking layer**~~ — ✅ **done** (#944): `core/catalog`, `core/catalog-komga`, `core/catalog-gutenberg`, `core/catalog-chitanka`, `core/catalog-radio-es` converted to `kotlin.multiplatform` with `iosArm64` + `iosSimulatorArm64` targets. `CatalogFileStream` now uses KMP-friendly `ByteReadChannel`; JVM-only APIs (OkHttp, `java.io.InputStream`, `java.net.URLEncoder`, `java.util.Base64`, `java.time.Instant`) replaced with Ktor/KMP equivalents or expect/actual. JVM tests stay `jvmTest`; `iosSimulatorArm64Test` wired in CI. `core/network` (OkHttp shim) remains JVM-only.

2. ~~**`core/dictionary` language packs**~~ — ✅ **done** (#945): iOS target added, `LanguageCatalogTest` now `commonTest` on `iosSimulatorArm64`.

3. **Stub iOS ViewModels — real feature gaps.** The following screens exist on iOS only as
   thin stub ViewModels; the full behaviour lives in `:app`. Bringing iOS to parity means
   porting the Android implementation into a shared module (with `expect/actual` seams where it
   touches Android APIs), then unifying the tests. Sizes are `:app` vs shared stub:
   - `CbzReaderViewModel` — 896L vs 85L, **Android-graphics-locked** (needs image-decode
     `expect/actual`). Largest/hardest.
   - `LibraryItemDetailViewModel` — 1120L vs 102L.
   - `SettingsViewModel` — 561L vs 44L (one `android.content.Context` seam).
   - `DownloadsViewModel` — 229L vs 85L.
   - `LibraryItemsViewModel` — 490L vs 422L (moderate divergence; most tractable).
   Plus two cross-module relocations with no stub gap but a placement problem (`:shared` is not
   on `:app`'s classpath): `AnnotationsListViewModel` (72L, logic identical) → move to
   `:feature:library`; and the reconciliation for `LibraryItemsViewModel` above.

4. *(To be appended as further BIG migrations surface during batch execution.)*

Small migrations (single pure class moves) are **not** listed here — they are done
inline as part of category-B batches.

---

## 6. Proposed execution batches (after audit approval)

Ordered by leverage and by unblocking. Each batch ends green
(`./gradlew test jvmTest <module>:iosSimulatorArm64Test` + `xcodebuild test`) and
updates Appendix A.

- **Batch 0 — CI wiring (§3).** Run every iOS-targeted module's `commonTest` on
  `iosSimulatorArm64Test` in CI. Small. Protects everything after.
- **Batch 1 — Reconcile already-migrated duplicates (B, subset).** Delete/merge the
  `:app` copies that already have a `feature/*` `commonTest`. Establishes the
  pattern; low risk.
- **Batch 2 — Library domain (B).** `feature/library` ViewModels + helpers
  (`LibraryItemsViewModel`, `LibraryFilterEngine`, `LibraryItemDetail*`, facets,
  sections). High-value, mostly framework-free.
- **Batch 3 — Audiobook/player domain (B).** `feature/audiobook`/`feature/player`
  resolvers, sleep timer, reconciliation, bookmark VM.
- **Batch 4 — Reader pure logic (B).** CFI, TOC, highlight/emphasis merge,
  footnotes, rail segments, formatting mappers, session orchestrators.
- **Batch 5 — Reader glue triage (B/D).** Split JS/DOM logic → `commonTest`;
  device execution → XCTest.
- **Batch 6 — Instrumentation → XCTest (D).** Feature-by-feature SwiftUI + Readium
  bridge XCTests, each with a scenario doc; skip/param those gated on absent iOS
  capability and record the gap.
- **Batch 7 — Catalog/network BIG migration (C).** Only if approved as in-scope.

Sizing note: batches 2–4 alone are ~1500 tests of logic movement. This is a
multi-PR programme, not a single change.

---

## 7. Keeping the suites in sync (going forward)

Requirement: Android and iOS must cover the same functionality, and stay that way.

- **Rule (already in CLAUDE.md):** every feature/fix logic lives in `commonMain`
  with a `commonTest`; every Android harness test gets a matching
  `docs/testing/ios-scenarios/*.md` + XCTest. This audit adds the enforcement hooks:
- **New guardrail (proposed):** a `checkParityScenarios` build check that fails if
  an `androidTest` class has no referenced scenario doc, mirroring the existing
  `checkTestGuardrails` philosophy. (Design in a follow-up; not built by this audit.)
- **This document** is the living ledger: any PR that adds an Android test must add
  its row here with the iOS status, and any PR that closes a gap flips the row.

---

## Progress log

### Batch 0 — CI wiring + shared-test repair (DONE, commit `868a0b965`)
- **Proved the §3 gap has teeth:** because iOS CI only ran `core:database`/`core:sync`
  `commonTest`, every other iOS-targeted module's shared tests had never executed on
  iOS. `shared/commonTest` had rotted since #941 (wrong-package imports for
  `EmbeddedFigure`/`ScreenDimensionBucket`; drifted `LibraryRefresher`/`TokenStorage`
  fakes) and did not even compile for iOS. Two tests were wrong on first-ever run
  (`ReaderNavRoutingTest` CBZ→Reader vs actual `CbzReader`; `initialStateIsLoading`
  running init inline on `Main.immediate`). All fixed.
- **CI now runs** `iosSimulatorArm64Test` for `shared`, `feature:library`,
  `feature:player`, `feature:reader`, `feature:source` (+ existing db/sync).
- **Result:** 136 iOS `commonTest`s green locally (shared 28, feature:library 29,
  feature:player 19, feature:reader 14, feature:source 7, core:sync 36, core:database 3).

### Batches 1–4 — REFRAMED (important)
The plan assumed these were "move a `:app` test into `commonTest`." Investigation shows
they are **production-class consolidation**: **9 core ViewModels exist twice** — an
Android copy in `:app/main` (wired to Compose) *and* a parallel shared copy (wired to
iOS SwiftUI): `LibraryItemsViewModel`, `LibraryItemDetailViewModel`, `SettingsViewModel`,
`CollectionDetailViewModel`, `SeriesDetailViewModel`, `CbzReaderViewModel`,
`AnnotationsListViewModel`, `DownloadsViewModel`, `LibrarySectionViewModel`. The two
implementations can (and do) drift; `:app` JVM tests cover the Android copy while
`commonTest` covers the shared copy, so "parity" requires **consolidating each pair into
one shared class, rewiring the Android app onto it, deleting the `:app` copy, and unifying
the tests.** That is real refactoring with Android-UI regression risk — per CLAUDE.md it
needs AVD verification (a build), not just JVM/iOS test runs. Sizing and risk are well
above a mechanical test port; recommend proceeding one ViewModel at a time, each with an
Android build+AVD check.

### Batch 1 — ViewModel consolidation (IN PROGRESS)
Device-verification path established: the ABS dev server returns an empty reply on
`POST /login` from this host (Tailscale path issue — unrelated to app code), so ABS-backed
screens can't be driven on the emulator. **Komga works** (GET Basic auth over Tailscale):
bridge `localhost:25600 → media-server:25600` via socat + `adb reverse`, add Komga in-app,
and its series/collections drive the shared library/detail screens on the AVD.

- **`SeriesDetailViewModel` — DONE (commit `eb40fb0ed`).** App copy was byte-identical to the
  shared class; pointed Android Koin + `SeriesDetailScreen` at the shared VM, deleted the
  `:app` copy. Moved the failed-refresh polling regression (4 tests) into feature:library
  `commonTest`. Verified: 33 tests green on iOS+JVM; `:app:assembleDebug` OK; **live on AVD** —
  opened a Komga series, `SeriesDetailScreen` rendered the full issue grid via the shared VM.
- **`CollectionDetailViewModel` — DONE (commit `e593c8478`).** Byte-identical to shared;
  swapped Android Koin + screen onto it, deleted `:app` copy, ported all 8 tests (offline
  filtering, connectivity refilter, polling) into feature:library `commonTest`. 41 tests green
  on iOS+JVM; `:app:compileDebugKotlin` OK.
- **`LibrarySectionViewModel` — DONE (commit `d670c6aa2`).** Byte-identical to shared; swapped
  Koin (libraryId + sectionType enum from SavedStateHandle) + screen, deleted `:app` copy. The
  `:app` test was redundant (tested the `librarySectionItems` helper already covered by
  `LibrarySectionItemsTest`), removed with `Removed-test:` trailers. Build green.

#### The other 6 VMs are BIG migrations (see §5) — they do NOT consolidate with a swap
The three done above worked only because their production class already lived in
**`:feature:library`** (which `:app` depends on) with logic byte-identical to the Android copy.
The remaining six do not meet those conditions:

- **Module-target topology constraint (discovered via AnnotationsListViewModel, commit
  `adaecc86b`).** `:feature:*` modules are **jvm+ios**; `:core:data` is **android+ios**. They share
  only `ios`, so a `:feature:*` module's jvm variant cannot depend on `:core:data`. That is the
  root reason the `:shared` VMs are duplicated: `:shared` (ios-only) reaches `:core:data`'s ios
  target, `:app` (android) reaches its android target, and no jvm+ios feature module can host code
  that touches `:core:data`. **Fix pattern:** pure interfaces + DTOs a shared VM needs must first be
  moved from `:core:data` to `:core:domain`/`:core:models` (both jvm+ios; impl stays in
  `:core:data`). AnnotationsListViewModel needed `AnnotatedBook` + `AnnotationsLibraryRepository`
  moved to `:core:domain`. Expect the same for the other `:shared` VMs.
- **`:app` does not depend on `:shared`.** `:shared` is the iOS aggregator module (only the iOS
  app consumes it). Five of the six shared VMs (`AnnotationsListViewModel`,
  `LibraryItemsViewModel`, `LibraryItemDetailViewModel`, `SettingsViewModel`,
  `DownloadsViewModel`) live in `:shared`, so `:app` cannot reference them. Consolidating any of
  them requires first **relocating the class (+ its UiState/support types) into a `:feature:*`
  module**, then updating `shared/iosMain/Koin.kt` and any `iosApp` Swift references.
- **The shared copies are stubs, not full implementations.** iOS is missing real functionality:
  `LibraryItemDetailViewModel` shared **102L** vs `:app` **1120L**; `SettingsViewModel` **44L**
  vs **561L**; `DownloadsViewModel` **85L** vs **229L**; `CbzReaderViewModel` (in
  `:feature:reader`) **85L** stub vs `:app` **896L**. Consolidation here means **porting the full
  Android implementation into `commonMain`**, not deleting a duplicate.
- **`CbzReaderViewModel` — DONE (#953).** Was Android-graphics-locked (`android.graphics.Bitmap`/
  `BitmapFactory`/`Color`, `android.app.Application`, `java.io.File`). Now fully consolidated into
  `:feature:reader/commonMain` as a plain `ViewModel`: image decode goes through the shared
  `ColorPageDecoder`/`PageImageDecoder` seams (Android `BitmapFactory`, iOS CoreGraphics); the
  pixel-energy SMART_SPLIT math is `commonMain`; `CbzRepository`/`ComicArchive` I/O is behind
  `ComicPageSource` (no `File` in the VM); the locator uses `kotlinx.serialization`; volume/reader
  collaborators + `AppearanceCoordinator`/`UpdateReadingProgress` were lifted to `commonMain`. iOS
  runs the same VM (Koin factory) with `IosCbzRepository` + no-op stores, plus a real
  `PanelEngine` (CoreGraphics decoder + in-memory store). Pure-logic tests run on JVM + iOS;
  existing `ComicsReaderTests.swift` still covers the iOS reader UI. Not yet device-verified
  (build/install gated).
- `AnnotationsListViewModel` — **DONE + device-verified (commits `adaecc86b`, `3e64a843d`).**
  Relocated `:shared` → `:feature:library` (uncovered the topology constraint below).
  feature:library **45 tests green on iOS+JVM**; app unit + androidTest compile;
  `assembleDebug`/`assembleDebugAndroidTest` OK; **`AnnotationsListHeaderTest` passes on the AVD**
  (the relocated `AnnotationsListUiState`/`AnnotatedBook`/screen render on-device). The full
  ABS-login→create-highlight→populated-tab e2e is not runnable here (ABS login response is
  mangled over the Tailscale DERP relay — env, not code).
- `LibraryItemsViewModel` — **DONE (issue #949).** Consolidated onto one shared class at
  `:feature:library`/`commonMain`; the `:shared` and `:app` copies are deleted, and the support
  types it depends on (`LibraryFilterEngine`, `LibraryProjection`, `LibrarySortMode`,
  `LibraryTabVisibility`, `AnnotationSearch*`) moved with it. `ToReadRepository` was lifted from
  `:core:data` to `:core:domain` (typealias shim left behind, mirroring `PlaylistsRepository`) so a
  KMP feature module can depend on the interface. The reconciliation kept the `:shared` shape
  (`libraryId: String` + a plain `MutableStateFlow` search query, no Android `SavedStateHandle`) —
  matching every other consolidated VM (`SeriesDetailViewModel` etc.). `LibrarySortMode` is now a
  bare enum; the Android string-resource label mapping lives in `:app`
  (`LibrarySortMode.labelResId`). Android reads `libraryId` from `SavedStateHandle` at the Koin call
  site. The 74 `:app` tests were ported to `commonTest` (**72** — the two `SavedStateHandle`
  process-death search-restore tests were retired with the seam; process-death search restoration
  is the one intentional behaviour drop) and the former `:shared` `LibraryItemsViewModelRefreshCrashTest`
  moved alongside. **72 + 1 tests green on both iOS (`iosSimulatorArm64Test`) and JVM.** Fixed a
  latent crash-safety bug uncovered by running the crash test on JVM for the first time: a thrown
  refresh dependency could surface as a swallowed `CancellationException`, leaving the offline
  banner un-set — `runRefresh` now guards each dependency so any genuine failure is captured.

### Batch 4 (partial) — Reader rail-segments cluster (DONE)
First category-B logic migration (#946). The whole-book chapter-rail generator and its CBZ/PDF
siblings were pure Kotlin trapped in `:app` only because they lived there. Moved verbatim into
`feature/reader/commonMain` (package `com.riffle.feature.reader`):

- `RailSegment`, `RailSegmentGenerator` (`buildRailSegments`, `findActiveSegmentIndex`,
  weighting/cursor/bounds math), `CbzRailSegments`, `PdfRailSegments`.
- The one non-common dependency, `core:domain`'s `normalizeEpubHref` (used `java.net.URI`), moved
  from `jvmMain` to `commonMain` with the URL-path extraction isolated behind an
  `internal expect fun uriPath` — JVM actual is byte-identical (`java.net.URI(raw).path`), iOS
  actual uses `NSURL.URLWithString(raw)?.path`. `resolveEpubHref`/`epubCfiToSpineIndex` came along
  for free and are now iOS-visible too.
- `:app` keeps thin `com.riffle.app.feature.reader` shims (typealias + forwarding funcs, the
  `AbsTargetResolver` idiom) so no consumer edited.

Tests moved to `feature/reader/commonTest` and now run on **iOS + JVM** (105 executing, 0 failures
each): `RailSegmentGeneratorTest` (79), `CbzRailSegmentsTest` (13), `PdfRailSegmentsTest` (13),
`RailSegmentInvariantTest` (1). JUnit→`kotlin.test` (assert message args reordered to last). Six
`RailSegmentGeneratorTest` names lost `,`/`()` (illegal in Kotlin/Native identifiers) — renamed,
behaviour unchanged, declared via `Removed-test:` trailers. `RailCorpusTest` (2, golden real-scale
corpus) **stays in `:app` jvmTest**: it loads JSON fixtures via `javaClass.getResourceAsStream` and
`kotlinx.serialization`; it still pins the moved `buildRailSegments` through the shim.

### Batch 5 (partial) — Cadence reader-glue cluster (DONE, #947)
First category **B/D** triage batch. The Cadence sub-package (`com.riffle.app.feature.reader.cadence`)
is the reader's word-cadence auto-follow: it produces the JS injected into the reader WebView,
parses the JSON the WebView returns, and drives a `WpmTicker` from the tokenised sentence map. Per
the §4 B/D rule, the JS-*producing* Kotlin and the JSON-*parsing* Kotlin are portable — the iOS
reader injects the identical JS into its Readium-Swift/WebKit navigator — so the whole cluster moved
to `feature/reader/commonMain` (package `com.riffle.feature.reader.cadence`), verified on **iOS + JVM**:

- `CadenceController` (drives `WpmTicker`, pure coroutines + `core:domain`), `DomSentenceSource`
  (`SentenceSource` handle, pure `CompletableDeferred`), `CadenceDomScript` (the injected JS strings +
  `parseCadenceStartId`), `CadenceInjector` (WebView-JSON → typed maps), and the
  `resolveCadenceStartRef` free function (extracted from `EpubReaderViewModel.kt`) → new
  `CadenceStartRefResolver.kt`. `CadenceUi.kt` (a Compose Composable) stays in `:app`.
- **Two JVM-only glue points isolated during the move:** `CadenceInjector.parse` and
  `CadenceDomScript.parseCadenceStartId` used `org.json.JSONObject`; both were rewritten with
  `kotlinx.serialization.json` (KMP-native, added to `feature/reader` `commonMain`). Parse behaviour
  is pinned by `CadenceInjectorTest` (6) + `CadenceDomScriptTest` (18) — same asserts, both platforms.
  The one diagnostic `android.util.Log.d(...)` line inside `parseCadenceStartId` (wrapped in
  `runCatching`, not test-covered) was dropped — `feature/reader`'s JVM target has no Android SDK and
  the log was diagnostic-only.
- `:app` keeps thin `com.riffle.app.feature.reader.cadence` shims (typealias/forwarder). Consumers of
  `CadenceInjector.Result` (nested type, which an object-typealias doesn't re-export) were repointed to
  the new package in `EpubReaderScreen.kt`.

Tests moved to `feature/reader/commonTest` (JUnit→`kotlin.test`, message args reordered last): 48
tests total, **0 failures on iOS + JVM** — `CadenceControllerTest` (13), `CadenceDomScriptTest` (18),
`CadenceInjectorTest` (6), `CadenceStartRefResolverTest` (8), `DomSentenceSourceTest` (3). Seven test
names carried `,`/`()` (illegal in Kotlin/Native identifiers) — renamed (behaviour unchanged),
declared via `Removed-test:` trailers.

#### B/D triage roadmap — remaining 26 files
First-pass classification from the production-class dependency scan (Android/Readium imports in the
class-under-test). Each remaining cluster is its own follow-up PR under #947; verify the exact
placement with a code read before moving, as several NOFILE rows test logic defined inside a larger
Android class (e.g. `ContinuousReaderView`) that must be extracted first.

- **Shared → `commonTest` (pure Kotlin / JS-string / DOM-math, no Android in the class-under-test):**
  `ReaderWebViewScripts` (43), `FigureTapScript` (12), `EmphasisDomInjector` (2), `HighlightsDomPatch`
  (13), `RendererCapability` (10), `ReturnNavigator` (5), `ContinuousPositionTracker` (76),
  `ContinuousScriptInjector` (9), `ContinuousDecorationController` (13), `ContinuousModeLocator` (12).
- **Shared with a small `expect/actual`/interface seam (1–2 Android imports to isolate, à la #946's
  `uriPath`):** `ContinuousStyleInjector` (97, mostly JS strings), `ContinuousHighlightRenderer` (26).
- **Readium/Presenter-coupled → assess seam vs iOS XCTest:** `ReadiumHighlightRenderer` (14),
  `ReadiumPresenter` (5), `ContinuousPresenter` (18), `FakeReaderPresenter` (16),
  `FragmentConfigurationMapper` (6), `ReadiumVersionPin` (1).
- **Android-WebView/Fragment glue → iOS XCTest + scenario doc:** `ChapterWebViewBinder` (6),
  `ChapterWebViewPageOwnership` (1), `ReaderFragmentCommitVariant` (2),
  `ContinuousAnnotationFocusReflowRace` (11), `ContinuousCrossReferenceTap` (5),
  `ContinuousPlayFromHere` (5), `ContinuousResumeTouchWiring` (2),
  `CadenceController`/`Cadence*` — done above; `CadenceInjector`/`CadenceDomScript` done above.

### 2026-09-07 — Source-onboarding consolidation + the e2e test that made iOS function

- **Shared UI proven:** new `:feature:source-ui` (androidTarget+ios, Compose Multiplatform
  resources) renders the real picker/add/select-libraries screens on BOTH platforms; the iOS
  BasicText skeleton for this surface is deleted. Pattern to repeat per surface (library,
  settings, downloads, detail).
- **iOS test suite made real:** new `iosAppUnitTests` Xcode target (10 Swift test files had
  never compiled); 8 trivial files deleted; `core:database` iosTest source set was orphaned and
  never ran — wired in, which exposed fabricated schema assertions.
- **The e2e payoff:** `AddAbsSourceFlowTests` (add-ABS flow against the live test server)
  bubbled up and fixed four production defects that made iOS non-functional: missing refresh
  trigger on library open, no-op `refreshLibraryItems`, `flowOf(emptyList())` library observers,
  and a series/collections schema (fabricated `sourceId`, missing `coverUrl`/`bookCount`) that
  rejected every write and, via `refreshFailed` → offline filter, blanked the whole library.
  iOS schema v2→v3 rebuilds those tables; entity→domain item mapping extracted to `commonMain`.
- Restored the #908 regression (add-ABS flow) and added `NSAllowsArbitraryLoads` for parity
  with Android's cleartext policy.

### 2026-09-06 — Full re-audit: CI closure, reverse ports, iOS test quality

- **CI wiring finished (§3):** `:feature:settings` and `:feature:downloads` added to the
  `unit-test` job; `feature/settings` commonTest needed three test renames to compile for
  Kotlin/Native (`,`/`()` illegal in K/N identifiers). 42 tests now green on `iosSimulatorArm64`.
- **Phantom Android coverage found (§3b):** `core/data/src/androidTest` (20 tests) is not
  compiled by any Gradle task; needs device-test wiring or an `androidHostTest` rewrite.
- **Reverse parity (iOS → Android) closed.** iOS is otherwise a strict subset; the only
  iOS-only assertions were ported back:
  - `RiffleDatabaseSchemaTablesTest` (`core/database/jvmTest`) — Room `sqlite_master` table
    set pinned exactly, mirroring `IosRiffleDatabaseSchemaTest.allDaoTablesExistAfterSchemaCreate`
    / `noSpuriousTablesAreCreated`.
  - `AndroidEpubNavigatorCloseTest` (`app/test`) — post-`close()` emissions must not reach
    consumers, mirroring `EpubReaderTests.testClearingCallbacksStopsFiring`.
  - `HighlightCssRgbaTest` (`app/test`) — ARGB→CSS `rgba()` channel extraction per
    `HighlightColor` token, mirroring `AnnotationTests.testHexColorRed/Green/Blue`.
- **iOS suite quality (audit result):** of 103 XCTest funcs, ~48 are trivial (constant echoes,
  non-nil checks on non-optionals, `XCTSkip` placeholders that always report green, no-assertion
  "doesn't crash" tests) and 11 XCUITests self-skip without a live server. Only ~30 assert real
  behaviour, and most duplicate Kotlin tests that already run on iOS via `commonTest`.
  Known-bad test: `IosRiffleDatabaseSchemaTest.migrateV1ToV2CreatesLocalFilesTables` is
  self-defeating (driver already at v2 + `CREATE TABLE IF NOT EXISTS` — passes even if the
  migration body is deleted). Dead fixture: `AutoFollowJsTests.twoAdjacentHtml` (the
  anti-bounce scenario was never implemented; Android has it plus 3 column-snap tests iOS lacks).

Status legend: ✅ covered on iOS · 🟡 partially / needs CI wiring · ❌ not covered.
All rows start at their category default; flip as batches land.

<!-- BEGIN MATRIX -->

### A-shared(runs on iOS)  — 21 files / 136 tests


**`core/database`**

- `LegacyFolderLibraryIdTest` (3)

**`core/sync`**

- `AnnotationSyncStatusStoreTest` (7)
- `AudiobookBookmarkReconcilerTest` (9)
- `ProgressSweepBookmarkTest` (4)
- `ProgressSweepTest` (10)
- `ReconcileLocksTest` (6)

**`feature/library`**

- `CollectionDetailViewModelTest` (4)
- `FacetMatchesTest` (6)
- `HomeViewModelStartDestinationTest` (3)
- `LibraryItemsViewModelTest` (72)
- `LibraryItemsViewModelRefreshCrashTest` (1)
- `LibrarySectionItemsTest` (5)
- `SeriesDetailViewModelTest` (4)
- `UrlDecodeTest` (7)

**`feature/player`**

- `AudiobookProgressUtilsTest` (19)

**`feature/reader`**

- `CbzReaderViewModelTest` (14)
- `RailSegmentGeneratorTest` (79) — migrated from `:app` (#946)
- `CbzRailSegmentsTest` (13) — migrated from `:app` (#946)
- `PdfRailSegmentsTest` (13) — migrated from `:app` (#946)
- `RailSegmentInvariantTest` (1) — migrated from `:app` (#946)
- `CadenceControllerTest` (13) — migrated from `:app` (#947)
- `CadenceDomScriptTest` (18) — migrated from `:app` (#947)
- `CadenceInjectorTest` (6) — migrated from `:app` (#947)
- `CadenceStartRefResolverTest` (8) — migrated from `:app` (#947)
- `DomSentenceSourceTest` (3) — migrated from `:app` (#947)

**`feature/source`**

- `SourceTypePickerViewModelTest` (7)

**`shared`**

- `AnnotationDecorationMapperTest` (7)
- `DrawerViewModelTest` (5)
- `LibraryItemDetailViewModelTest` (4)
- `NormalizeAbsUrlTest` (6)
- `ReaderNavRoutingTest` (5)

### A?-commonTest(non-ios module)  — 1 files / 6 tests


**`core/dictionary`**

- `LanguageCatalogTest` (6)

### B-app pure-logic -> move feature/commonMain + commonTest  — 220 files / 2140 tests


**`app`**

- `AbsTargetResolverTest` (4)
- `AbsolutePositionPlayerTest` (2)
- `AddChitankaViewModelTest` (4)
- `AddGutenbergViewModelTest` (3)
- `AddSourceCopyTest` (4)
- `AddSourceViewModelTest` (22)
- `AlignedReaderWidthTest` (8)
- `AnnotationSearchTest` (9)
- `AnnotationSearchViewModelTest` (1)
- `AnnotationSessionTest` (43)
- `AnnotationSyncMaintenanceViewModelTest` (6)
- `AnnotationSyncWorkerOutcomeMappingTest` (7)
- `AnnotationTapUrlTest` (8)
- `AnnotationsListViewModelTest` (4)
- `AnnotationsPanelMaxLinesTest` (3)
- `AnnotationsPanelRowKindTest` (6)
- `AnnotationsPanelSplitTest` (12)
- `AppLanguageTest` (3)
- `AudioClockTickerTest` (6)
- `AudiobookFollowTest` (10)
- `AudiobookPlayerViewModelBookmarkTest` (23)
- `AudiobookProgressFractionTest` (6)
- `AudiobookReconciliationCoordinatorTest` (9)
- `AudiobookResumeResolverTest` (12)
- `AudiobookResumeSecTest` (11)
- `AuthHeaderTest` (5)
- `AutoScrollControllerTest` (6)
- `AutoScrollHudPillPaddingTest` (1)
- `BitmapDownsampleTest` (4)
- `BookImportManagerTest` (5)
- `BookmarksControllerTest` (17)
- `BuildAudiobookFactsTest` (6)
- `CaptionHighlightUpgraderTest` (10)
- `CbzArchiveSwapTest` (8)
- `CbzPageContentTest` (6)
- `CbzPageGestureActionTest` (4)
- `CbzSampledDecodeTest` (6)
- `CfiSyncContractTest` (17)
- `ChangelogViewModelTest` (4)
- `ChapterMapOverlayLabelTest` (3)
- `ChapterNavigationRailTest` (6)
- `ChapterWindowManagerTest` (40)
- `ChitankaBrowseViewModelTest` (29)
- `CollectionDetailViewModelTest` (8)
- `CombineDraftEmphasisStylesTest` (6)
- `ComicBackgroundThemeTest` (3)
- `ComicDisplaySummaryTest` (10)
- `ComicFormattingOverridesMergeTest` (2)
- `ComputeTotalProgressionTest` (12)
- `CrashReportShareSubjectTest` (1)
- `DebugLogDisplayOrderTest` (2)
- `DecodeWithRetryTest` (3)
- `DefaultApplicationScopeTest` (3)
- `DetailRouteEncodingTest` (13)
- `DeveloperOptionsTapCounterTest` (3)
- `DictionaryPacksViewModelTest` (9)
- `DoubleTapZoomTranslationTest` (4)
- `DownloadManagerTest` (9)
- `DownloadsViewModelTest` (2)
- `DraftPopupSelectionTest` (15)
- `EmphasisMergeTest` (15)
- `EmptyScanMessageTest` (2)
- `EpubCfiRangeTest` (16)
- `EpubCfiTest` (25)
- `EpubReaderViewModelEmbeddedFiguresTest` (2)
- `EpubReaderViewModelFootnoteTest` (14)
- `EpubReaderViewModelHighlightsSourceTest` (38)
- `EpubReaderViewModelHighlightsSuppressionTest` (2)
- `EpubReaderViewModelImageAnnotationTest` (7)
- `EpubReaderViewModelTest` (66)
- `ExtractEpubTocUseCaseCacheTest` (3)
- `ExtractEpubTocUseCaseTest` (11)
- `ExtractPdfPageCountUseCaseTest` (3)
- `FadingScrollbarMetricsTest` (11)
- `FetchAudiobookChaptersUseCaseTest` (5)
- `FigureBorderDecorationTest` (17)
- `FigureBorderInjectionTest` (7)
- `FigureCaptionWalkerTest` (14)
- `FigureTapBridgeTest` (8)
- `FigureZoomTest` (13)
- `FiguresInHtmlRangeTest` (12)
- `FileCrashReportSenderTest` (5)
- `FilterFacetTest` (7)
- `FollowLoopOrchestratorTest` (13)
- `FootnoteResolverTest` (39)
- `FormattingSessionTest` (24)
- `GutenbergBrowseScreenFilterTest` (1)
- `GutenbergBrowseViewModelTest` (5)
- `HealGenericOriginFontsTest` (1)
- `HighlightMergeTest` (27)
- `HighlightOverlapTest` (11)
- `HighlightRangeOverlapTest` (24)
- `HighlightTintTest` (1)
- `HighlightsLiveUpdateObserverTest` (5)
- `HighlightsPdfExporterTest` (13)
- `HighlightsPublicationFactoryImageTest` (14)
- `HighlightsPublicationFactoryTest` (39)
- `HighlightsUiSuppressionTest` (3)
- `HomeLeadingSectionKeyTest` (6)
- `HomeViewModelTest` (16)
- `ImmersiveModeStateTest` (21)
- `InitialLocatorSelectionTest` (6)
- `IsReaderRouteTest` (7)
- `KoinModuleVerificationTest` (1)
- `LibraryBackActionTest` (5)
- `LibraryFilterEngineTest` (26)
- `LibraryItemDetailPublicationFactsTest` (14)
- `LibraryItemDetailReadaloudDownloadTest` (1)
- `LibraryItemDetailViewModelTest` (59)
- `LibraryItemDetailViewModelTocTest` (10)
- `LibraryItemImportProgressTest` (5)
- `LibraryLocalizationResourceTest` (2)
- `LibrarySectionViewModelTest` (4)
- `LibraryTabBarAnnotationsTest` (1)
- `LibraryTabVisibilityObserverTest` (6)
- `LibraryTabVisibilityTest` (9)
- `LookupUiStateResolutionTest` (7)
- `MediaSourceFactoryHandlesTest` (3)
- `NarratedColumnProgressionTest` (11)
- `NarratedColumnsResultParserTest` (13)
- `NavigationDrawerSourceSubtitleTest` (7)
- `NavigationDrawerViewModelTest` (17)
- `NetworkImageSourceTest` (13)
- `NoteGlyphDecorationTest` (8)
- `NowPlayingStoreTest` (6)
- `OnDeviceReproTest` (1)
- `OrchestratorScopeTest` (1)
- `OwnedItemMatcherTest` (33)
- `PageScrollCoalescerTest` (7)
- `PagedDirectionalNavigationAdapterTest` (1)
- `PanelReportOutlineStyleTest` (4)
- `PanelReportViewModelTest` (26)
- `PanelShouldSnapTest` (1)
- `PdfLocatorGateTest` (4)
- `PdfReaderViewModelFormattingTest` (1)
- `PdfTocAdapterTest` (7)
- `PdfiumPreferencesMapperTest` (5)
- `PendingSeekGateTest` (4)
- `PersistedAnnotationRenderingTest` (2)
- `PlaybackSpeedTest` (10)
- `PlayerCoordinatorScopeTest` (1)
- `PluralityOriginFontTest` (7)
- `PopupDismissedTapTest` (4)
- `PositionOrchestratorTest` (11)
- `PositionSaveCoordinatorTest` (5)
- `ProgressFlushScopeTest` (2)
- `PublisherFontFaceExtractorTest` (7)
- `RailCorpusTest` (2)
- `ReadaloudAudioAnchorTest` (9)
- `ReadaloudControlStateTest` (4)
- `ReadaloudHighlightDecorationTest` (8)
- `ReadaloudLocatorTest` (3)
- `ReadaloudParkPolicyTest` (10)
- `ReadaloudReserveTest` (7)
- `ReadaloudRowSummaryTest` (8)
- `ReadaloudSessionTest` (22)
- `ReadaloudStartAnchorTest` (5)
- `ReaderContainerPaddingTest` (8)
- `ReaderCoordinatesTest` (9)
- `ReaderModeForkGuardTest` (1)
- `ReaderRouterTest` (6)
- `ReaderSessionLifecycleTest` (17)
- `ReaderSettingsSummariesTest` (13)
- `ReaderSyncCoordinatorTest` (12)
- `ReaderThemePaletteTest` (5)
- `ReaderViewportAlignmentTest` (5)
- `ReconnectSyncKickerTest` (1)
- `ReleaseDateTest` (3)
- `RenderCapabilitiesTest` (2)
- `ResumePlaybackGateTest` (3)
- `ResumeRestorerTest` (14)
- `ReturnNavEffectKeysTest` (3)
- `RiffleApplicationStartupTest` (2)
- `RiffleApplicationTest` (3)
- `RiffleImageLoaderTest` (4)
- `ScopeSentencesToChapterTest` (4)
- `ScreenDimensionBucketMapperTest` (4)
- `SearchControllerTest` (7)
- `SearchToggleTest` (3)
- `SectionPreviewCountTest` (10)
- `SelectionHandoffLatchTest` (4)
- `SelectionSuppressedTapTest` (5)
- `SentencePlaybackControllerRememberKeysTest` (1)
- `SentencePlaybackControllerTest` (9)
- `SeriesDetailViewModelTest` (4)
- `SeriesPositionBadgeTest` (2)
- `ServerJumpCoordinatorTest` (5)
- `SettingsViewModelTest` (40)
- `ShouldInterceptBackForDrawerTest` (23)
- `SidecarSentenceSourceTest` (2)
- `SleepTimerTest` (12)
- `SmartSeamHoldTest` (4)
- `SourceIconResolverTest` (22)
- `SourceSwitchNavigationTest` (3)
- `SourceTypePickerTest` (12)
- `StalePageDecodeTest` (3)
- `StartupUpdateViewModelTest` (12)
- `SteppedTypographyValueTest` (6)
- `SystemBarScrimTest` (1)
- `TimeFormatTest` (6)
- `TocActiveEntryTest` (14)
- `TocFlattenTest` (9)
- `TypographyOverrideTest` (9)
- `UiStateTest` (7)
- `UnifiedSliderLabelsTest` (7)
- `UploadDestinationVisibilityTest` (5)
- `VolumeKeyDispatcherTest` (3)
- `VolumeKeyEventHandlerTest` (20)
- `WakeLockControllerTest` (4)
- `WebSourceLibraryViewModelTest` (2)
- `WebSourceUiTest` (3)
- `ZipEpubResourceFetcherTest` (7)
- `edge` (11)
- `exists` (3)
- `the` (4)

### B/D-app reader-glue (assess: shared-logic vs webview)  — 31 files / 464 tests


**`app`**

- `CadenceControllerTest` (13) — ✅ migrated to feature:reader commonTest (#947)
- `CadenceDomScriptTest` (18) — ✅ migrated to feature:reader commonTest (#947)
- `CadenceInjectorTest` (6) — ✅ migrated to feature:reader commonTest (#947)
- `CadenceStartRefResolverTest` (8) — ✅ migrated to feature:reader commonTest (#947)
- `ChapterWebViewBinderTest` (6)
- `ChapterWebViewPageOwnershipTest` (1)
- `ContinuousAnnotationFocusReflowRaceTest` (11)
- `ContinuousCrossReferenceTapTest` (5)
- `ContinuousDecorationControllerTest` (13)
- `ContinuousHighlightRendererTest` (26)
- `ContinuousModeLocatorTest` (12)
- `ContinuousPlayFromHereTest` (5)
- `ContinuousPositionTrackerTest` (76)
- `ContinuousPresenterTest` (18)
- `ContinuousResumeTouchWiringTest` (2)
- `ContinuousScriptInjectorTest` (9)
- `ContinuousStyleInjectorTest` (97)
- `DomSentenceSourceTest` (3) — ✅ migrated to feature:reader commonTest (#947)
- `EmphasisDomInjectorTest` (2)
- `FakeReaderPresenterTest` (16)
- `FigureTapScriptTest` (12)
- `FootnoteAnchorBridgeInstallScriptTest` (6)
- `FragmentConfigurationMapperTest` (6)
- `HighlightsDomPatchTest` (13)
- `ReaderFragmentCommitVariantTest` (2)
- `ReaderWebViewScriptsTest` (43)
- `ReadiumHighlightRendererTest` (14)
- `ReadiumPresenterTest` (5)
- `ReadiumVersionPinTest` (1)
- `RendererCapabilityTest` (10)
- `ReturnNavigatorTest` (5)

### C-catalog/net BIG migration  — 23 files / 361 tests — ✅ done (#944)


**`core/catalog`**

- `AbsCatalogTest` (61)
- `CatalogImportPreflightTest` (3)
- `CatalogProgressTest` (6)
- `DestinationItemMatcherTest` (4)
- `SeriesEntryOrderingTest` (7)

**`core/catalog-chitanka`**

- `ChitankaCatalogTest` (46)
- `ChitankaHttpClientTest` (25)
- `ChitankaScraperTest` (14)

**`core/catalog-gutenberg`**

- `GutenbergCatalogTest` (17)
- `GutenbergHttpClientTest` (7)
- `GutenbergParserTest` (6)

**`core/catalog-komga`**

- `KomgaCatalogTest` (26)
- `KomgaCbzPageStreamTest` (7)
- `KomgaPlaylistsCapabilityTest` (21)

**`core/catalog-radio-es`**

- `RadioEsCatalogTest` (44)
- `RadioEsHttpClientTest` (7)
- `RadioEsParserTest` (22)

**`core/network`**

- `AbsFileDownloadApiClientTest` (3)
- `AudiobookBundleApiTest` (4)
- `GitHubReleaseApiTest` (18)
- `HttpByteStreamerTest` (2)
- `StorytellerBundleApiTest` (7)
- `StorytellerPositionApiTest` (4)

### D-instr(webview/readium)->XCTest+scenario  — 31 files / 143 tests


**`app`**

- `AnnotationFocusHarnessTest` (6)
- `AnnotationMergeFormattingHarnessTest` (1)
- `AutoFollowJsTest` (12)
- `BackwardChapterSnapJsTest` (5)
- `BookmarkRibbonOverWebViewTest` (1)
- `ChapterWebViewSettingsTest` (6)
- `ContinuousAnnotationHighlightsJsTest` (4)
- `ContinuousAnnotationRenderHarnessTest` (1)
- `ContinuousChapterBoundaryHarnessTest` (3)
- `DismissEmptyHighlightHarnessTest` (4)
- `ElidedNoteCalloutWebViewTest` (2)
- `EpubCfiTranslatorInstrumentedTest` (15)
- `EpubHarnessTest` (4)
- `EpubSearchServiceTest` (11)
- `FigureTapScriptTest` (5)
- `GoldenTraceHarnessTest` (1)
- `HeightMeasurementJsTest` (2)
- `HighlightLineCoverageWebViewTest` (3)
- `LibrarySearchHeaderFocusTest` (4)
- `NarratedColumnsJsTest` (3)
- `NavigationSnapHarnessTest` (3)
- `NoteGlyphMarginWebViewTest` (2)
- `NoteGlyphRenderHarnessTest` (3)
- `OrientationFlipAnnotationHarnessTest` (2)
- `PdfHarnessTest` (2)
- `ReaderWebViewScriptsTest` (15)
- `SearchHarnessTest` (3)
- `TocIntegrationTest` (7)
- `TocParserTest` (4)
- `TypographyOverrideWebViewTest` (6)
- `WakeLockHarnessTest` (3)

### D-instr(compose-ui)->XCTest+scenario  — 53 files / 234 tests


**`app`**

- `AnnotationReopenInstrumentedTest` (1)
- `AnnotationsListHeaderTest` (1)
- `AudioPlaybackSpeedPersistenceTest` (5)
- `AudiobookPlayerSnackbarTest` (2)
- `BehaviorSectionRowHeightTest` (1)
- `BookCoverTileReadaloudTest` (2)
- `BookSectionGridTest` (5)
- `BookmarkIndicatorReopenInstrumentedTest` (3)
- `CadenceSettingsPanelTest` (6)
- `CbzThumbnailStripTest` (5)
- `ChapterRailIsolationTest` (4)
- `ChitankaSourceRowTest` (4)
- `ContinuousReaderViewSelectionInterceptTest` (11)
- `CornerBookmarkIndicatorTest` (6)
- `EpubNavigatorPreferencesTest` (3)
- `FormattingPreferencesMapperTest` (24)
- `HighlightActionsPopupTest` (8)
- `HighlightRendererSwapReapplyTest` (1)
- `ImmersiveOnReadaloudPlayTest` (4)
- `KoinModulesTest` (2)
- `LibraryItemDetailReadButtonStabilityTest` (1)
- `LibraryItemDetailTabletLayoutTest` (1)
- `LocalFilesSourceRowTrashTest` (4)
- `NavigateAsRootTest` (2)
- `NavigationDrawerGestureTest` (1)
- `OrientationChangeTest` (1)
- `PermanentNavigationDrawerTest` (2)
- `PlayerTitleYearTest` (3)
- `ReadaloudDataSourceRoutingTest` (1)
- `ReadaloudDownloadButtonTest` (2)
- `ReadaloudMiniPlayerTest` (7)
- `ReadaloudStreamingSessionFactoryAndroidTest` (3)
- `ReaderSemanticMatchersTest` (4)
- `ReaderSettingsSectionsTest` (9)
- `ReaderSettingsSheetCapabilitiesTest` (3)
- `ReflowReapplyGenerationTest` (3)
- `ScrollBoundaryNavigationContainerTest` (27)
- `SeriesDetailGridTest` (1)
- `ServerSettingsExpansionTest` (7)
- `SettleSnapInstallTest` (6)
- `ShareElidedViewTest` (6)
- `SourceTypePickerScreenTest` (5)
- `StreamingAudioDownloaderAndroidTest` (1)
- `StreamingPlaybackAndroidTest` (1)
- `StubAbsServerTest` (2)
- `SwipeToDeleteRowTest` (2)
- `TabletContentWidthContainerNoOpTest` (1)
- `UnboundedCatalogGridZoomTest` (3)
- `calculation` (1)
- `falls` (1)
- `guards` (5)

**`core/data`**

- `AnnotationSyncControllerIntegrationTest` (7)
- `LocalDirectoryTargetTest` (13)

### N/A-build lint (host-only)  — 7 files / 72 tests


**`buildSrc`**

- `AndroidImportLintTest` (17)
- `DatabaseImplLeakLintTest` (7)
- `LocalizationResourceLintTest` (4)
- `OkHttpConfinementLintTest` (6)
- `RiffleLogTagLintTest` (10)
- `ServerReferenceLintTest` (13)
- `TestGuardrailLintTest` (15)
<!-- END MATRIX -->
