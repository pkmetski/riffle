# Share Elided View — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Share button to the elided reader's top bar that exports all highlights as a PDF and shares it via the Android share sheet.

**Architecture:** `EpubReaderViewModel.onShareElidedView()` calls `HighlightsPdfExporter.export()`, which assembles a single self-contained HTML document from the chapters already held by the ViewModel, loads it into a headless `WebView`, and renders a PDF via `PrintDocumentAdapter` without showing the system print dialog. The resulting file is wrapped by the existing `FileProvider` and shared via `ACTION_SEND`. Nav events carry the result (URI or error) from the ViewModel to the nav host (`MainScreen`), following the same pattern as `OpenInSourceBook` / `CloseEmptyHighlights`.

**Tech Stack:** Kotlin coroutines (`suspendCancellableCoroutine`, `withContext(Dispatchers.Main)`), Android `WebView.createPrintDocumentAdapter`, `PrintDocumentAdapter` non-interactive callbacks, `FileProvider`, Hilt for DI.

## Global Constraints

- All new files go under `app/src/main/kotlin/com/riffle/app/feature/reader/highlights/` (exporter) or alongside existing test files.
- New `internal` functions/constants in `:app` module are accessible across files in that module — no visibility changes needed for `renderChapterHtml`, `ACCENT_BAR_TAP_CSS`, `FIGURE_CENTERING_CSS`, `ACCENT_BAR_TAP_CLASS`, `sanitizeCssFontFamily`, `FALLBACK_ORIGIN_FONT_FAMILY`.
- Nav events are handled in `MainScreen.kt` (not inside `EpubReaderScreen`) — follow the `OpenInSourceBook` pattern.
- `ReaderSource.Highlights` gate on UI: add `shouldShowShareHighlights` to `HighlightsUiSuppression.kt` following the pattern of `shouldShowReadaloudUi` / `shouldShowOpenInBook`.
- Run tests with: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:test`

---

## File Map

| Action | File |
|--------|------|
| Modify | `app/src/main/res/xml/file_paths.xml` |
| Modify | `app/src/main/res/values/strings.xml` |
| Modify | `app/src/main/kotlin/com/riffle/app/feature/reader/highlights/HighlightsUiSuppression.kt` |
| Modify | `app/src/main/kotlin/com/riffle/app/feature/reader/ReaderNavEvent.kt` |
| Create | `app/src/main/kotlin/com/riffle/app/feature/reader/highlights/HighlightsPdfExporter.kt` |
| Modify | `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt` |
| Modify | `app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt` |
| Modify | `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt` |
| Create | `app/src/test/kotlin/com/riffle/app/feature/reader/highlights/HighlightsPdfExporterTest.kt` |
| Modify | `app/src/test/kotlin/com/riffle/app/feature/reader/highlights/HighlightsUiSuppressionTest.kt` |

---

## Task 1: FileProvider path + `shouldShowShareHighlights` predicate

**Files:**
- Modify: `app/src/main/res/xml/file_paths.xml`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/highlights/HighlightsUiSuppression.kt`
- Modify: `app/src/test/kotlin/com/riffle/app/feature/reader/highlights/HighlightsUiSuppressionTest.kt`

**Interfaces:**
- Produces: `internal fun shouldShowShareHighlights(source: ReaderSource): Boolean` (used in Task 4)

- [ ] **Step 1: Write failing tests in `HighlightsUiSuppressionTest.kt`**

Append these two tests to the existing `HighlightsUiSuppressionTest` class (before the closing `}`):

```kotlin
    @Test
    fun shareHighlightsVisibleOnlyInHighlightsMode() {
        assertTrue(shouldShowShareHighlights(ReaderSource.Highlights))
        assertFalse(shouldShowShareHighlights(ReaderSource.FullBook))
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:test --tests "com.riffle.app.feature.reader.highlights.HighlightsUiSuppressionTest" 2>&1 | tail -20
```

Expected: `Unresolved reference: shouldShowShareHighlights`

- [ ] **Step 3: Add `shouldShowShareHighlights` to `HighlightsUiSuppression.kt`**

Append after the existing `shouldShowOpenInBook` function:

```kotlin
/** Share action exports the elided view as PDF — only meaningful inside the elided reader. */
internal fun shouldShowShareHighlights(source: ReaderSource): Boolean = source == ReaderSource.Highlights
```

- [ ] **Step 4: Run to verify tests pass**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:test --tests "com.riffle.app.feature.reader.highlights.HighlightsUiSuppressionTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Add `<cache-path>` entry for exports to `file_paths.xml`**

In `app/src/main/res/xml/file_paths.xml`, add inside `<paths>`:

```xml
    <!-- Exported highlights PDF files shared via ACTION_SEND. -->
    <cache-path name="exports" path="exports/" />
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/xml/file_paths.xml \
        app/src/main/kotlin/com/riffle/app/feature/reader/highlights/HighlightsUiSuppression.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/highlights/HighlightsUiSuppressionTest.kt
git commit -m "feat(reader): shouldShowShareHighlights predicate + exports file_paths entry"
```

---

## Task 2: `HighlightsPdfExporter` — HTML assembly (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/highlights/HighlightsPdfExporter.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/highlights/HighlightsPdfExporterTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  internal fun buildCombinedHtml(
      factory: HighlightsPublicationFactory,
      chapters: List<ChapterElision>,
      bookTitle: String?,
      figureBytesByHref: Map<String, String>,
      publisherFontFaceCss: String,
      bookBodyFontFamily: String?,
  ): String
  ```
- Produces: `class HighlightsPdfExporter` (shell for Task 3)

- [ ] **Step 1: Create the test file**

Create `app/src/test/kotlin/com/riffle/app/feature/reader/highlights/HighlightsPdfExporterTest.kt`:

```kotlin
package com.riffle.app.feature.reader.highlights

import com.riffle.core.database.AnnotationEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightsPdfExporterTest {

    private val factory = HighlightsPublicationFactory()

    private fun highlight(id: String, snippet: String): AnnotationEntity = AnnotationEntity(
        id = id,
        sourceId = "S1",
        itemId = "B1",
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = "epubcfi(/6/2!/dummy)",
        textSnippet = snippet,
        note = null,
        color = AnnotationEntity.COLOR_YELLOW,
        chapterHref = "ch0.xhtml",
        spineIndex = 0,
        progression = 0.0,
        createdAt = 0L,
        updatedAt = 0L,
        originDeviceId = "test",
        lastModifiedByDeviceId = "test",
        originFontFamily = null,
        textSnippetHtml = null,
        emphasisStyles = null,
    )

    @Test
    fun combinedHtml_containsAllChapterTitles() {
        val html = buildCombinedHtml(
            factory = factory,
            chapters = listOf(
                ChapterElision("ch1.xhtml", "Chapter One", listOf(highlight("h1", "text"))),
                ChapterElision("ch2.xhtml", "Chapter Two", listOf(highlight("h2", "more text"))),
            ),
            bookTitle = "My Book",
            figureBytesByHref = emptyMap(),
            publisherFontFaceCss = "",
            bookBodyFontFamily = null,
        )
        assertTrue("Chapter One h1", html.contains("<h1>Chapter One</h1>"))
        assertTrue("Chapter Two h1", html.contains("<h1>Chapter Two</h1>"))
    }

    @Test
    fun combinedHtml_hasNoReadiumAssetLink() {
        val html = buildCombinedHtml(
            factory = factory,
            chapters = listOf(ChapterElision("ch1.xhtml", "Ch", listOf(highlight("h1", "text")))),
            bookTitle = null,
            figureBytesByHref = emptyMap(),
            publisherFontFaceCss = "",
            bookBodyFontFamily = null,
        )
        assertFalse("no readium_assets href", html.contains("readium_assets"))
    }

    @Test
    fun combinedHtml_tapSpansHidden() {
        val html = buildCombinedHtml(
            factory = factory,
            chapters = listOf(ChapterElision("ch1.xhtml", "Ch", listOf(highlight("h1", "text")))),
            bookTitle = null,
            figureBytesByHref = emptyMap(),
            publisherFontFaceCss = "",
            bookBodyFontFamily = null,
        )
        // The tap-dispatch span class must be hidden in the exported PDF.
        assertTrue("tap class hidden", html.contains(".$ACCENT_BAR_TAP_CLASS") && html.contains("display:none"))
    }

    @Test
    fun combinedHtml_includesPublisherFontFaceWhenNonBlank() {
        val fontCss = "@font-face { font-family: TestFont; src: url(data:font/woff2;base64,abc); }"
        val html = buildCombinedHtml(
            factory = factory,
            chapters = listOf(ChapterElision("ch1.xhtml", "Ch", listOf(highlight("h1", "text")))),
            bookTitle = null,
            figureBytesByHref = emptyMap(),
            publisherFontFaceCss = fontCss,
            bookBodyFontFamily = null,
        )
        assertTrue("publisher font-face present", html.contains("TestFont"))
    }

    @Test
    fun combinedHtml_skipsChaptersWithNoHighlights() {
        val html = buildCombinedHtml(
            factory = factory,
            chapters = listOf(
                ChapterElision("ch1.xhtml", "Has Highlights", listOf(highlight("h1", "text"))),
                ChapterElision("ch2.xhtml", "Empty Chapter", emptyList()),
            ),
            bookTitle = null,
            figureBytesByHref = emptyMap(),
            publisherFontFaceCss = "",
            bookBodyFontFamily = null,
        )
        assertTrue("non-empty chapter present", html.contains("<h1>Has Highlights</h1>"))
        assertFalse("empty chapter absent", html.contains("<h1>Empty Chapter</h1>"))
    }
}
```

- [ ] **Step 2: Run to verify tests fail**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:test --tests "com.riffle.app.feature.reader.highlights.HighlightsPdfExporterTest" 2>&1 | tail -20
```

Expected: `Unresolved reference: buildCombinedHtml`

- [ ] **Step 3: Create `HighlightsPdfExporter.kt` with `buildCombinedHtml` and class shell**

Create `app/src/main/kotlin/com/riffle/app/feature/reader/highlights/HighlightsPdfExporter.kt`:

```kotlin
package com.riffle.app.feature.reader.highlights

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Exports the elided reader's full highlight set as a self-contained PDF via a headless
 * [android.webkit.WebView] and [android.print.PrintDocumentAdapter].
 *
 * [buildCombinedHtml] is an `internal` top-level function so JVM unit tests can exercise HTML
 * assembly without a live [Context] or [android.webkit.WebView].
 */
class HighlightsPdfExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val factory: HighlightsPublicationFactory,
) {
    /**
     * Assembles the combined HTML for all chapters, renders it to a PDF via a headless WebView,
     * writes the result to `cacheDir/exports/annotations-<itemId>.pdf`, and returns a
     * [FileProvider] URI suitable for [android.content.Intent.ACTION_SEND].
     *
     * Must be called from a coroutine; the WebView and PrintDocumentAdapter callbacks run on the
     * main thread ([kotlinx.coroutines.Dispatchers.Main]).
     */
    suspend fun export(
        chapters: List<ChapterElision>,
        bookTitle: String?,
        itemId: String,
        figureBytesByHref: Map<String, String>,
        publisherFontFaceCss: String,
        bookBodyFontFamily: String?,
    ): Uri {
        val html = buildCombinedHtml(factory, chapters, bookTitle, figureBytesByHref, publisherFontFaceCss, bookBodyFontFamily)
        val exportsDir = File(context.cacheDir, "exports").also { it.mkdirs() }
        val pdfFile = File(exportsDir, "annotations-${itemId.take(64)}.pdf")
        renderToPdf(html, bookTitle, pdfFile)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
    }

    // WebView + PrintDocumentAdapter rendering — implemented in Task 3.
    private suspend fun renderToPdf(html: String, title: String?, outFile: File): Unit = TODO("Task 3")
}

// ─── PDF base styles ─────────────────────────────────────────────────────────

private const val PDF_BASE_CSS =
    "body{font-size:14pt;line-height:1.6;margin:0;padding:16pt;}" +
        "h1{font-size:16pt;border-bottom:1px solid #cccccc;padding-bottom:4pt;" +
        "margin:24pt 0 12pt;page-break-after:avoid;}" +
        "h1:first-child{margin-top:0;}" +
        "p{margin:0.75em 0;}" +
        "aside{margin:0.5em 0;}"

// ─── HTML assembly ────────────────────────────────────────────────────────────

/**
 * Assembles a single self-contained `<html>` document from [chapters] for PDF export.
 *
 * Each chapter is rendered via [HighlightsPublicationFactory.renderChapterHtml]; only the
 * `<body>` content is extracted and concatenated. The combined `<head>` substitutes
 * [PDF_BASE_CSS] for the `READIUM_DEFAULT_CSS_LINK` (which is served by Readium's
 * `WebViewServer` and is not resolvable outside the live reader WebView). Tap-dispatch spans
 * ([ACCENT_BAR_TAP_CLASS]) are hidden via CSS — they have no meaning in a static PDF.
 *
 * All images are already base64 data URIs inside the chapter HTML; the result is fully
 * self-contained with no external resource references.
 */
internal fun buildCombinedHtml(
    factory: HighlightsPublicationFactory,
    chapters: List<ChapterElision>,
    bookTitle: String?,
    figureBytesByHref: Map<String, String>,
    publisherFontFaceCss: String,
    bookBodyFontFamily: String?,
): String {
    val safeTitle = bookTitle
        ?.replace("&", "&amp;")?.replace("<", "&lt;")?.replace("\"", "&quot;")
        ?: "Annotations"

    val bodyParts = chapters
        .filter { it.highlights.isNotEmpty() }
        .joinToString("\n") { chapter ->
            val chapterXhtml = factory.renderChapterHtml(
                chapter, bookBodyFontFamily, figureBytesByHref, publisherFontFaceCss,
            )
            // Extract content between <body> and </body>; the chapter XHTML is authored by
            // renderChapterHtml and always contains exactly one <body> block.
            val bodyStart = chapterXhtml.indexOf("<body>").takeIf { it >= 0 } ?: return@joinToString ""
            val bodyEnd = chapterXhtml.lastIndexOf("</body>").takeIf { it >= 0 } ?: return@joinToString ""
            chapterXhtml.substring(bodyStart + "<body>".length, bodyEnd).trim()
        }

    val bodyFontRule = run {
        val safe = sanitizeCssFontFamily(
            bookBodyFontFamily?.takeIf { it != FALLBACK_ORIGIN_FONT_FAMILY },
        ) ?: return@run ""
        "body,h1,h2,h3,h4,h5,h6{font-family:$safe;}"
    }

    return buildString {
        append("<!DOCTYPE html><html><head>")
        append("<meta charset=\"utf-8\"/>")
        append("<title>$safeTitle</title>")
        append("<style>")
        append(PDF_BASE_CSS)
        append(ACCENT_BAR_TAP_CSS)
        append(FIGURE_CENTERING_CSS)
        // Hide tap-dispatch spans — they serve no purpose in a static PDF.
        append(".$ACCENT_BAR_TAP_CLASS{display:none}")
        if (publisherFontFaceCss.isNotBlank()) append(publisherFontFaceCss)
        if (bodyFontRule.isNotBlank()) append(bodyFontRule)
        append("</style></head><body>")
        append(bodyParts)
        append("</body></html>")
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:test --tests "com.riffle.app.feature.reader.highlights.HighlightsPdfExporterTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` (all 5 tests green)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/highlights/HighlightsPdfExporter.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/highlights/HighlightsPdfExporterTest.kt
git commit -m "feat(reader): HighlightsPdfExporter HTML assembly + unit tests"
```

---

## Task 3: `HighlightsPdfExporter` — PDF rendering

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/highlights/HighlightsPdfExporter.kt`

**Interfaces:**
- Completes: `private suspend fun renderToPdf(html: String, title: String?, outFile: File)`
- Consumes: `buildCombinedHtml` from Task 2

- [ ] **Step 1: Replace the `renderToPdf` stub with the real implementation**

Replace the `TODO("Task 3")` body of `renderToPdf` in `HighlightsPdfExporter.kt` with:

```kotlin
    private suspend fun renderToPdf(html: String, title: String?, outFile: File) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                val webView = android.webkit.WebView(context)
                webView.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String) {
                        val adapter = webView.createPrintDocumentAdapter(title ?: "Annotations")
                        val attributes = android.print.PrintAttributes.Builder()
                            .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4)
                            .setResolution(
                                android.print.PrintAttributes.Resolution("pdf", "pdf", 300, 300),
                            )
                            .setMinMargins(android.print.PrintAttributes.Margins.NO_MARGINS)
                            .build()
                        adapter.onLayout(
                            null, attributes, null,
                            object : android.print.PrintDocumentAdapter.LayoutResultCallback() {
                                override fun onLayoutFinished(
                                    info: android.print.PrintDocumentInfo,
                                    changed: Boolean,
                                ) {
                                    val pfd = android.os.ParcelFileDescriptor.open(
                                        outFile,
                                        android.os.ParcelFileDescriptor.MODE_READ_WRITE or
                                            android.os.ParcelFileDescriptor.MODE_CREATE or
                                            android.os.ParcelFileDescriptor.MODE_TRUNCATE,
                                    )
                                    adapter.onWrite(
                                        arrayOf(android.print.PageRange.ALL_PAGES),
                                        pfd,
                                        null,
                                        object : android.print.PrintDocumentAdapter.WriteResultCallback() {
                                            override fun onWriteFinished(
                                                pages: Array<out android.print.PageRange>,
                                            ) {
                                                pfd.close()
                                                webView.destroy()
                                                cont.resume(Unit)
                                            }

                                            override fun onWriteFailed(error: CharSequence?) {
                                                pfd.close()
                                                webView.destroy()
                                                cont.resumeWithException(
                                                    java.io.IOException("PDF write failed: $error"),
                                                )
                                            }
                                        },
                                    )
                                }

                                override fun onLayoutFailed(error: CharSequence?) {
                                    webView.destroy()
                                    cont.resumeWithException(
                                        java.io.IOException("PDF layout failed: $error"),
                                    )
                                }
                            },
                            null,
                        )
                    }
                }
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                cont.invokeOnCancellation { webView.destroy() }
            }
        }
    }
```

Also add the missing import for `suspendCancellableCoroutine` at the top of the file. The full imports block should be:

```kotlin
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
```

- [ ] **Step 2: Verify it compiles**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|warning:|BUILD" | tail -20
```

Expected: no errors, `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/highlights/HighlightsPdfExporter.kt
git commit -m "feat(reader): HighlightsPdfExporter PDF rendering via PrintDocumentAdapter"
```

---

## Task 4: Nav events + string resource + ViewModel wiring

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/ReaderNavEvent.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`

**Interfaces:**
- Produces: `ReaderNavEvent.ShareHighlights(val uri: Uri)` (used in Task 5 — MainScreen)
- Produces: `ReaderNavEvent.ExportError` (used in Task 5 — MainScreen)
- Produces: `val isExporting: StateFlow<Boolean>` on ViewModel (used in Task 5 — EpubReaderScreen)
- Produces: `fun onShareElidedView()` on ViewModel (used in Task 5 — EpubReaderScreen)
- Consumes: `HighlightsPdfExporter.export(...)` from Task 3

- [ ] **Step 1: Add string resource**

In `app/src/main/res/values/strings.xml`, add inside `<resources>`:

```xml
    <string name="export_pdf_error">Couldn\'t generate PDF</string>
```

- [ ] **Step 2: Add nav event variants to `ReaderNavEvent.kt`**

Replace the file content with:

```kotlin
package com.riffle.app.feature.reader

import android.net.Uri

/**
 * Navigation events emitted by [EpubReaderViewModel] that the nav host (MainScreen) must act on
 * outside the reader's own back stack — e.g. leaving the elided Highlights-mode reader to open the
 * real source book (ADR 0041, Task 9).
 */
sealed interface ReaderNavEvent {
    data class OpenInSourceBook(val sourceId: String, val itemId: String, val cfi: String) : ReaderNavEvent

    /**
     * Highlights-mode reader has no highlights left (user deleted the last one). The synthesised
     * Publication would have an empty readingOrder — Readium's navigator crashes on that — so the
     * nav host must pop the reader off the back stack instead of letting the VM reopen an empty
     * book.
     */
    object CloseEmptyHighlights : ReaderNavEvent

    /** The elided view PDF was generated successfully; the nav host fires [android.content.Intent.ACTION_SEND]. */
    data class ShareHighlights(val uri: Uri) : ReaderNavEvent

    /** PDF generation failed; the nav host shows a toast. */
    object ExportError : ReaderNavEvent
}
```

- [ ] **Step 3: Wire ViewModel — new fields and `onShareElidedView()`**

In `EpubReaderViewModel.kt`:

3a. Add `private var elidedBookTitle: String? = null` near the other `private var elidedBodyFontFamily` declaration (around line 520). Place it immediately after that line:

```kotlin
private var elidedBodyFontFamily: String? = null
private var elidedBookTitle: String? = null
```

3b. Store `realBookTitle` right after it is fetched inside the `openBook()` Highlights branch (around line 1195, where `val realBookTitle = libraryItemDao.getById(sourceId, itemId)?.title` appears):

```kotlin
val realBookTitle = libraryItemDao.getById(sourceId, itemId)?.title
elidedBookTitle = realBookTitle
```

3c. Add `HighlightsPdfExporter` to the ViewModel's `@Inject constructor` parameters. The constructor already has many parameters; add `private val pdfExporter: HighlightsPdfExporter` alongside them (alphabetical order is not required — add it near the end of the parameter list).

3d. Add `isExporting` state near the other `MutableStateFlow` declarations (search for `_tocVisible` or similar patterns and add alongside):

```kotlin
private val _isExporting = MutableStateFlow(false)
val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()
```

3e. Add `onShareElidedView()` as a new `fun` in the ViewModel (near other `fun` declarations used by the top bar, e.g. near `openSearch()` / `openToc()`):

```kotlin
fun onShareElidedView() {
    if (_isExporting.value) return
    val chapters = highlightsResumeChapters ?: return
    val handle = highlightsPublicationHandle ?: return
    viewModelScope.launch {
        _isExporting.value = true
        try {
            val uri = pdfExporter.export(
                chapters = chapters,
                bookTitle = elidedBookTitle,
                itemId = itemId,
                figureBytesByHref = handle.figureBytesByHref,
                publisherFontFaceCss = handle.publisherFontFaceCss,
                bookBodyFontFamily = elidedBodyFontFamily,
            )
            _readerNavEvents.send(ReaderNavEvent.ShareHighlights(uri))
        } catch (e: Exception) {
            _readerNavEvents.send(ReaderNavEvent.ExportError)
        } finally {
            _isExporting.value = false
        }
    }
}
```

- [ ] **Step 4: Verify it compiles**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD" | tail -20
```

Expected: no errors

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/kotlin/com/riffle/app/feature/reader/ReaderNavEvent.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "feat(reader): onShareElidedView + ShareHighlights/ExportError nav events"
```

---

## Task 5: Screen wiring + manual verification

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

**Interfaces:**
- Consumes: `shouldShowShareHighlights` from Task 1
- Consumes: `ReaderNavEvent.ShareHighlights`, `ReaderNavEvent.ExportError` from Task 4
- Consumes: `viewModel.isExporting: StateFlow<Boolean>` from Task 4
- Consumes: `viewModel.onShareElidedView()` from Task 4

- [ ] **Step 1: Handle new nav events in `MainScreen.kt`**

In `MainScreen.kt`, locate the `when (event)` block around line 769 that handles `OpenInSourceBook` and `CloseEmptyHighlights`. Add two new branches:

```kotlin
                        when (event) {
                            is com.riffle.app.feature.reader.ReaderNavEvent.OpenInSourceBook -> {
                                val encodedId = URLEncoder.encode(event.itemId, "UTF-8")
                                val encodedCfi = URLEncoder.encode(event.cfi, "UTF-8")
                                navController.popBackStack()
                                navController.navigate("epub_reader/$encodedId?openAtCfi=$encodedCfi")
                            }
                            com.riffle.app.feature.reader.ReaderNavEvent.CloseEmptyHighlights -> {
                                navController.popBackStack()
                            }
                            is com.riffle.app.feature.reader.ReaderNavEvent.ShareHighlights -> {
                                val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(android.content.Intent.EXTRA_STREAM, event.uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(sendIntent, null))
                            }
                            com.riffle.app.feature.reader.ReaderNavEvent.ExportError -> {
                                android.widget.Toast.makeText(
                                    context,
                                    com.riffle.app.R.string.export_pdf_error,
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
```

The `context` variable is already accessible in this composable scope via `LocalContext.current` — check the top of the lambda for the existing `val context = LocalContext.current` binding; add one if absent.

- [ ] **Step 2: Add share button to `EpubReaderScreen.kt` top bar**

In `EpubReaderScreen.kt`:

2a. Add the import alongside the other `shouldShow*` imports (around line 51–52):

```kotlin
import com.riffle.app.feature.reader.highlights.shouldShowShareHighlights
```

2b. Collect `isExporting` near the other `collectAsState()` calls (around line 189–280):

```kotlin
val isExporting by viewModel.isExporting.collectAsState()
```

2c. Inside the `actions = { … }` block of the `TopAppBar` (the block starting around line 797), add the following **after** all existing action icons and **before** the closing `}` of `if (state is ReaderState.Ready)`:

```kotlin
                            if (shouldShowShareHighlights(viewModel.readerSource)) {
                                if (isExporting) {
                                    Box(
                                        modifier = Modifier.size(48.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                } else {
                                    IconButton(onClick = viewModel::onShareElidedView) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = "Share annotations as PDF",
                                        )
                                    }
                                }
                            }
```

`Box`, `Alignment`, `CircularProgressIndicator`, and `Icons.Default` will already be imported in this file. Add any missing imports the IDE flags.

- [ ] **Step 3: Verify it compiles**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD" | tail -20
```

Expected: no errors

- [ ] **Step 4: Run all `:app` JVM tests to confirm nothing regressed**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(reader): share button in elided reader top bar, wire ShareHighlights intent"
```
