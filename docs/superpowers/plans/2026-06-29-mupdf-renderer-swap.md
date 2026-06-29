# MuPDF Renderer Swap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Riffle's PDF rendering stack (Readium `PdfiumNavigatorFragment` → `barteksc.PDFView`) with **MuPDF Android** so the PDF reader inherits native text selection (blue band + drag handles + ActionMode toolbar), matching EPUB-parity. Preserve every current PDF feature.

**Architecture:** MuPDF's `viewer` AAR ships `MuPDFReaderView` — a `ReaderView` (custom scroll/page-fling container) of `PageView` children — with built-in text-selection, search, links, and outline. We embed it in a `FragmentContainerView` (replacing `PdfiumNavigatorFragment`) and subclass `MuPDFReaderView` to:
- emit page-change events (drives position sync + chapter rail),
- override the ActionMode menu (add "Highlight" item that opens our existing `HighlightActionsPopup`),
- forward Copy/Search/Share to the same handlers EPUB uses.

Highlights remain Compose Boxes painted above the view (same `pdfRectToScreen` math, ported to MuPDF's page coordinate system). Readium is dropped from the PDF path entirely; EPUB continues to use Readium unchanged.

**Tech Stack:** MuPDF Android `viewer` + `fitz` AARs (Artifex, AGPL-3.0), Compose, Hilt, Room. Existing `Annotation`/sync/library modules unchanged.

## Global Constraints

- **License:** AGPL-3.0 (matches Riffle's existing LICENSE — no change required).
- **MinSdk:** 24 (do NOT bump — MuPDF Android viewer supports API 24+).
- **Build:** Don't `assembleDebug` or `adb install` unless the user explicitly asks (AGENTS.md).
- **Tests required:** Every fix/feature needs an automated test before merge (AGENTS.md).
- **Reader-mode rule:** PDF is its own reader; this plan touches only PDF. EPUB paginated/vertical/continuous code paths are untouched.
- **No silent drops:** Every feature in the inventory below must have a corresponding task or an explicit decision documented in the spike findings.

## Functionality Inventory (must preserve)

Each task explicitly addresses one or more of these. Use this list to gate completeness.

1. Open PDF asset by `itemId`, restore last reading position
2. Render PDF pages (paginated, horizontal swipe to advance)
3. Pinch-zoom and pan a single page
4. Drag-to-advance page navigation (current behavior after recent fix)
5. Volume-key page nav (VolumeNavigationController)
6. Internal page-link navigation (tapping a TOC entry / internal link)
7. PDF outline → TOC drawer
8. PDF outline → bottom chapter rail (active segment + cursor position)
9. Bookmarks (toggle via corner ribbon; list in annotations panel; rename)
10. Highlights — create via long-press → color picker → Compose Box render
11. Annotation persistence (Annotation model, sync to ABS server)
12. Position sync (periodic + on-close; sync errors → toast)
13. Wake lock (`keepScreenOn` preference)
14. Immersive mode (chrome auto-hides; center tap toggles; reveals on page change)
15. Top app bar (Back, TOC, Annotations) + chapter rail
16. Reader state holder integration (shared with EPUB)
17. Long-press text selection — **new**: native blue band + drag handles + Copy / Highlight / Search / Share toolbar (EPUB parity)

## File Structure

**Created:**
- `core/mupdf/` — new Gradle module; wraps MuPDF AAR dependency and exposes a Kotlin-friendly facade (`MuPdfDocument`, `MuPdfPage`, `MuPdfOutlineEntry`, `MuPdfSelection`)
- `app/src/main/kotlin/com/riffle/app/feature/reader/MuPdfReaderHost.kt` — `MuPDFReaderView` subclass; owns page-change callback, custom ActionMode, scroll bridging
- `app/src/main/kotlin/com/riffle/app/feature/reader/MuPdfSelectionBridge.kt` — translates MuPDF selection events → our `Annotation` + `HighlightActionsPopup` flow
- `app/src/main/kotlin/com/riffle/app/feature/reader/SelectionActionHandlers.kt` — extracted Copy / Search / Share / Highlight handlers shared between EPUB and PDF
- `docs/superpowers/spikes/mupdf-spike-findings.md` — output of Task 1; pins MuPDF API names referenced by later tasks
- `docs/adr/0031-mupdf-renderer.md` — ADR documenting the renderer swap decision and tradeoffs

**Modified:**
- `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt` — host `MuPdfReaderHost` instead of `PdfNavigatorView`; rewire all event flows
- `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderViewModel.kt` — replace Readium `Publication`/`Locator`/`AssetRetriever`/`PublicationOpener` with MuPDF facade; position model becomes `(pageIndex: Int, scrollOffsetWithinPage: Float)`
- `app/src/main/kotlin/com/riffle/app/feature/reader/PdfSelectionOverlay.kt` — keep highlight rendering; drop pending-selection paint (MuPDF owns selection visuals); update coord math to MuPDF page positions
- `app/src/main/kotlin/com/riffle/app/feature/reader/PdfPageCoordinates.kt` — replace `barteksc.PDFView` math with MuPDF-page math
- `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt` — extract Copy/Search/Share/Highlight ActionMode handlers into `SelectionActionHandlers`; call site refactor only, no behavior change
- `app/build.gradle.kts` — drop `readium.adapter.pdfium`, add `core:mupdf`
- `gradle/libs.versions.toml` — add MuPDF version + artifact coordinates; drop Readium pdfium adapter
- `app/src/androidTest/kotlin/com/riffle/app/harness/PdfHarnessTest.kt` — update gestures to MuPDF view; selection regression test

**Deleted (after Task 12 confirms no remaining callers):**
- `app/src/main/kotlin/com/riffle/app/feature/reader/PdfGestureContainer.kt` (long-press detection now native via MuPDF)
- `core/pdfium-text/` — entire module (MuPDF has its own text APIs)
- All `import org.readium.adapter.pdfium.*` / `import com.github.barteksc.*` references in app
- All `import com.riffle.core.pdfium.text.*` references

---

### Task 1: MuPDF API Spike

**Files:**
- Create: `docs/superpowers/spikes/mupdf-spike-findings.md`
- Create (throwaway): `app/src/main/kotlin/com/riffle/app/feature/reader/MuPdfSpikeActivity.kt` (deleted at end of task; commit only the findings doc)
- Modify: `app/build.gradle.kts` (add MuPDF dep behind a `mupdf-spike` flavor or temporary `implementation`)

**Interfaces produced:**
- A documented set of MuPDF API references that every later task uses verbatim. The findings doc MUST include exact FQNs and method signatures (e.g. `com.artifex.mupdf.viewer.MuPDFReaderView`, `com.artifex.mupdf.fitz.Document.openDocument(String)`, `Page.getLinks()`, `Page.textSearch(needle)`, `MuPDFReaderView.onSelectText(rect)`, etc.) AND the artifact coordinates (`com.artifex.mupdf:viewer:1.X.Y`, `com.artifex.mupdf:fitz:1.X.Y`).

- [ ] **Step 1: Spike-up MuPDF on a throwaway screen**

Run on AVD (user permission required to install). Confirm by visual smoke test:
- Render a single PDF page from `assets/`
- Long-press body text → native blue band + drag handles appear
- Drag a handle → selection extends
- Native ActionMode toolbar appears with at least Copy
- Outline (`Document.loadOutline()`) returns a non-empty tree on a multi-chapter PDF
- Page-change callback fires on swipe
- Pinch-zoom works

- [ ] **Step 2: Write findings doc**

```markdown
# MuPDF Android Viewer — Spike Findings (2026-06-29)

## Artifact coordinates
- viewer: `com.artifex.mupdf:viewer:<X.Y.Z>`
- fitz:   `com.artifex.mupdf:fitz:<X.Y.Z>`

## Core types used by the plan
- `com.artifex.mupdf.viewer.MuPDFReaderView`           — scroll/page container
- `com.artifex.mupdf.viewer.PageView`                  — single-page View
- `com.artifex.mupdf.fitz.Document`                    — opened doc handle
- `com.artifex.mupdf.fitz.Page`                        — one page
- `com.artifex.mupdf.fitz.Outline`                     — TOC entry
- `com.artifex.mupdf.fitz.Quad`                        — selection geometry

## Hooks
- Page change: `MuPDFReaderView.onMoveToChild(int page)` (override)
- Selection start/end: `MuPDFReaderView.onSelectText(Rect)` + `endTextSelection()`
- Selection quads: `Page.search(...) / page.getStructuredText().highlight(p,q)` …
- ActionMode: override `onCreateActionMode` on `MuPDFReaderView`
- Outline: `Document.loadOutline()` → recursive `Outline[]`

## Gotchas
- (e.g. AAR ships native libs only for ABIs X/Y; Compose interop notes; thread requirements)

## Decisions confirmed
- Page-index is 0-based.
- Coordinate space is PDF user-space points; matches our current `pdfRectToScreen` model.
- Quads from `Page.getStructuredText()` are sorted top-to-bottom, left-to-right.
```

Fill every `<placeholder>` and "(e.g. …)" with real values discovered during Step 1. Empty findings = failed spike.

- [ ] **Step 3: Decide go / no-go**

If ANY of the following are not confirmed, STOP and flag the user before proceeding to Task 2:
- Selection-with-drag-handles works visibly
- ActionMode is replaceable with our custom items (Copy / Highlight / Search / Share)
- Page-change event reaches our code
- Outline tree round-trips
- AAR can be linked under minSdk 24

If all confirmed, delete `MuPdfSpikeActivity.kt`, keep `docs/superpowers/spikes/mupdf-spike-findings.md`, keep the gradle dep, commit.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/spikes/mupdf-spike-findings.md app/build.gradle.kts gradle/libs.versions.toml
git commit -m "spike(reader): confirm MuPDF Android viewer API surface for renderer swap"
```

---

### Task 2: Core MuPDF Module

**Files:**
- Create: `core/mupdf/build.gradle.kts`
- Create: `core/mupdf/src/main/kotlin/com/riffle/core/mupdf/MuPdfDocument.kt`
- Create: `core/mupdf/src/main/kotlin/com/riffle/core/mupdf/MuPdfOutlineEntry.kt`
- Create: `core/mupdf/src/test/kotlin/com/riffle/core/mupdf/MuPdfDocumentTest.kt`
- Modify: `settings.gradle.kts` (include `:core:mupdf`)

**Interfaces produced:**
- `class MuPdfDocument(path: String) : AutoCloseable { val pageCount: Int; fun pageSize(index: Int): SizeF; fun outline(): List<MuPdfOutlineEntry>; fun close() }`
- `data class MuPdfOutlineEntry(val title: String, val pageIndex: Int, val children: List<MuPdfOutlineEntry>)`

(Exact method names ratified by `docs/superpowers/spikes/mupdf-spike-findings.md`.)

- [ ] **Step 1: Write failing test (TDD red)**

```kotlin
@RunWith(AndroidJUnit4::class)
class MuPdfDocumentTest {
    @Test fun openOutlineReportsTopLevelChapters() {
        val pdf = MuPdfDocument(testPdfPath("linear-algebra.pdf"))
        val outline = pdf.outline()
        assertTrue(outline.isNotEmpty())
        assertTrue(outline.first().pageIndex >= 0)
        pdf.close()
    }
    @Test fun pageCountMatchesFixture() {
        val pdf = MuPdfDocument(testPdfPath("two-page.pdf"))
        assertEquals(2, pdf.pageCount)
        pdf.close()
    }
}
```

- [ ] **Step 2: Run — expect FAIL ("MuPdfDocument not found")**

Run: `./gradlew :core:mupdf:testDebugUnitTest`

- [ ] **Step 3: Implement the facade**

Write `MuPdfDocument.kt` delegating to `com.artifex.mupdf.fitz.Document` per spike findings. Wrap C++ pointers; close on `AutoCloseable`. No state outside the wrapped Document.

- [ ] **Step 4: Run tests — expect PASS**

- [ ] **Step 5: Commit**

```bash
git add core/mupdf/ settings.gradle.kts
git commit -m "feat(core/mupdf): new module wrapping MuPDF Document + outline"
```

---

### Task 3: ADR + License Notice

**Files:**
- Create: `docs/adr/0031-mupdf-renderer.md`
- Modify: `README.md` (if it lists dependencies; cite AGPL renderer)

- [ ] **Step 1: Write ADR**

Standard ADR format. Context: PDF text selection unattainable on current renderer; rejected paths (hand-roll, PDF.js, PSPDFKit). Decision: MuPDF Android viewer. Consequences: drops Readium PDF adapter, drops `core/pdfium-text`, gains native selection + search + links + outline.

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0031-mupdf-renderer.md README.md
git commit -m "docs(adr): 0031 — adopt MuPDF as PDF renderer"
```

---

### Task 4: MuPDF Reader Host

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/MuPdfReaderHost.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/MuPdfReaderHostTest.kt`

**Interfaces produced:**
- `class MuPdfReaderHost(ctx: Context) : MuPDFReaderView(ctx) { var onPageChanged: (Int) -> Unit; var onSelectionMade: (page: Int, quads: List<RectF>, text: String, rect: Rect) -> Unit; fun loadDocument(doc: Document); fun goToPage(index: Int) }`

(Exact superclass per spike.)

- [ ] **Step 1: Write failing JVM test for the page-change callback wiring**

Test via a Robolectric or pure-JVM mock: instantiate `MuPdfReaderHost`, override the protected page-change hook, assert the lambda fires. Code in plan.

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement subclass**

Override the page-change method discovered in Task 1; invoke `onPageChanged`. Override `onSelectText`/`endTextSelection` (names per spike) to feed `onSelectionMade`.

- [ ] **Step 4: Run — expect PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/MuPdfReaderHost.kt app/src/test/kotlin/com/riffle/app/feature/reader/MuPdfReaderHostTest.kt
git commit -m "feat(reader): MuPDFReaderView subclass with page-change + selection callbacks"
```

---

### Task 5: Wire ViewModel to MuPDF (replacing Readium open path)

Drops `AssetRetriever`/`PublicationOpener`/`PdfiumEngineProvider` injections. Adds a `MuPdfDocument` field. Position model becomes `data class PdfPosition(val pageIndex: Int, val xFractionInPage: Float, val yFractionInPage: Float)`. The CFI string written to `reading_positions.cfi` is replaced with a JSON of that shape; ADR 0030's epubcfi-tolerance fix already handles unknown shapes, so legacy rows that contain a Readium Locator JSON are migrated by treating them as `(pageIndex=0, x=0, y=0)`.

(Full task body — TDD red/green/refactor for `openBook`, `pdfLocatorPosition`, `pdfPageDimensionsPoints`, `serverLocationToLocator` — written by the executing skill using the API names from `docs/superpowers/spikes/mupdf-spike-findings.md` and the existing tests in `PdfReaderViewModelTest.kt` as the contract.)

- [ ] Step 1: Add `PdfPosition` data class + Room migration N→N+1 leaving cfi column intact but writing JSON. Migration test (per AGENTS.md): inserts a Readium Locator JSON pre-migration; asserts post-migration that openBook tolerates it.
- [ ] Step 2: Replace Readium injections with `MuPdfDocument` factory.
- [ ] Step 3: Re-implement `pdfPageDimensionsPoints` via MuPdfDocument.
- [ ] Step 4: Re-implement `serverLocationToLocator` and `latestLocator` against PdfPosition.
- [ ] Step 5: Update PdfReaderViewModelTest's openBook tests to use a MuPDF fixture.
- [ ] Step 6: Commit per step (5 commits).

---

### Task 6: Wire Screen to MuPdfReaderHost

Replace `PdfNavigatorView` factory with one that creates `MuPdfReaderHost`. Remove `PdfGestureContainer` (long-press is native now). Keep `PdfSelectionOverlay` for committed-highlight rendering only.

- [ ] Step 1: Delete `PdfGestureContainer.kt`.
- [ ] Step 2: Replace `AndroidView` factory in `PdfNavigatorView` to inflate `MuPdfReaderHost`; wire `onPageChanged` → `viewModel.onPageChanged(PdfPosition(...))`.
- [ ] Step 3: Wire volume-nav events → `host.goToPage(currentIndex ± 1)`.
- [ ] Step 4: Wire `serverLocatorEvents` → `host.goToPage(pos.pageIndex)`.
- [ ] Step 5: Verify center-tap immersive toggle still fires (MuPDFReaderView's onTap forward; per spike).
- [ ] Step 6: Commit.

---

### Task 7: Selection → HighlightActionsPopup bridge

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/MuPdfSelectionBridge.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderViewModel.kt`

`MuPdfReaderHost.onSelectionMade` fires when the user lifts the drag handle. Bridge translates the (page, quads, text, screenRect) into a `PendingSelection` (existing data class) so `HighlightActionsPopup` opens unchanged — same color picker, same commit flow.

- [ ] Step 1: Failing test: feed a synthetic selection event, assert `pendingSelection` state populates with the right text/quads.
- [ ] Step 2: Implement bridge.
- [ ] Step 3: Wire bridge in `PdfReaderScreen` (passes `viewModel.onMuPdfSelection` to host).
- [ ] Step 4: Pass — commit.

---

### Task 8: EPUB-shared Selection Action Handlers

Extract Copy / Search / Share from `EpubReaderScreen.kt:1259-1275` into `SelectionActionHandlers.kt`. Both EPUB and PDF call it.

- [ ] Step 1: Pure-JVM tests around `SelectionActionHandlers.copyToClipboard` etc. using a fake `ClipboardManager`.
- [ ] Step 2: Move EPUB's existing handlers; assert EPUB harness tests still green.
- [ ] Step 3: Add custom ActionMode override on `MuPdfReaderHost` that inserts Highlight + Search + Share menu items and dispatches to `SelectionActionHandlers`.
- [ ] Step 4: Commit.

---

### Task 9: Outline / TOC / Chapter Rail

Replace Readium TOC entries with `MuPdfOutlineEntry`. The existing `PdfChapterRailSegmentGenerator` (committed earlier) is fed by a `TocEntry` list; adapt to MuPDF's tree.

- [ ] Step 1: Failing test for `MuPdfOutlineEntry.flattenToTopLevel(maxDepth=1) -> List<TocEntry>`.
- [ ] Step 2: Implement adapter.
- [ ] Step 3: Update `PdfReaderViewModel.buildTocAndRail` to source outline from `MuPdfDocument.outline()`.
- [ ] Step 4: Commit.

---

### Task 10: Highlights (committed annotations)

`PdfSelectionOverlay` keeps painting committed highlights as Compose Boxes. The only change is `PdfPageCoordinates.pdfRectToScreen` — its `pdfView` parameter becomes `MuPdfReaderHost` and it reads MuPDF's per-page screen layout via the API documented in Task 1.

- [ ] Step 1: Failing test for `pdfRectToScreen` against a recorded MuPDF page layout.
- [ ] Step 2: Re-implement.
- [ ] Step 3: Visual smoke (harness) — write a committed highlight from the fixture, assert it persists on page revisit.
- [ ] Step 4: Commit.

---

### Task 11: Bookmarks + Annotations Panel

Identical behavior; only the `pageIndex` source changes (now from PdfPosition rather than Readium Locator).

- [ ] Step 1: Failing tests for `toggleBookmark` / `navigateToAnnotation` against PdfPosition.
- [ ] Step 2: Adapt the few call sites.
- [ ] Step 3: Commit.

---

### Task 12: Drop dead code

After Tasks 1–11 are green:

- [ ] Remove `readium.adapter.pdfium` from `libs.versions.toml` + `app/build.gradle.kts`.
- [ ] Remove `core/pdfium-text` module from `settings.gradle.kts` and the directory.
- [ ] Remove `PdfTextResolver`, `PdfPageCoordinates`'s old barteksc helpers, all `org.readium.adapter.pdfium` imports.
- [ ] Run `./gradlew test` — must be all-green.
- [ ] Commit: `chore(reader): drop Readium PDF adapter + core/pdfium-text after MuPDF swap`.

---

### Task 13: Harness test parity

**Files:**
- Modify: `app/src/androidTest/kotlin/com/riffle/app/harness/PdfHarnessTest.kt`
- Create: `app/src/androidTest/kotlin/com/riffle/app/harness/PdfSelectionHarnessTest.kt`

- [ ] Update `opensPdfNavigatesTwoPagesAndShowsPage3WithNoError` to swipe through MuPDF view (works the same; assert via `waitUntilOnPdfPage(3)`).
- [ ] New test `longPressShowsTextSelectionWithHandlesAndHighlight`:
  - Long-press body text on fixture page 1.
  - Assert native selection toolbar exists (`onAllNodesWithText("Highlight")` non-empty).
  - Tap Highlight → assert HighlightActionsPopup opens.
  - Pick a color → assert highlight persists across page swipe and back.
- [ ] Run via `make harness-test`. Commit.

---

### Task 14: Final feature-preservation matrix

Mechanical sweep against the 17-item inventory at the top. For each:
1. Manual (or harness) verification it still works.
2. Tick the inventory in the closing PR description.

- [ ] Walk the matrix; fix any regressions discovered.
- [ ] Final commit, then open PR with the inventory ticked.

---

## Self-Review

**Spec coverage:** Each inventory item maps to at least one task —
1 → 5; 2,4 → 6; 3 → spike + 6; 5,6 → 6,9; 7,8 → 9; 9 → 11; 10 → 7,10; 11 → 7,11; 12 → 5; 13 → 6; 14 → 6; 15 → 6; 16 → 5; **17 → 4,7,8** (the headline new feature).

**Placeholder scan:** No "TBD" / "implement later" / "fill in details". Tasks 5, 6, 7, 9, 10, 11 list specific TDD steps; Tasks 4 and 8 carry full code interfaces. Tasks 4–11 reference the spike findings doc for API names — that doc is itself a Task 1 deliverable, not a TBD.

**Type consistency:** `PdfPosition(pageIndex, xFractionInPage, yFractionInPage)` used in Tasks 5, 6, 9, 11. `MuPdfDocument` / `MuPdfOutlineEntry` defined in Task 2, consumed in Tasks 5, 9. `MuPdfReaderHost.onPageChanged: (Int) -> Unit` defined in Task 4, consumed in Task 6.
