package com.riffle.app.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for the Highlights-mode (ADR 0041) live per-annotation DOM-patch pipeline.
 *
 * The elided reader USED to rebuild its whole synthesised Publication on every colour / note /
 * delete edit via `reloadHighlightsView()` — the resulting Loading→Ready state flash was visible
 * and the Readium fragment unmounted mid-edit. The new design keeps the Publication and rewrites
 * ONE annotation's DOM in place via a `HighlightsDomPatch`, so the reader repaints in-place.
 *
 * These assertions pin the wiring that a future refactor could regress:
 *  - `openBook`'s Highlights branch starts the observer with the freshly rendered chapters and
 *    snapshots the rendered set,
 *  - `ensureHighlightsObserver` subscribes to the annotation DAO Flow, diffs per-annotation, and
 *    for a non-structural change emits a `HighlightsDomPatch` on `_highlightDomPatches` AND
 *    rewrites the touched chapter's bytes via the publication handle,
 *  - `recolorHighlight`, `updateHighlightNote`, `deleteHighlight`, `deleteAnnotation` do NOT
 *    fire the old `reloadHighlightsView()` in the Highlights branch — the observer path is now
 *    the single source of truth for repainting.
 *
 * Cheaper than instantiating the Hilt/Readium-coupled VM, and pin the exact regressions that
 * would silently revert to the old flash-through-Loading UX.
 */
class HighlightsLiveUpdateObserverTest {

    @Test
    fun `openBook Highlights branch starts the annotation observer with the rendered chapters`() {
        val body = extractFunctionBody(vmSource(), "openBook")
            ?: error("openBook not found in EpubReaderViewModel.kt")
        val hlBranch = body.substringAfter("source == ReaderSource.Highlights")
        assertTrue(
            "openBook Highlights branch must call ensureHighlightsObserver(sourceId, itemId) so " +
                "the observer subscribes to per-book annotation changes.",
            hlBranch.contains("ensureHighlightsObserver("),
        )
        assertTrue(
            "openBook Highlights branch must snapshot the rendered set by id (highlightsRenderedById) " +
                "so the observer can diff subsequent emissions per-annotation. Without this the " +
                "observer would re-fire on every echo emission.",
            hlBranch.contains("highlightsRenderedById ="),
        )
        assertTrue(
            "openBook Highlights branch must cache the HighlightsPublicationHandle so per-annotation " +
                "byte rewrites (via setChapterBytes) can persist through chapter-back navigation.",
            hlBranch.contains("highlightsPublicationHandle ="),
        )
    }

    @Test
    fun `openBook Highlights branch filters the baseline snapshot to TYPE_HIGHLIGHT`() {
        // Regression guard for the elided-view infinite-load + repeated-WebDAV-push bug:
        // getForItem returns ALL annotation types (highlights, bookmarks, images), but the observer
        // filters incomingById to TYPE_HIGHLIGHT. If openBook's baseline associated over the raw
        // rows, every bookmark would appear as a removed id on the first emission, structural=true
        // fires (any bookmark in a chapter with zero highlights), reloadHighlightsView() runs → openBook
        // rewrites the same all-types baseline → infinite loop, each cycle re-binding the annotation
        // session and firing a WebDAV syncOnOpen.
        val body = extractFunctionBody(vmSource(), "openBook")
            ?: error("openBook not found in EpubReaderViewModel.kt")
        val hlBranch = body.substringAfter("source == ReaderSource.Highlights")
        val assignment = Regex(
            """highlightsRenderedById\s*=\s*rows[\s\S]*?\.associateBy\s*\{\s*it\.id\s*}""",
        ).find(hlBranch)?.value ?: error(
            "highlightsRenderedById = rows…associateBy { it.id } not found in Highlights branch",
        )
        assertTrue(
            "openBook's Highlights baseline must filter rows to AnnotationEntity.TYPE_HIGHLIGHT " +
                "before associating by id — otherwise bookmarks in the same book perpetually mismatch " +
                "the observer's TYPE_HIGHLIGHT-filtered incomingById and reloadHighlightsView() loops " +
                "forever, firing a WebDAV syncOnOpen on every cycle.",
            assignment.contains("AnnotationEntity.TYPE_HIGHLIGHT"),
        )
    }

    @Test
    fun `ensureHighlightsObserver subscribes and emits DOM patches on partial change`() {
        val body = extractFunctionBody(vmSource(), "ensureHighlightsObserver")
            ?: error("ensureHighlightsObserver not found — did it move?")
        assertTrue(
            "ensureHighlightsObserver must subscribe to annotationDao.observeAnnotationsByPosition " +
                "so it has enough per-row data (chapterHref, spineIndex, progression) to compute a " +
                "structural-vs-partial diff without a second DAO round-trip.",
            body.contains("annotationDao.observeAnnotationsByPosition("),
        )
        assertTrue(
            "ensureHighlightsObserver must emit HighlightsDomPatch events on the SharedFlow so the " +
                "screen can apply targeted DOM mutations instead of a full reload.",
            body.contains("_highlightDomPatches.tryEmit("),
        )
        assertTrue(
            "ensureHighlightsObserver must emit a Recolor patch for a per-annotation colour change.",
            body.contains("HighlightsDomPatch.Recolor("),
        )
        assertTrue(
            "ensureHighlightsObserver must emit a SetNote patch for a note add/update/remove.",
            body.contains("HighlightsDomPatch") && body.contains(".SetNote("),
        )
        assertTrue(
            "ensureHighlightsObserver must emit a Remove patch when a highlight leaves the set " +
                "(without emptying its chapter).",
            body.contains("HighlightsDomPatch.Remove("),
        )
        assertTrue(
            "ensureHighlightsObserver must call HighlightsPublicationHandle.setChapterBytes to persist " +
                "the rewritten HTML so a chapter-back navigation still shows the fresh state.",
            body.contains("setChapterBytes("),
        )
        assertTrue(
            "ensureHighlightsObserver must fall back to reloadHighlightsView / reloadOrCloseHighlightsAfterDelete " +
                "for structural changes (new-chapter add, chapter emptied).",
            body.contains("reloadHighlightsView(") ||
                body.contains("reloadOrCloseHighlightsAfterDelete("),
        )
        assertTrue(
            "ensureHighlightsObserver must refresh highlightsRenderedById after applying patches so " +
                "the next emission's diff is against what's actually on screen.",
            body.contains("highlightsRenderedById = incomingById"),
        )
        assertTrue(
            "ensureHighlightsObserver must filter the store Flow to TYPE_HIGHLIGHT only — bookmarks " +
                "share the annotations table but the elided reader's spine, patches, and byte " +
                "rewrites are highlights-only; a bookmark leaking through would (a) be rendered as a " +
                "fake highlight paragraph after a byte rewrite, (b) block the empty-close check, and " +
                "(c) count as a structural add and re-trigger the flash-through-Loading rebuild.",
            body.contains("AnnotationEntity.TYPE_HIGHLIGHT"),
        )
    }

    @Test
    fun `screen dispatches DOM patches through the presenter, not the renderer bridge`() {
        // Regression guard for the "Annotations View live-update dead in continuous mode" bug.
        // RendererBridge wraps only Readium's EpubNavigatorFragment; in continuous mode the
        // fragment is parked at height=0 and holds no elided DOM, so a bridge-only dispatch
        // silently drops every non-structural patch. Routing through ReaderPresenter lets
        // ContinuousPresenter fan the patch out to its live chapter WebViews.
        val screen = File("src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt")
            .let { if (it.exists()) it else File("app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt") }
        val text = screen.readText()
        assertTrue(
            "EpubReaderScreen must dispatch highlightDomPatches through readerPresenter." +
                "applyHighlightDomPatch — a bridge-only dispatch drops the patch in continuous mode.",
            text.contains("readerPresenter.applyHighlightDomPatch("),
        )
        assertFalse(
            "EpubReaderScreen must NOT dispatch highlightDomPatches via rendererBridge directly; " +
                "the bridge is Readium-only and silently drops patches in continuous mode.",
            text.contains("rendererBridge.applyHighlightDomPatch("),
        )
    }

    @Test
    fun `mutation methods do not fire reloadHighlightsView directly anymore`() {
        val source = vmSource()
        val names = listOf(
            "recolorHighlight",
            "updateHighlightNote",
            "deleteHighlight",
            "deleteAnnotation",
        )
        for (name in names) {
            val body = extractFunctionBody(source, name)
                ?: error("$name not found in EpubReaderViewModel.kt")
            assertFalse(
                "$name must NOT fire reloadHighlightsView() directly — the observer path emits a " +
                    "HighlightsDomPatch on the same DB write, so an imperative reload here would " +
                    "unmount the WebView and re-introduce the visible flash the new design fixes.",
                body.contains("reloadHighlightsView()"),
            )
            assertFalse(
                "$name must NOT re-enter openBook() from its Highlights branch — same reason.",
                body.contains("openBook()"),
            )
        }
    }

    private fun extractFunctionBody(source: String, name: String): String? {
        val declRegex = Regex("""(?m)^\s{4}(?:private\s+|internal\s+)?(?:suspend\s+)?fun\s+$name\b""")
        val decl = declRegex.find(source) ?: return null
        var i = source.indexOf('{', startIndex = decl.range.first)
        if (i < 0) return null
        var depth = 0
        val start = i
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun vmSource(): String {
        val rel = "src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt"
        val candidates = listOf(File(rel), File("app/$rel"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("EpubReaderViewModel.kt not found: ${candidates.map { it.absolutePath }}")
        return file.readText()
    }
}
