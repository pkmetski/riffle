# PDF parity v1 — TOC + annotations (with engine upgrade)

Bring PDF reading to first-class parity with EPUB for navigation and annotations. After this spec ships, a user opening a PDF gets:

- a Table of Contents drawer with the same shape and behavior as EPUB's,
- bookmark / highlight / note annotations with the same selection UX, color picker, action sheets, corner-bookmark indicator, and Annotations panel as EPUB,
- all of the above synced to the same WebDAV target Riffle already uses for EPUB annotations.

The user-facing parity work depends on a foundational engine upgrade: the upstream Readium Pdfium adapter ultimately depends on the abandoned `com.github.barteksc:pdfium-android:1.8.2`, whose Java surface exposes only rendering, metadata, the PDF outline, and link rects — no text extraction. Without text APIs, user-drag highlights are impossible. This spec includes both pieces (engine + features) as one PR series, per user decision (bundling lets us reason about engine assumptions and feature requirements together).

## Scope

**In scope**

1. New `core/pdfium-text` Gradle module — a small parasitic JNI bridge that resolves `FPDFText_*` symbols at runtime from the `libmodpdfium.so` that `com.github.barteksc:pdfium-android` already loads. No vendoring of upstream sources or binaries. No replacement of the engine Readium uses.
2. PDF TOC via `PdfDocument.outline`, exposed through the existing EPUB TOC drawer Composable (generalized to take a book-format-agnostic TOC model).
3. PDF **chapter navigation rail** (the side-rail with chapter ticks + current-position indicator), adapted from the EPUB `ChapterNavigationRail`, fed by the same TOC + page-count data the drawer uses.
4. PDF bookmark, highlight, and note annotations: data model, selection overlay, persistence, rendering, sync.
5. Locator schema extension: Readium-`Locator`-JSON-shaped PDF locators stored in the existing `Annotation.cfi` column.

**Out of scope (deferred to follow-up specs)**

- PDF in-document text search (even though `FPDFText_Find*` bindings come for free with the engine work, the search UI is a separate design surface).
- PDF formatting menu — theme / dark-mode rendering, double-page-in-landscape, on-screen-info toggles. Explicitly deferred.
- Cross-format annotation conflict resolution beyond what the existing W3C codec already does.
- Migration of any prior local PDF annotations — none exist today.
- Multi-page selection (a single highlight cannot cross a page boundary). EPUB has the same per-spine-item limit; this is a parity-consistent constraint, not a regression.

## Architecture

Four layered changes, each isolated from the others:

```
core/pdfium-text  (NEW Gradle module — Android library, Kotlin + small C++ JNI bridge)
  ├─ src/main/cpp/fpdf_text.h         (only header we need — function signatures)
  ├─ src/main/cpp/pdfium_text_jni.cpp (~80 LoC: dlopen RTLD_NOLOAD + dlsym wiring)
  ├─ src/main/cpp/CMakeLists.txt      (builds libriffle_pdfium_text.so per ABI)
  ├─ src/main/kotlin/com/riffle/core/pdfium/text/PdfiumTextApi.kt (external decls)
  └─ NO Pdfium binaries committed — the .so loaded by barteksc is reused at runtime

core/data (existing — small additions)
  ├─ AnnotationStore: queries unchanged (already book-agnostic)
  ├─ AnnotationW3CCodec: Locator JSON path extended for `type: application/pdf`
  └─ no schema migration — `cfi` column absorbs PDF Locator JSON

core/domain (existing — small additions)
  └─ PdfLocator helper for constructing / parsing PDF-shaped Locators
     (façade over Readium's Locator.fromJSON; lives here so app + data can share)

app/feature/reader (existing — bulk of the new code)
  ├─ RifflePdfController — wraps PdfiumNavigatorFragment + selection overlay View
  ├─ PdfSelectionGestureMachine — long-press → drag-handles → action menu
  ├─ PdfHighlightRenderer — paints highlight + bookmark rects on the overlay
  ├─ PdfReaderViewModel — extended to expose TOC, annotations, selection state
  └─ PdfReaderScreen — wires existing EPUB drawers (TOC, Annotations) and
                       existing color picker / HighlightActionsSheet
                       / BookmarkActionsSheet / corner indicator
```

**Boundary invariants:**

- `core/pdfium-text` contains no Riffle business logic. It exists only to expose `FPDFText_*` Pdfium functions to Kotlin. It does not own or replace the Pdfium engine; it borrows symbols from the engine barteksc already loads.
- The Readium adapter (`org.readium.kotlin-toolkit:readium-adapter-pdfium-navigator`) is **not modified or forked**, and its transitive `com.github.barteksc:pdfium-android` dependency is **not excluded or replaced**. Our module sits alongside that dep, calling into the same already-loaded `libmodpdfium.so` at runtime via `dlopen`+`dlsym`.
- All selection / decoration logic lives in `app/feature/reader/`, *outside* both Readium and `core/pdfium-text`. This is the Spike 3 result: `PdfNavigatorFragment` doesn't implement `SelectableNavigator` or `DecorableNavigator`, so we wrap our own equivalents around it.
- The `Annotation` row schema does not change. PDF rows reuse `cfi` (treated as opaque Locator JSON), `chapterHref` (PDF resource URL), `textSnippet/Before/After` (snippet text from `FPDFText_GetBoundedText`). `spineIndex` stays `0`. `progression` carries Locator's page-level progression.

## Engine module — `core/pdfium-text` (parasitic JNI bridge)

A small Android-library module — no vendoring of upstream binaries or sources. The Pdfium native code Riffle uses today (shipped by `com.github.barteksc:pdfium-android:1.8.2` and pulled in transitively by Readium's adapter) already contains every `FPDFText_*` symbol we need (verified by `nm -D` on the prebuilt `libmodpdfium.so`). The only thing missing is a JNI bridge that exposes those symbols to Java. This module adds exactly that bridge — nothing else.

**How it works:** at first call into our Kotlin facade, our native bridge calls `dlopen("libmodpdfium.so", RTLD_NOW | RTLD_NOLOAD)`. `RTLD_NOLOAD` returns a handle to the already-loaded `.so` without reloading it (barteksc's `PdfiumCore` static initializer loaded it earlier). We then `dlsym` each `FPDFText_*` symbol once and cache the function pointers. Subsequent calls go through the cached pointers — same overhead as a normal JNI call into a linked library.

**Source layout**

```
core/pdfium-text/
├─ build.gradle.kts                       # androidLibrary + externalNativeBuild { cmake }
├─ src/main/
│   ├─ cpp/
│   │   ├─ CMakeLists.txt                 # builds libriffle_pdfium_text.so per ABI
│   │   ├─ fpdf_text.h                    # upstream signatures only (~5 KB, from Pdfium)
│   │   ├─ fpdfview.h                     # for FPDF_PAGE typedefs (~10 KB)
│   │   └─ pdfium_text_jni.cpp            # ~120 LoC dlopen+dlsym bridge + JNI wrappers
│   └─ kotlin/com/riffle/core/pdfium/text/
│       └─ PdfiumTextApi.kt               # object with external fun declarations
└─ src/androidTest/                       # instrumentation smoke test
```

**Native bridge** (`pdfium_text_jni.cpp`): a one-time symbol-resolution helper plus one `extern "C" JNIEXPORT` function per Java native. Each JNI wrapper calls the cached function pointer with marshaled arguments and converts the result back to Java/Kotlin types.

```cpp
// Sketch — actual file ~120 LoC including symbol resolution and error handling.
static void* sPdfiumHandle = nullptr;
static FPDF_TEXTPAGE (*FPDFText_LoadPage_p)(FPDF_PAGE) = nullptr;
static int (*FPDFText_CountChars_p)(FPDF_TEXTPAGE) = nullptr;
// ... etc.

static bool resolveSymbols() {
    if (sPdfiumHandle) return true;
    sPdfiumHandle = dlopen("libmodpdfium.so", RTLD_NOW | RTLD_NOLOAD);
    if (!sPdfiumHandle) return false;
    FPDFText_LoadPage_p = (decltype(FPDFText_LoadPage_p))
        dlsym(sPdfiumHandle, "FPDFText_LoadPage");
    // ... etc.
    return FPDFText_LoadPage_p != nullptr; /* etc */
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_riffle_core_pdfium_text_PdfiumTextApi_nativeOpenTextPage(
    JNIEnv*, jobject, jlong pagePtr) {
    if (!resolveSymbols()) return 0L;
    return (jlong) FPDFText_LoadPage_p((FPDF_PAGE) pagePtr);
}
```

**Kotlin facade** (`PdfiumTextApi.kt`):

```kotlin
object PdfiumTextApi {
    init { System.loadLibrary("riffle_pdfium_text") }

    fun openTextPage(pagePtr: Long): Long = nativeOpenTextPage(pagePtr)
    fun closeTextPage(textPagePtr: Long) { nativeCloseTextPage(textPagePtr) }
    fun countChars(textPagePtr: Long): Int = nativeCountChars(textPagePtr)
    fun getCharBox(textPagePtr: Long, charIndex: Int): RectF? = ...
    fun getCharIndexAtPos(textPagePtr: Long, x: Double, y: Double, tolX: Double, tolY: Double): Int = ...
    fun countRects(textPagePtr: Long, startIndex: Int, count: Int): Int = ...
    fun getRect(textPagePtr: Long, rectIndex: Int): RectF? = ...
    fun getText(textPagePtr: Long, startIndex: Int, count: Int): String = ...
    fun getBoundedText(textPagePtr: Long, bounds: RectF): String = ...

    private external fun nativeOpenTextPage(pagePtr: Long): Long
    // ... etc.
}
```

The `pagePtr: Long` is `barteksc.com.shockwave.pdfium.PdfiumCore` page handle, obtained from its existing `openPage(...)` API. We pass it straight through to `FPDFText_LoadPage` (the underlying C API takes an `FPDF_PAGE` which is just a `void*`). The two libraries cooperate by sharing native pointer values — same `libmodpdfium.so`, same opaque handles.

**Gradle wiring** (in `app/build.gradle.kts`):

```kotlin
implementation(project(":core:pdfium-text"))
// barteksc transitive (via readium-adapter-pdfium) stays untouched.
```

No `exclude`. No drop-in replacement. The two modules coexist.

**NDK build config**

- `ndkVersion` pinned in `build.gradle.kts` to the version the CI matrix installs (`29.0.13113456`).
- `cmakeVersion` pinned to `3.22.1`.
- ABIs built: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
- Output artifact: `libriffle_pdfium_text.so` per ABI (~10 KB each), packaged into the AAR. Total APK size impact: <100 KB.

**Risks**

- *Symbol export changes.* Our approach depends on `FPDFText_*` symbols being exported with stable C names in `libmodpdfium.so`. They are (the Pdfium ABI is the same one Chromium ships); barteksc's prebuilt is frozen at Pdfium ~78 (Nov 2017) and the FPDFText API has been stable since Pdfium ~30. Mitigated by the instrumentation smoke test catching `nullptr` symbols at first call.
- *Library not loaded yet.* `dlopen(..., RTLD_NOLOAD)` returns `nullptr` if `libmodpdfium.so` hasn't been loaded by anyone. We mitigate by always touching `com.shockwave.pdfium.PdfiumCore.<clinit>` (loads the .so via `System.loadLibrary`) before the first `PdfiumTextApi` call. Easiest: call `PdfiumCore.<some-trivial-method>` at module init.
- *Cross-loader symbol visibility.* Android `System.loadLibrary` uses `RTLD_LOCAL` by default, but `dlopen(..., RTLD_NOLOAD)` against the same path returns the loader's handle and can resolve its symbols regardless. Verified pattern (used by Chromium's own PartitionAlloc and others on Android).
- *Engine swap regret.* If we later decide to replace the engine (e.g., MuPDF), our module's signatures still hold — only the JNI body changes to call the new engine. No call sites churn.

**Testing**

- Instrumentation smoke test on Harness AVD: open a known fixture PDF via `PdfiumCore`, hand the page pointer to `PdfiumTextApi.openTextPage`, assert non-zero handle and sensible char counts / boxes / extracted text. Verifies both that `dlsym` resolves the symbols and that the cross-library handle sharing works. Lives under `core/pdfium-text/src/androidTest/`.

**Risks**

- *ABI mismatch in CI.* CI runners need the NDK installed and the right CMake. Pin `ndkVersion` and `cmakeVersion`; add an explicit `androidNdk` install step to the existing GH Actions workflow.

## TOC integration

`PdfDocument.outline` is the source of truth — title + destination-page-index per node, nested arbitrarily deep.

**Data flow**

- `PdfReaderViewModel` exposes `toc: List<TocNode>` where `TocNode = (title, pageIndex, children)`.
- The model is built once at book-open by walking Readium's `PdfDocument.OutlineNode` tree (which already maps to a similar shape).
- On TOC item tap, the ViewModel calls `PdfiumNavigatorFragment.goToPageIndex(pageIndex, animated=false)`, the same way it handles server-locator events today.

**UI reuse**

The EPUB reader's TOC drawer is currently built around Readium's EPUB-side TOC types (`Publication.tableOfContents` → `Link`). The existing `TocPanel` Composable is lifted to take a generic `TocNode` model; both EPUB and PDF feed it that shape. (Alternative: keep EPUB's `TocPanel` as-is and write a parallel `PdfTocPanel`. Rejected — parallel UI code drifts.)

**No outline case**: empty state "This PDF has no table of contents." — matches EPUB's no-TOC state.

**Affordance**: a notebook-icon `IconButton` added to `PdfReaderScreen`'s `TopAppBar` opens a `ModalNavigationDrawer` with the TOC, identical to EPUB.

## Annotations — data model & locator format

**Storage stays put.** PDF annotations write to the existing `annotations` Room table via the existing `AnnotationStore`. No new table, no schema migration. The four book-agnostic fields (`itemId`, `serverId`, `type`, `color`, `note`, `createdAt`, `updatedAt`, `textSnippet/Before/After`) carry their existing meaning. The format-flavored fields are reinterpreted:

| Annotation field | EPUB | PDF |
|---|---|---|
| `cfi` | Locator JSON with `epubcfi(...)` string inside `locations.fragments[]` | Locator JSON with `type: "application/pdf"`, page+char in `locations`, charStart/End in `locations.otherLocations` |
| `chapterHref` | spine item href | PDF resource URL |
| `spineIndex` | spine position (≥0) | always `0` |
| `progression` | progression within spine item (0..1) | `(pageIndex + intraPageOffset) / totalPages` — same semantic as EPUB |
| `bookmarkTitle` | user-typed title (bookmarks only) | same |

The column name `cfi` is already a historical artifact on the EPUB side (it holds Locator JSON, not raw CFI). The misnomer is acceptable; renaming the column to `locator` would require a Room migration that buys nothing functional. Documented in a CONTEXT.md note instead.

**PDF Locator JSON shape (highlight)**

```json
{
  "href": "books/example.pdf",
  "type": "application/pdf",
  "locations": {
    "position": 42,
    "progression": 0.31,
    "totalProgression": 0.127,
    "otherLocations": {
      "charStart": 1503,
      "charEnd": 1547,
      "quads": [
        {"x":120,"y":280,"w":340,"h":18},
        {"x":68,"y":300,"w":280,"h":18}
      ]
    }
  },
  "text": {
    "highlight": "the selected passage exactly as it appears",
    "before": "≤32 chars of preceding text for disambiguation",
    "after":  "≤32 chars of following text for disambiguation"
  }
}
```

- `position` = 1-based page number (Readium convention).
- `progression` = vertical position within the page (top-of-selection / page-height). Drives sorting + Annotations-panel display, same as EPUB's intra-spine progression.
- `totalProgression` = book-wide 0..1. Same as EPUB.
- `otherLocations.charStart/charEnd` = character indices into `FPDFText_GetText(textPage)`. The pair survives page re-renders because Pdfium's text-page char ordering is deterministic for a given PDF file. Highlights also work after engine-module updates (the binary defines the ordering, and we only update it in dedicated PRs).
- `otherLocations.quads` = the persisted highlight rectangles in PDF user-space coords, from `FPDFText_GetRect`. Stored so render isn't hot-pathed through JNI on every frame. The char range is canonical; quads are a derived cache. If quads ever go missing (manual JSON edit, format-version skew), we regenerate from the char range.
- `text.{highlight,before,after}` = same disambiguation triple EPUB uses, populated from `FPDFText_GetBoundedText`. Used by the W3C codec for cross-device verification. 32-char window matches EPUB.

**Bookmark Locator JSON** has no `text` block and no `otherLocations`:

```json
{
  "href": "books/example.pdf",
  "type": "application/pdf",
  "locations": {"position": 42, "progression": 0, "totalProgression": 0.127}
}
```

`type = "bookmark"` on the row distinguishes them (same field that distinguishes EPUB bookmarks from highlights today).

**Sort order in Annotations panel** unchanged — `(itemId, totalProgression)`. PDF rows slot in naturally.

**Format detection at read-time:** the existing `Locator.fromJSON(annotation.cfi)` returns a Locator with `type` set. EPUB code paths key on `type == application/xhtml+xml` (today implicit); we make the discriminator explicit and add the PDF branch in `AnnotationW3CCodec` and any other locator decoder used by UI.

## Annotations — selection UX

The selection layer is the most code-dense piece of the spec. State machine, overlay rendering, and gesture handling all live in `app/feature/reader/`.

**Components**

- **`PdfSelectionGestureMachine`** — pure-Kotlin state machine, JVM-testable. States: `Idle → Selecting(anchor, head) → Committed(range) → Idle`. Transitions on `onLongPress`, `onHandleDragStart`, `onHandleDragMove`, `onHandleDragEnd`, `onOutsideTap`, `onSelectionCleared`. Emits selection-range events. Knows nothing about Pdfium or Compose.
- **`PdfTextResolver`** — wraps `core/pdfium`'s low-level char APIs into the operations the gesture machine needs: `wordAtPoint(page, x, y) → IntRange?`, `extendSelection(page, range, toPoint) → IntRange`, `quadsForRange(page, range) → List<RectF>`, `extractSnippet(page, range, contextChars=32) → SnippetTriple`. JVM-testable against a fake Pdfium backed by fixture char data.
- **`PdfSelectionOverlay`** — Compose `Canvas` overlaid on `PdfiumNavigatorFragment`. Renders selection quads (semi-transparent fill), drag handles at range endpoints, hit-test regions for handle drag, hit-test regions for tapping committed highlights. Subscribes to page-scroll/zoom events from `PDFView` to keep quads aligned (the one PDFView-internal-coupling point — we use its public `OnDrawListener` + zoom callbacks).
- **`PdfSelectionActionMenu`** — Compose popup anchored to the selection. Single primary action: **Highlight** → reuses EPUB's `ColorPickerSheet`. Secondary: **Copy** (clipboard), **Add note** (inline editor with the color picker, same as EPUB).

**Gesture binding to Readium's `InputListener`**

```kotlin
// Riffle-side, registered on PdfiumNavigatorFragment
override fun onTap(event: TapEvent): Boolean {
    if (selectionState.value is Committed) {
        return controller.handleTap(event.point)
    }
    return false  // DirectionalNavigationAdapter handles edge-tap nav
}
override fun onDrag(event: DragEvent): Boolean {
    return controller.handleDrag(event)  // long-press → drag-handle path
}
```

Drag events from Readium's `InputListener` give us the press lifecycle. Long-press detection is done by us (timestamp the press, threshold-fire if drag distance stays below the touch slop) since `InputListener` doesn't model long-press directly. `DragEvent.Type` (`START`, `MOVE`, `END`) is the surface; we time it.

**Multi-line selection** — `FPDFText_CountRects` + `GetRect` already returns one quad per visual line of the selection, so multi-line highlights just paint multiple quads. No custom line-segmentation code needed.

**Multi-page selection — explicitly out of scope.** Cross-page char-index arithmetic gets messy (one page visible in paginated mode, two in scroll); EPUB doesn't support cross-spine-item selection either, so this is parity-consistent. Documented limit.

**Color picker / action sheets — direct reuse**

- `ColorPickerSheet` (EPUB) takes a color + onColorChange + onConfirm — no EPUB-specific state. Drops in as-is.
- `HighlightActionsSheet` opens on tap of an existing highlight. Takes an `Annotation` and emits actions (change color, edit note, delete). Already book-agnostic.
- `BookmarkActionsSheet` likewise.

**Persistence**

On Highlight commit:

1. `PdfTextResolver.extractSnippet(page, range)` → `(highlight, before, after)`.
2. `PdfTextResolver.quadsForRange(page, range)` → quads.
3. Build PDF Locator JSON per the data-model section.
4. Insert row via `AnnotationStore.insert(Annotation(type="highlight", ..., cfi=locatorJson))`.
5. Existing W3C sync codec picks up the new row on the next sync tick — no codec changes needed for the row itself; only the codec's locator-decoding branch needs the `type: application/pdf` case.

**Rendering committed highlights**

On page-load, ViewModel queries `AnnotationStore` for highlights on the current page (cheap — `WHERE itemId = ? AND locations.position = pageIndex`, exposed as a flow). The overlay paints each highlight's persisted quads, no per-frame Pdfium calls. Hit-testing for tap-to-edit uses the same quads.

## Annotations — bookmark UX

Bookmarks are a thin slice on top of the same overlay machinery.

**Components**

- **`PdfCornerBookmarkIndicator`** — reuse of EPUB's `CornerBookmarkIndicator`. Already book-agnostic. Painted into the same overlay View as the selection layer, positioned in the top-trailing corner of the visible page.
- **Bookmark hit region** — a small invisible region in the same corner, hit-tested before `DirectionalNavigationAdapter`'s edge-tap handling. Tap-to-toggle the bookmark for the current page.
- **`BookmarkActionsSheet`** — reuse of EPUB's. Opens on tap of an existing bookmark's row in the Annotations panel; offers Rename + Delete. (Tapping the corner indicator on a bookmarked page un-bookmarks it directly — matches EPUB.)

**ViewModel state**

- `currentPageBookmarked: StateFlow<Boolean>` — true iff an `Annotation(type="bookmark")` row exists for `(itemId, locations.position == currentPage)`. Drives the indicator's visibility.
- `addBookmark(title: String? = null)` — inserts a row with the bookmark Locator. `title` defaults to "Page N" if not supplied (matches EPUB's default-title pattern).
- `removeBookmark(pageIndex: Int)` — deletes the matching row.

**Where bookmarks live in UI**

- The current page shows the corner indicator if it has a bookmark.
- The Annotations panel (existing) lists bookmarks alongside highlights, sorted by `totalProgression`. PDF bookmark rows render exactly like EPUB bookmark rows — title + page-number position hint + tap to navigate.

**Multi-bookmark-per-page** — not modeled. EPUB doesn't model it either; toggle is the affordance.

**Persistence and sync** — same `AnnotationStore` insert/delete + W3C sync codec as highlights. No extra work.

## Sync codec — W3C / WebDAV extension

**Today.** `AnnotationW3CCodec` encodes each `Annotation` row into a W3C Web Annotation JSON-LD object, packs them into per-book annotation files, and pushes/pulls through `AnnotationSyncTarget` (WebDAV mount under the user's configured server). The codec is mostly format-agnostic — `type`, `color`, `note`, `textSnippet`, `bookmarkTitle`, `createdAt`, `updatedAt` all serialize the same regardless of book type. The two book-format-specific points are selector encoding and locator decoding.

**Selector for PDF**

Use **`FragmentSelector` + `RefinedBy(DataPositionSelector)`** — the W3C-blessed shape for "page + character range":

- `FragmentSelector` with a PDF Open Parameters fragment (`#page=N`) — W3C standard, supported by any third-party WebDAV-reading client.
- `RefinedBy(DataPositionSelector)` with `start/end` for the char range.
- A custom property on the `RefinedBy` carries quads — annotation tools that don't know it just ignore it, which is fine.

Alternative considered: a single proprietary `RiffleLocatorSelector` stuffing the whole Locator JSON. Rejected because it's opaque to other tools that might read the same WebDAV folder.

**Round-trip path**

```
Write:  Annotation row (PDF locator JSON in `cfi`)
     → AnnotationW3CCodec.encode()
     → branches on locator.type
     → emits FragmentSelector + RefinedBy DataPositionSelector + Riffle quads property
     → JSON-LD doc pushed to WebDAV

Read:   pull JSON-LD doc from WebDAV
     → AnnotationW3CCodec.decode()
     → detects selector shape; reconstructs PDF locator JSON
     → upserts Annotation row via existing AnnotationStore.merge() rules
     → existing W3C codec timestamp resolution decides winner on conflict
```

**Wire-format change is additive.** Existing EPUB annotations on user WebDAV mounts are unchanged. New PDF annotation files use new selector types but live in the same folder structure (`<server>/<itemId>/annotations.jsonld`). No migration needed; old Riffle versions on the same WebDAV mount just won't decode PDF rows (they have no PDF books to attach them to anyway). Documented in CONTEXT.md.

**Sync of bookmarks** uses the same path with `Motivation: bookmarking` and no `RefinedBy` (bookmarks have no char range).

**Cross-device verification.** The codec already uses the `textSnippet/Before/After` triple to verify that a re-applied annotation lands on the same text on a different device (in case the source file was re-derived or the engine version differs). PDF rows participate naturally — the snippet triple is populated from `FPDFText_GetBoundedText` at write time and re-extractable at read time.

**Conflict resolution** unchanged: `(updatedAt, deviceId)` tiebreak as today.

## Testing strategy

- **JVM unit tests** for: `PdfSelectionGestureMachine` (full state-machine coverage), `PdfTextResolver` (against fake Pdfium with fixture char data), `AnnotationW3CCodec` PDF branch (round-trip JSON-LD), PDF Locator JSON construction/parsing.
- **Instrumentation smoke test** for `core/pdfium`: a fixture PDF + each new native method, asserting non-null results.
- **Harness tests** for: TOC drawer open + item-tap navigation, corner-bookmark toggle, highlight create / tap / delete, multi-line highlight rendering.

JVM tests alone don't cover the WebView / Pdfium / native layers — per AGENTS.md, instrumentation coverage is required for anything that touches Pdfium or the reader UI.

## Chapter navigation rail (PDF)

Riffle's EPUB reader has a side rail that shows chapter tick-marks along the book's reading axis, with the current position indicated. The visual lives in `ChapterNavigationRail.kt` and is fed by `RailSegmentGenerator.kt` — given a sequence of `(progressionStart, progressionEnd, label)` segments plus a `currentProgression`, it produces ticks + a position marker.

For PDFs, segments come from the same TOC tree the drawer renders (decision: TOC is the source of truth for chapter boundaries). The generator becomes book-format-agnostic with one additional construction call:

```kotlin
RailSegmentGenerator.fromPdfToc(
    toc: List<TocNode>,            // flattened to top-level nodes only — nested chapters fold up
    totalPages: Int,
): List<RailSegment>
```

It walks the top-level TOC nodes in order; for each node `i`, the segment is `(pageIndex_i / totalPages, pageIndex_{i+1} / totalPages, title_i)`. The trailing chapter terminates at `1.0`. Nested sub-chapters are not rendered as ticks on the rail (keeps the rail visually consistent with EPUB, where the rail also uses top-level spine items, not their inner anchors).

**PDF without TOC**: the rail renders as a single segment spanning `0.0–1.0` with no label — same fallback EPUB uses when its publication has no TOC. The position indicator still shows progress, which is the rail's primary utility.

**Visibility**: the rail is gated by the existing `showChapterMap` formatting preference. PDF doesn't have its own formatting menu yet (deferred to follow-up spec), so we read the same global preference EPUB uses. Default `true`, matches EPUB.

**Tap-to-jump on the rail**: existing EPUB behavior — tap a segment to jump to that chapter. For PDF, "jump to chapter" means `goToPageIndex(toc[i].pageIndex)`. Wires through the same `PdfReaderViewModel` method TOC-drawer taps already use.

## Open items / explicit non-decisions

- **NDK / CMake version pinning** — `ndkVersion = "29.0.13113456"`, `cmakeVersion = "3.22.1"` to match `core/pdfium-text/build.gradle.kts`. CI workflow update (in scope of this spec) installs them.
- **Fixture PDF for tests** — TBD at implementation time; a small (<200 KB) public-domain PDF with a known outline and known text content.
