# ReaderModeStrategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Collapse the 36 mode-branching call sites in `EpubReaderScreen.kt` by expanding the existing `ReaderPresenter` seam so the screen holds **one** presenter handle and stops discriminating on `isContinuous` / `orientation` for any concern the presenter can own.

**Architecture:** A `ReaderPresenter` interface already exists with two adapters (`ReadiumPresenter` for paginated+vertical, `ContinuousPresenter` for continuous). This plan widens that interface so all mode-divergent concerns currently branched in the screen — decoration application, navigation-target construction, volume-nav, readaloud-sentence snap, vertical boundary observation — live behind the seam.

**Tech Stack:** Kotlin, Jetpack Compose, Readium 3.3.0, Hilt, Android. Tests: JVM unit (Robolectric for Compose) + harness instrumentation (`make harness-test`).

## Global Constraints

- One PR per user request — slices commit incrementally on `pkmetski/reader-mode-strategy`.
- No behaviour change in any of the three modes. Visual + functional parity required.
- `make harness-test` and `make harness-test-tablet` must stay green.
- AGENTS.md rule: every change touching reading behaviour must be verified across all three modes (paginated, vertical, continuous).
- No new external dependencies.
- Internal-only visibility (the seam is already `internal`).

## State of the existing seam

`app/src/main/kotlin/com/riffle/app/feature/reader/presenter/ReaderPresenter.kt` (139 lines) defines:
- Event flows: `positionEvents`, `pageLoadEvents`, `tapEvents`, `linkEvents`, `selectionEvents`, `annotationTapEvents`.
- Commands: `navigateTo(NavigationTarget)`, `applyTypography(prefs)`, `snapshotPosition()`, `pageBy(direction)`.

`ReadiumPresenter` additionally exposes Readium-typed methods leaking out: `applyDecorations(List<Decoration>, group)`, `navigateToLocator(...)`, `navigateToLink(...)`, `attachmentStamp()`, `updateLayoutContext(...)`. These force the screen to know it has a `ReadiumPresenter` for several call sites.

## Branch inventory (36 sites in EpubReaderScreen.kt)

| Lines        | Concern                                  | Slice |
|--------------|------------------------------------------|-------|
| 1107–1131    | Presenter selection + HighlightRenderer  | 1     |
| 1555–1635    | NavigationTarget construction + nav events | 2   |
| 1792–1828    | Volume-nav dispatch                      | 3     |
| 1744–1790    | Readaloud auto-snap (paginated only)     | 4     |
| 1521, 2271   | Vertical boundary polling (`atForwardBoundary`) | 5 |
| 632, 1840–1858, 1934, 2012, 2053, 2174 | UI lifecycle (cover frame, fragment recreate, paged-only chrome) | 6 (audit) |

## Recalibration

After reading the code: `ReaderPresenter` (139 lines, `internal`) already exists with both adapters wired (`ReadiumPresenter`, `ContinuousPresenter`). The original "introduce a `ReaderModeStrategy` interface" framing was based on an outdated survey. Slice 1 ("unify presenter handle") was dropped because the two concrete handles remain justified by their use sites until slices 2–5 narrow them.

Issue #320 has been updated with this discovery.

## Slice plan

Each slice is one commit and is independently mergeable. The PR aggregates them.

---

### Slice 2 — Route navigation through presenter.navigateTo

**Files:**
- Modify: `app/.../reader/EpubReaderScreen.kt:1555–1635` — delete the `serverLocatorTarget` / `navigationTarget` `if (isContinuous)` factories (and the `ContinuousNavigationTarget` / `ReadiumNavigationTarget` helpers if they exist as separate types). Callers issue `presenter.navigateTo(NavigationTarget.ToLocatorJson(...))` etc.
- Modify: `app/.../reader/presenter/ContinuousPresenter.kt:93–110` — implement `ToLocatorJson` by delegating to `ContinuousReaderCoordinator`'s existing parse (lift the parse helper into a small package-private util if necessary).
- Modify: `app/.../reader/presenter/ReadiumPresenter.kt:152–160` — `navigateTo` honours `landAtStartWhenNoTarget` parity with `navigateToLocator(snap = true)` so the abstract entry point is sufficient for screen callers.

**Acceptance:**
- `serverLocatorEvents`, `returnNavEvents`, `searchNavigationEvents`, `annotationNavigationEvents` consumers all call `presenter.navigateTo(...)`.
- Server-progress resume, search-result navigation, return-to-position card, and annotation-tap nav all work in all three modes. Verify on AVD.
- `make harness-test` + `make harness-test-tablet` green.

**Commit:** `refactor(reader): route all reader navigation through presenter.navigateTo`

---

### Slice 3 — Unify volume-key paging

**Files:**
- Modify: `app/.../reader/EpubReaderScreen.kt:1792–1828` — replace the `if (isContinuous)` fork with a single `presenter.pageBy(direction)` call.

`ReaderPresenter.pageBy` already exists on both adapters; this slice is mostly deletion.

**Acceptance:**
- Volume keys page forward/backward in all three modes.
- Real-device test required per memory `reference_test_avd_chrome55_webview` (AVD's old WebView no-ops `goForward`). Add a TODO/note if device verification is deferred.

**Commit:** `refactor(reader): collapse volume-nav dispatch into presenter.pageBy`

---

### Slice 4 — Readaloud sentence snap behind presenter

**Files:**
- Modify: `app/.../reader/presenter/ReaderPresenter.kt` — add `suspend fun snapToReadaloudSentence(anchor: ReadaloudSentenceAnchor)`. Define `ReadaloudSentenceAnchor` (href, sentence-id, column-index hint) as the seam-side payload.
- Modify: `app/.../reader/presenter/ReadiumPresenter.kt` — implement by calling `NarratedColumnProgression.snapNarratedColumn(...)`.
- Modify: `app/.../reader/presenter/ContinuousPresenter.kt` — implement as a no-op (continuous has its own follow pipeline).
- Modify: `app/.../reader/EpubReaderScreen.kt:1744–1790` — replace `if (isContinuous) return@LaunchedEffect` + paginated-only snap with `presenter.snapToReadaloudSentence(...)`.

**Acceptance:**
- Readaloud auto-snap behaviour unchanged in paginated; no regression in vertical or continuous.
- Existing readaloud highlight tests pass.

**Commit:** `refactor(reader): route readaloud sentence snap through ReaderPresenter`

---

### Slice 5 — Vertical boundary observation behind presenter

**Files:**
- Modify: `app/.../reader/presenter/ReaderPresenter.kt` — add `val boundaryEvents: Flow<BoundaryState>` (data class with `atForwardBoundary: Boolean`, `atBackwardBoundary: Boolean`).
- Modify: `app/.../reader/presenter/ReadiumPresenter.kt` — emit boundary on each `onPageLoaded` (or via JS scroll polling for vertical, parity with current screen polling).
- Modify: `app/.../reader/presenter/ContinuousPresenter.kt` — emit boundary from `ContinuousReaderView` scroll state.
- Modify: `app/.../reader/EpubReaderScreen.kt:1521, 2271` — replace JS scroll polling with `presenter.boundaryEvents.collect { ... }`.

**Acceptance:**
- Chapter-boundary gesture interception (ADR 0014) behaves identically in vertical.
- No new emitter while the user is on a non-boundary page.

**Commit:** `refactor(reader): route boundary observation through ReaderPresenter`

---

### Slice 6 — Audit remaining branches + add lint guard

**Files:**
- Modify: `app/.../reader/EpubReaderScreen.kt` — review the ~6 remaining `isContinuous` / `orientation ==` checks. For each, either delete (covered by a prior slice), inline the justification with a comment, or move behind the presenter.
- Add: `app/.../reader/presenter/ReaderPresenterTests.kt` (or similar) — contract-style test that pins the surface area of `ReaderPresenter`.
- Add: a Detekt or simple unit-test guard that fails CI if a new `isContinuous` reference appears in `EpubReaderScreen.kt`. (Optional — costs little, prevents regression.)

**Acceptance:**
- `grep -nE 'isContinuous|orientation\s*==' EpubReaderScreen.kt` returns at most a handful of clearly-justified UI-lifecycle sites (e.g. fragment recreation for double-page) each with a `// MODE-FORK:` comment.
- `make harness-test` and `make harness-test-tablet` green.
- Visual + functional verification on AVD across all three modes. Capture before/after on a representative book (memory rule: verify reader/UI in the running app on AVD before "done").

**Commit:** `refactor(reader): finalize reader-mode-strategy cleanup + guard against new mode forks`

---

## PR plan

- **Title:** `refactor(reader): unify mode-branching behind ReaderPresenter (closes #320)`
- **Body:** Summary table of branches collapsed per slice, plus before/after `grep` counts. Test plan: `make harness-test`, `make harness-test-tablet`, manual AVD verification across the three modes.
- Mention contradiction risk for ADR 0014 (chapter-boundary gesture) and ADR 0006 (immersive tap) — both should be unaffected; verify explicitly.

## Out of scope (per issue #320)

- Three-peer progress sync changes.
- Continuous-mode position pipeline rework.
- ViewModel restructuring beyond what the seam requires.
- Readaloud highlight color / decoration style work.
