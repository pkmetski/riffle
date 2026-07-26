# Share Elided View — Design

**Date:** 2026-07-26
**Branch:** pkmetski/share-elided-view

## Goal

Add a share action to the elided reader ("Highlights" mode) that exports the entire book's annotations as a self-contained PDF and shares it via the Android share sheet. The action is only available inside the elided view — not from `AnnotationsListScreen`.

---

## Architecture & data flow

```
Share button (top bar, Highlights mode only)
  → EpubReaderViewModel.onShareElidedView()
      → HighlightsPdfExporter.export(combinedHtml, appContext)   [IO + Main dispatchers]
          → hidden WebView  →  PrintDocumentAdapter  →  cacheDir PDF file
      → ReaderNavEvent.ShareFile(uri)
  → EpubReaderScreen  →  ACTION_SEND intent
```

`HighlightsPdfExporter` is a new Hilt-injectable class. The ViewModel owns the export call; the screen only handles the resulting nav event. The screen never touches the exporter directly.

---

## HTML assembly

`HighlightsPdfExporter` builds a single `<html>` document from the chapter data already held in `EpubReaderViewModel`:

1. Iterate all `ChapterElision`s (filtered to non-empty) and call `HighlightsPublicationFactory.renderChapterHtml()` (already `internal`, accessible within `:app`) for each.
2. Extract the `<body>` content from each chapter's XHTML (simple substring between `<body>` and `</body>`).
3. Wrap all body content in one `<html><head>…</head><body>…</body></html>` whose `<head>` contains:
   - `<meta charset="utf-8"/>`
   - PDF-friendly base CSS: readable `font-size`, `line-height`, `body` padding, `h1` border-bottom separating chapters.
   - `ACCENT_BAR_TAP_CSS` and `FIGURE_CENTERING_CSS` (imported as constants from `HighlightsPublicationFactory`).
   - `publisherFontFaceCss` from `HighlightsPublicationHandle` (may be empty).
   - Body-font rule if `bookBodyFontFamily` is non-null.
   - `.riffle-hl-tap { display: none; }` — tap-dispatch spans are invisible in the printed output.
4. **No `READIUM_DEFAULT_CSS_LINK`** — that URL (`https://readium_assets/…`) is served by Readium's `WebViewServer` and is not resolvable in the headless export WebView. PDF-friendly base styles replace the Readium defaults for the export context only.

All images are already data URIs inside the chapter HTML (`imageBytes` / `dataUriByHref` entries embedded by `HighlightsPublicationFactory`). The resulting document is fully self-contained with no external resource references.

---

## PDF rendering

1. On `Dispatchers.Main`, create `WebView(applicationContext)` without attaching it to the window — sufficient for the non-interactive `PrintDocumentAdapter` path.
2. Call `webView.loadDataWithBaseURL(null, combinedHtml, "text/html", "UTF-8", null)`.
3. Wait for `WebViewClient.onPageFinished` via `suspendCancellableCoroutine`.
4. Obtain `val adapter = webView.createPrintDocumentAdapter(bookTitle ?: "Annotations")`.
5. Call `adapter.onLayout(null, printAttributes, null, layoutCallback, null)` with A4 portrait attributes.
6. In `onLayoutFinished`, call `adapter.onWrite(arrayOf(PageRange.ALL_PAGES), parcelFileDescriptor, null, writeCallback)`.
7. In `onWriteFinished`, close the `ParcelFileDescriptor` and return the file `Uri`.
8. Detach and destroy the WebView after the PDF is written.

The PDF file is written to `context.cacheDir` as `annotations-<itemId>.pdf`. A `FileProvider` authority (already required by other Android sharing conventions — add if absent) wraps the file before sharing.

---

## Loading state & UI

- Share button lives in the elided reader's existing top bar, rendered only when `readerSource == ReaderSource.Highlights`.
- Icon: `Icons.Default.Share`.
- While `isExporting == true` (new `StateFlow<Boolean>` on the ViewModel), the icon is replaced by a `CircularProgressIndicator` of the same size and the button is non-interactive.
- On success: `ReaderNavEvent.ShareFile(uri)` → screen fires `ACTION_SEND` intent with `type = "application/pdf"` and `EXTRA_STREAM = uri`.
- On failure: `ReaderNavEvent.ShowSnackbar(messageResId)` with a generic "Couldn't generate PDF" string.

---

## New files

| File | Purpose |
|------|---------|
| `app/…/reader/highlights/HighlightsPdfExporter.kt` | HTML assembly + headless WebView PDF rendering |

## Modified files

| File | Change |
|------|--------|
| `EpubReaderViewModel.kt` | `onShareElidedView()`, `isExporting` state, inject `HighlightsPdfExporter` |
| `EpubReaderScreen.kt` | Share button in top bar (Highlights mode only), handle `ShareFile` nav event |
| `ReaderNavEvent.kt` | Add `ShareFile(uri: Uri)` |
| `HighlightsPublicationFactory.kt` | No change needed — `ACCENT_BAR_TAP_CSS` and `FIGURE_CENTERING_CSS` are already `internal const` |
| `AndroidManifest.xml` | Add / confirm `FileProvider` for `cacheDir` |

---

## Testing

**Unit (JVM):**
- `HighlightsPdfExporterTest.combinedHtml_containsAllChapterTitles` — given N `ChapterElision`s, the assembled HTML has N `<h1>` elements.
- `HighlightsPdfExporterTest.combinedHtml_hasNoReadiumAssetLink` — the combined HTML contains no `readium_assets` href.
- `HighlightsPdfExporterTest.combinedHtml_tapSpansHidden` — the combined HTML contains `.riffle-hl-tap { display: none }`.
- `HighlightsPdfExporterTest.combinedHtml_includesPublisherFontFaceWhenNonBlank` — publisher font CSS is present when supplied.

**Instrumentation:**
- `ShareElidedViewTest` (harness) — open a book in Highlights mode, tap share, assert a non-empty PDF file is written to `cacheDir` before the share intent fires (verify file size > 0).

---

## Out of scope

- Per-annotation share (single highlight → share sheet) — separate feature.
- Sharing from `AnnotationsListScreen` — explicitly excluded.
- Export formats other than PDF.
