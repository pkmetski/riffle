# Figure annotations — design

**Date:** 2026-07-07
**Author:** pkmetski
**Status:** Draft

## Problem

Users can highlight text and add bookmarks, but the reader has no way to annotate a figure — a graph, diagram, illustration, or equation — even though those are exactly the moments a reader wants to save. When the annotated book is opened in "Highlights mode" (ADR 0041's elided reader) the annotated figures are absent, and text highlights that clearly reference a nearby figure ("as shown below…") lose the figure that made them meaningful.

## Goals

1. Any element that is currently *zoomable by tap* is also *annotatable by long-press*. The two mechanisms share their element detector so the sets never drift.
2. Text highlights whose selection range crosses a figure include that figure in the annotation. One combined annotation, not two.
3. Figures annotated by either mechanism appear in the Highlights-mode reader at full size, with their caption, inline with the surrounding text highlights in reading order.
4. Annotated figures are visually marked in the normal reader so the reader can find and edit them.
5. Sync round-trips figure annotations to and from Audiobookshelf without regressing existing highlight sync.

## Non-goals

- Cross-chapter selections stay unsupported (matches the current text-selection scope; see ADR 0032).
- No region cropping — the annotation covers the whole figure, not a sub-rect.
- No annotation on figures Readium/WebView doesn't already detect as figures (the zoomable set is the annotatable set — nothing more).
- MathML `<math>` is out of scope for v1. In practice EPUBs render equations either as `<img>` (in scope), as inline SVG (in scope), or as MathML (out of scope).

## Design

### Annotatable = zoomable

`FigureTapScript.kt` already walks up from the tap target to detect `<img>`, `<svg>`, `<picture>`, `<figure>`, skipping images inside anchor links. That detector is extracted into a single function used by both the tap handler (opens zoom overlay) and a new long-press handler (creates an annotation). Any future extension of the zoomable set is automatically an extension of the annotatable set.

### Caption resolution

Best-effort chain, resolved in JS at the moment of long-press:

1. Nearest `<figcaption>` — sibling within the enclosing `<figure>`, or descendant.
2. `alt` attribute on the `<img>`.
3. `aria-label` on the figure/img element.
4. Empty string.

Empty caption never blocks the annotation. The user's original ask is "graphs and diagrams *with their descriptions*", but forcing a `<figcaption>` would silently exclude a large fraction of real-world EPUBs that place captions in a sibling `<p>` or encode them in `alt`.

### Data model

New annotation type and one new column.

```kotlin
// AnnotationEntity.kt
object {
  const val TYPE_HIGHLIGHT = "HIGHLIGHT"
  const val TYPE_BOOKMARK = "BOOKMARK"
  const val TYPE_IMAGE = "IMAGE"  // new
}
```

For `TYPE_IMAGE` annotations:

- `cfi` — CFI *point* to the figure element (not a range).
- `textSnippet` — the resolved caption (may be empty).
- `chapterHref`, `spineIndex`, `progression` — as today.
- `imageHref` — the in-EPUB href of the raster image, or `null` for inline `<svg>`.
- `imageSvg` — serialized inline SVG source, or `null` for raster.

For `TYPE_HIGHLIGHT` annotations that enclose one or more figures:

- `embeddedFigures` — nullable JSON string, `null` when no figures are inside the range. When present, an ordered array of `{href?: String, svg?: String, caption: String, order: Int}` recording each enclosed figure and its position within the highlight's text so the Highlights-mode view can interleave them correctly.

Three new nullable columns (`embeddedFigures`, `imageHref`, `imageSvg`) means one Room migration N→N+1 with the CLAUDE.md-mandated `MigrationTest` covering column defaults and preservation of existing rows, plus registration in `DataModule.kt` and an exported schema JSON at `core/database/schemas/com.riffle.core.database.RiffleDatabase/<N+1>.json`.

### Capture flow

**Direct figure annotation.**

1. Long-press on a zoomable element.
2. `FigureTapScript` builds the payload (existing `kind`, `href`, dimensions) *plus* the resolved caption *plus*, when the element is inline SVG, the serialized SVG source.
3. Payload arrives at `RiffleFigureBridge.onFigureLongPress(...)` → `EpubReaderViewModel.createImageAnnotation(payload)`.
4. The view model resolves a CFI point from the element's chapter position (reusing the same locator machinery that `createHighlight` uses today) and persists a `TYPE_IMAGE` annotation.

**Text highlight that spans figures.**

1. Existing text-selection flow runs unchanged up to `EpubReaderViewModel.createHighlight`.
2. Before persisting, the view model calls a new JS function `window.RiffleChapter.figuresInsideRange(cfiStart, cfiEnd)` that walks the selection range in the DOM, finds each enclosed figure element, applies the same caption resolution, and returns an ordered list of `{href?, svg?, caption, order}` entries.
3. The list is serialized to JSON and stored in `embeddedFigures`. Empty list → `null`.

Both flows work in paginated, vertical, and continuous modes because the figure-tap script and `RiffleChapter` bridge already run in all three (ADR 0032 continuous mode uses `RiffleChapter` via `FigureTapBridge.kt:37`).

### Highlights-mode rendering

`HighlightsPublicationFactory.renderChapterHtml()` is extended:

- For each `TYPE_IMAGE` annotation, emit a `<figure>` block containing the image (raster `<img src="…"/>` or inline `<svg>…</svg>`) and, if the caption is non-empty, a `<figcaption>`. Full width of the elided reader's content column.
- For each `TYPE_HIGHLIGHT` annotation with `embeddedFigures`, interleave the highlight's text spans with figure blocks in the order recorded, so a highlight that read "text-A [figure] text-B" renders as text-A, then the figure block, then text-B. Text spans keep the highlight's color decoration.

**Image byte access.** The factory currently owns an in-memory `Map<Url, ByteArray>` container. For raster figures, image bytes must be copied into that map at a synthetic path so the elided reader's WebView can load them. The factory gains a constructor-injected seam (a `ResourceFetcher` interface) that resolves an in-EPUB href to bytes; production wires it to the source `Publication`'s `Container`, JVM tests wire a `Map`-backed fake. Inline SVG needs no fetching — the SVG source is already stored on the annotation.

The `urlFactory` seam that exists today for JVM testing is the template. Add `resourceFetcher: (String) -> ByteArray?` with the same shape.

### Reader visualisation

Annotated figures in the *normal* reader get a coloured border matching the annotation's highlight colour, drawn as a Readium `Decoration` targeting the figure element. Rendering is JS-injected: an outline (2–3dp equivalent CSS width) around the figure's box, colour = the annotation's colour. `<figcaption>` is not decorated (caption belongs to the figure, and outlining it separately is redundant).

**Multi-annotation on the same figure — newest wins visually.** When two annotations cover the same figure (e.g. a direct `TYPE_IMAGE` plus a `TYPE_HIGHLIGHT` whose range crosses it), the reader draws a single border in the newest annotation's colour. Both annotations remain independent in the database and both appear in Highlights mode; the older one is reachable through the annotations panel and Highlights-mode view even though its border isn't drawn.

### Gesture mapping

On an already-annotated figure:

- **Tap** — zoom (unchanged from today's behaviour).
- **Long-press** — open the annotation actions sheet (edit note, change colour, delete). This is the same gesture that creates the annotation; on an already-annotated figure it edits.

On an un-annotated figure:

- **Tap** — zoom.
- **Long-press** — create a `TYPE_IMAGE` annotation with the default highlight colour.

### Sync

Web Annotation body extension. New body:

```json
{ "type": "riffle:image", "value": { "href": "…", "caption": "…" } }
```

or for SVG:

```json
{ "type": "riffle:image", "value": { "svg": "…", "caption": "…" } }
```

`TYPE_IMAGE` annotations have this body as their sole body. `TYPE_HIGHLIGHT` annotations with `embeddedFigures` carry the same shape as extra body entries alongside the existing text body. Existing sync consumers ignore unknown body types, so round-tripping degrades gracefully.

## Testing

- **JVM unit tests** for `HighlightsPublicationFactory` covering: `TYPE_IMAGE` renders `<figure>` with correct src and caption; `TYPE_HIGHLIGHT` with `embeddedFigures` interleaves text and figures in the recorded order; SVG source is inlined verbatim; missing image bytes fall back to a figcaption-only block.
- **JVM unit tests** for the caption-resolution and figure-inside-range JS helpers (extracted into a testable JS module the same way `FigureTapScript` is today).
- **`MigrationTest`** for the schema bump, per CLAUDE.md — asserts columns exist with correct nullable defaults and pre-existing rows survive.
- **Instrumentation (`make harness-test`)** for the end-to-end capture flow: long-press on a figure creates a `TYPE_IMAGE` annotation and draws a coloured border; text-selection over a figure produces a `TYPE_HIGHLIGHT` with `embeddedFigures` populated.
- **Instrumentation** for the Highlights-mode view: an image annotation renders full-size with its caption; a text highlight with an embedded figure interleaves correctly.
- **Regression tests naming.** For each of the following, name the assertion that would flip red if the fix were reverted:
  - Caption resolution falls through the chain when `<figcaption>` is absent.
  - `embeddedFigures` is null (not empty JSON `[]`) when no figures are inside the range.
  - The synthetic container serves image bytes at a synthetic path derived from the source href.
  - Newest-wins border rendering when two annotations cover the same figure.

## Open questions carried forward

- Which highlight colour applies when a text highlight spans figures — the palette's default for new highlights? The user's last-used colour? Follow the same rule as `createHighlight` today (which the existing `last-used-highlight-color` design already answers).
- Should `imageSvg` be size-bounded to avoid pathological inline `<svg>` sources bloating the annotation row? Probably yes — flag as a v1 follow-up if we see it in the wild.

## Files touched (rough map)

- `core/database/src/main/kotlin/com/riffle/core/database/AnnotationEntity.kt` — new `TYPE_IMAGE`, new columns.
- `core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt` — version bump, `MIGRATION_N_(N+1)`.
- `core/database/schemas/…/<N+1>.json` — exported by KSP after building.
- `core/database/src/androidTest/kotlin/…/MigrationTest.kt` — new step test + chain test entry.
- `core/domain/src/main/kotlin/com/riffle/core/domain/Annotation.kt` — mirror the new fields.
- `app/src/main/kotlin/com/riffle/app/feature/reader/FigureTapScript.kt` — long-press handler, caption walker, SVG serializer, `figuresInsideRange`.
- `app/src/main/kotlin/com/riffle/app/feature/reader/FigureTapBridge.kt` — new `onFigureLongPress` message.
- `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt` — `createImageAnnotation`, `createHighlight` extended with `embeddedFigures`.
- `app/src/main/kotlin/com/riffle/app/feature/reader/highlights/HighlightsPublicationFactory.kt` — figure rendering, `ResourceFetcher` seam.
- `app/src/main/kotlin/com/riffle/app/feature/reader/decorations/…` — figure-border decoration and injection JS.
- Sync serializers (existing) — new body type serialization + parsing.
