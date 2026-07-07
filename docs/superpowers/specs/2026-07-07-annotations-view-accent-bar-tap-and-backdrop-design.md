# Annotations View — Accent-Bar Tap Target & Continuous Backdrop

## Problems

In the annotations reading view (`ReaderSource.Highlights`), three related defects:

- **A.** In continuous mode, tapping *anywhere on highlighted text* opens the highlight edit menu. Only tapping the visible colored side line (the accent bar) should.
- **B.** In paginated and vertical modes, tapping the accent bar does nothing. Nothing at all opens the menu today.
- **C.** In continuous mode, when a chapter's synthesised body is short (few highlights), the area below the body renders as grey instead of the reader theme's background.

FullBook mode is unaffected by any of these — it uses `HighlightTintStyle` + Readium's `onDecorationActivated`, a separate path that keeps on-text tinted highlights and tap-to-edit.

## Goals

- Highlights mode: taps on the accent bar open the highlight edit menu. Taps on the highlighted text do nothing (fall through to normal WebView tap → immersive toggle).
- Uniform behavior across paginated, vertical, and continuous.
- Continuous mode: backdrop below a short chapter matches the reader theme background.

## Non-goals

- No change to FullBook mode tap dispatch or highlight visuals.
- No change to the visible accent-bar (border-left) styling in the synthesised HTML.
- No change to note-glyph tap dispatch.

## Design

### Tap dispatch (Bugs A + B)

The visible accent bar is a CSS `border-left: 4px solid <color>` on the `<p>` synthesised by `HighlightsPublicationFactory.renderChapterHtml`. That HTML is loaded by all three modes (paginated, vertical, and continuous — paginated/vertical via Readium's `EpubNavigatorFragment` on the synthesised `Publication`; continuous via `ContinuousReaderView` reading the same `Container`). We can therefore fix both bugs with a *single* mechanism inside that HTML.

**In `renderChapterHtml`:** inside every synthesised highlight `<p>`, inject an absolutely-positioned transparent tap strip covering the border-left + padding-left gutter:

```html
<span class="riffle-hl-tap" data-ann-id="<id>"
      onclick="location.href='riffle-annotation-tap:<id>'"></span>
```

CSS positions it `position:absolute; left:-16px; top:0; bottom:0; width:20px;` so it overlaps the visible 4px border and 12px padding. The `<p>` gets `position:relative` so the absolute child anchors correctly.

**Dispatch:** `location.href='riffle-annotation-tap:<id>'` is intercepted before it navigates. The scheme string `riffle-annotation-tap:` is Riffle's convention for this feature.

- **Paginated / vertical:** Readium's `EpubNavigatorFragment` exposes a `Navigator.Listener.onExternalLinkActivated` / `Navigator.Listener.onResourceLoadFailed` chain and a `WebViewClient.shouldOverrideUrlLoading` under the hood. We add an override that recognises the `riffle-annotation-tap:` scheme and routes the id to `ReadiumPresenter.feedAnnotationHighlightTap(href = currentHref, annotationId = id)`. Any other unknown scheme falls through to Readium's default handling.
- **Continuous:** `ChapterWebView`'s existing `WebViewClient.shouldOverrideUrlLoading` gets the same check, routing to `ContinuousPresenter.feedAnnotationHighlightTap`.

**In continuous mode's applied `<mark>` (`ContinuousStyleInjector.applyAnnotationHighlightsJs`):** remove the `click` listener from the `<mark>` when running in Highlights mode. The `<mark>` still wraps text (needed for locator resolution and any future color-change), but it no longer intercepts taps — the injected tap strip in the synthesised `<p>` owns clicks.

`HighlightAccentBarStyle` (the invisible Readium decoration that was supposed to be the tap zone) is deleted along with its template + the `useAccentBarStyle=true` branch in `ContinuousHighlightRenderer` and `ReadiumHighlightRenderer`. Nothing else consumes it.

### Continuous backdrop (Bug C)

`ContinuousReaderView`'s root Composable currently paints no background, so a chapter whose body is shorter than the viewport shows the platform default (grey) beneath. Fix: apply `Modifier.background(readerBackgroundColor)` on the root, sourced from the effective reader theme the composable already observes for other purposes.

Extract the theme→color mapping into a pure function `continuousBackdropColor(theme: ReaderTheme): Color` so it's JVM-testable.

## Testing

Every test below pins a specific behavior; reverting the fix flips at least one red.

- **`HighlightsPublicationFactoryTest`** (extend existing):
  - Each synthesised highlight `<p>` contains exactly one `<span class="riffle-hl-tap" data-ann-id="…">` and its `onclick` targets the `riffle-annotation-tap:` scheme with the annotation id.
  - The `<p>` carries `position: relative` so the absolute tap strip anchors.
- **`ContinuousStyleInjectorTest`** (new or extend existing):
  - In Highlights mode, the emitted JS body wraps text in `<mark data-riffle-ann="…">` but no code path attaches a `click` listener on the mark.
  - The FullBook branch is untouched (still attaches the listener).
- **`AnnotationTapUrlSchemeTest`** (new):
  - Introduce a pure decoder `parseAnnotationTapUrl(url: String): String?`. `parseAnnotationTapUrl("riffle-annotation-tap:abc-123") == "abc-123"`; `parseAnnotationTapUrl("https://example.com") == null`; empty id returns null.
- **`ContinuousBackdropColorTest`** (new):
  - `continuousBackdropColor(ReaderTheme.Light) == Color.White`; `Dark` / `DarkDim` / `Sepia` / `Auto` each map to the expected value.

## Non-goals for tests

- No instrumentation test forcing a real WebView tap — the JVM assertions above already flip if the injected HTML, injected JS, URL scheme decoder, or backdrop color function is reverted.

## Rollout

Single PR, additive at the DOM and dispatch layers. No data migration. No user-visible change to FullBook. Users in Highlights mode see the same visuals; only tap targets and (in continuous) the backdrop change.
