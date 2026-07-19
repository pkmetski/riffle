package com.riffle.app.feature.reader.highlights

import com.riffle.app.feature.reader.toCssRgba
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.EmbeddedFigure
import com.riffle.core.models.HighlightColor
import javax.inject.Inject
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.PerResourcePositionsService
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource

/**
 * Sentinel `originFontFamily` value written on annotation entities when the WebView had no live
 * `getComputedStyle().fontFamily` to report at annotation-create time (rare selection-teardown
 * race, bookmarks toggled without a prior selection, TYPE_IMAGE caption highlights). The store
 * contract requires a non-blank string, so we can't just store null; instead, this exact literal
 * is the shared "no captured font" marker that [pluralityOriginFont] (in EpubReaderViewModel)
 * and [appendOriginFontFamilyStyle] both treat as "no value" and fall back through. Kept as
 * plain `serif` for backwards compatibility with rows already written before the regression fix.
 * Issue #484 + elided-view-serif-font-regression follow-up.
 */
internal const val FALLBACK_ORIGIN_FONT_FAMILY = "serif"

/**
 * One chapter's worth of highlights to be rendered into the elided reader (ADR 0041).
 *
 * [highlights] must already be sorted by (spineIndex, progression, createdAt) — the factory
 * renders them in the order given, it does not re-sort.
 */
data class ChapterElision(
    val href: String,
    val title: String,
    val highlights: List<AnnotationEntity>,
)

/**
 * Handle returned by [HighlightsPublicationFactory.buildHandle]. Bundles the synthesised
 * [publication] with the mutable byte store backing its `InMemoryContainer` so per-annotation
 * edits (recolour, note change, delete, in-chapter insert) can rewrite one chapter's HTML in
 * place — a subsequent navigation back to that chapter re-reads the FRESH bytes, and Compose is
 * never asked to reload the reader. The [chapterUrls] map lets callers translate a real EPUB
 * `chapterHref` into the synthetic `highlights/chN.xhtml` URL Readium routes on.
 */
class HighlightsPublicationHandle internal constructor(
    val publication: Publication,
    val chapterUrls: Map<String, Url>,
    private val byteStore: MutableMap<Url, ByteArray>,
    /** Href → data-URI map for figure bytes fetched via the [ResourceFetcher] at
     *  [HighlightsPublicationFactory.buildHandle] time. Exposed so the reader's live-patch path
     *  can pass the same map into [HighlightsPublicationFactory.renderChapterHtml] when
     *  regenerating a chapter for a per-annotation edit — otherwise the regen would overwrite
     *  the initial figure-bearing HTML with a byte-less version and the elided view would
     *  regress to "[figure image not captured]" after the first edit. */
    val figureBytesByHref: Map<String, String>,
    /** Concatenated publisher `@font-face` rules (with base64-inlined font bytes) extracted from
     *  the source EPUB's stylesheets — see [PublisherFontFaceExtractor]. Same reasoning as
     *  [figureBytesByHref]: live-patch re-renders must carry this forward or a colour/note edit
     *  would strip the embedded font and the elided view would revert to a generic-serif fallback
     *  (elided-view-serif-font-regression). Empty when no fetcher was supplied, no CSS was
     *  found, or every referenced font failed to resolve. */
    val publisherFontFaceCss: String,
) {
    /** Overwrite the bytes for [chapterHref] with [freshHtml]'s UTF-8 encoding. No-op if the
     *  chapter isn't in the current spine (i.e. this handle predates a structural change). */
    fun setChapterBytes(chapterHref: String, freshHtml: String) {
        val url = chapterUrls[chapterHref] ?: return
        byteStore[url] = freshHtml.toByteArray(Charsets.UTF_8)
    }
}

/**
 * Synthesises an in-memory Readium [Publication] out of a set of [ChapterElision]s so the elided
 * "highlights only" reader (ADR 0041) can be driven by the exact same [EpubNavigatorFragment] /
 * [EpubReaderViewModel] machinery as a full book — Readium never needs to know the content didn't
 * come from a real EPUB container.
 *
 * ### Readium 3.3.0 API notes
 *
 * [Publication] takes a [Manifest] plus an optional [Container]<[Resource]> (see
 * `Publication(manifest, container, servicesBuilder)` in readium-shared 3.3.0). There is no
 * `Publication.Builder` in this version and no first-party in-memory container, so this factory
 * supplies its own tiny [Container] backed by a `Map<Url, ByteArray>`.
 *
 * Building real [Link]/[Url] instances on the JVM unit-test runtime is not possible through the
 * public API: `Url(String)` and `Href` both funnel through `android.net.Uri.parse`, which throws
 * `RuntimeException: ... not mocked` under the stock (non-Robolectric) Android unit-test stub jar
 * that this module uses (see [ReadiumPresenterTest], [PositionOrchestratorTest],
 * [SearchControllerTest] for the same constraint) — and this factory, unlike those call sites, is
 * itself the thing constructing the [Url], so a JVM test cannot simply avoid the code path.
 * [urlFactory] is the seam: production uses the real `Url(String)` (works fine on-device, where
 * `android.net.Uri` is the genuine implementation); JVM tests substitute a factory that builds
 * [Url] instances the same way [PositionOrchestratorTest] and [SearchControllerTest] build their
 * fixtures — `Unsafe.allocateInstance` + `android.net.FakeUri` — so the rest of this class (HTML
 * rendering, spine filtering, TOC titles, container wiring) gets exercised for real.
 */
class HighlightsPublicationFactory @Inject constructor() {

    fun build(
        sourceId: String,
        itemId: String,
        bookTitle: String?,
        chapters: List<ChapterElision>,
        urlFactory: (String) -> Url? = { Url(it) },
        resourceFetcher: ResourceFetcher = ResourceFetcher { null },
        bookBodyFontFamily: String? = null,
    ): Publication =
        buildHandle(sourceId, itemId, bookTitle, chapters, urlFactory, resourceFetcher, bookBodyFontFamily)
            .publication

    /**
     * Same as [build] but returns the handle that lets the caller rewrite a chapter's bytes in
     * place. The elided reader ([EpubReaderViewModel]) uses this so per-annotation edits patch the
     * DOM live via [RendererBridge.applyHighlightDomPatch] AND persist to the container — so a
     * subsequent chapter-back navigation reads the fresh HTML.
     *
     * [bookBodyFontFamily] is the source book's computed body-font (issue #484), used as the
     * per-excerpt fallback when an annotation has no [AnnotationEntity.originFontFamily] of its
     * own (legacy row, or one that hasn't been touched by the lazy-backfill yet). Null falls
     * back all the way to ReadiumCSS's default.
     */
    fun buildHandle(
        sourceId: String,
        itemId: String,
        bookTitle: String?,
        chapters: List<ChapterElision>,
        urlFactory: (String) -> Url? = { Url(it) },
        resourceFetcher: ResourceFetcher = ResourceFetcher { null },
        bookBodyFontFamily: String? = null,
    ): HighlightsPublicationHandle {
        val nonEmptyChapters = chapters.filter { it.highlights.isNotEmpty() }

        val entries = mutableMapOf<Url, ByteArray>()
        val readingOrder = mutableListOf<Link>()
        val chapterUrls = mutableMapOf<String, Url>()

        // Fetch every figure image referenced anywhere in the elided chapters — a standalone
        // TYPE_IMAGE annotation's own imageHref, plus every TYPE_HIGHLIGHT's embeddedFigures hrefs
        // — and stage the bytes into the synthetic container under syntheticPath(href). Nulls
        // (fetch failed, or resourceFetcher is the default/Noop) are skipped silently: the figure
        // renders figcaption-only, per the "missing image bytes fall back to figcaption-only"
        // contract (see ResourceFetcher's KDoc).
        val hrefs = nonEmptyChapters
            .flatMap { it.highlights }
            .flatMap { annotation ->
                buildList {
                    annotation.imageHref?.let { add(it) }
                    annotation.decodedEmbeddedFigures()?.forEach { fig -> fig.href?.let { add(it) } }
                }
            }
            .distinct()
        val dataUriByHref = mutableMapOf<String, String>()
        for (href in hrefs) {
            val bytes = resourceFetcher.fetch(href) ?: continue
            // Data-URI FIRST — this is what [appendFigureFigure] uses as the render-time
            // fallback when the annotation itself has no captured `imageBytes`. Populating the
            // map is independent of whether we can also stage the bytes at the synthetic
            // container path (the synthetic path derivation can trip `urlFactory` on hrefs that
            // contain `:` after path-flattening — Readium's `Url(String)` rejects those — and
            // gating the map on that would silently drop the fallback for exactly those hrefs).
            dataUriByHref[href] = "data:${mimeForHref(href)};base64," +
                java.util.Base64.getEncoder().encodeToString(bytes)
            val url = urlFactory(syntheticPath(href)) ?: continue
            entries[url] = bytes
        }

        // Publisher `@font-face` inlining (elided-view-serif-font-regression follow-up): pull the
        // source EPUB's stylesheets + font files and rewrite each `@font-face` `src: url(...)` to
        // a `data:` URI so the synthesised elided document can actually render the captured
        // `originFontFamily` face in Original / Publisher mode. Without this, the WebView sees
        // e.g. `font-family: Nimbusromno9l;`, can't resolve it (the synthesised container
        // doesn't serve the source book's font files), and falls back to a generic serif that
        // visibly diverges from what the source reader shows.
        //
        // Extraction is opt-in via [resourceFetcher] exposing [ZipEpubResourceFetcher.listEntries]
        // — for JVM tests and the Noop fetcher this yields an empty list and no `@font-face`
        // block is emitted (matches the pre-fix rendering exactly for tests that don't need it).
        val publisherFontFaceCss = when (val fetcher = resourceFetcher) {
            is ZipEpubResourceFetcher -> {
                val cssFiles = fetcher.listEntries(listOf(".css"))
                val fontResolver: (String) -> ByteArray? = { path -> fetcher.fetch(path) }
                PublisherFontFaceExtractor.extract(cssFiles, fontResolver)
            }
            else -> ""
        }

        nonEmptyChapters.forEachIndexed { index, chapter ->
            val href = "highlights/ch$index.xhtml"
            val url = requireNotNull(urlFactory(href)) { "Failed to build synthetic Url for $href" }
            entries[url] = renderChapterHtml(
                chapter, bookBodyFontFamily, dataUriByHref, publisherFontFaceCss,
            ).toByteArray(Charsets.UTF_8)
            chapterUrls[chapter.href] = url
            readingOrder += Link(
                href = url,
                mediaType = MediaType.XHTML,
                title = chapter.title,
            )
        }

        val manifest = Manifest(
            // conformsTo = EPUB is load-bearing (ADR 0041 follow-up): WebViewServer only invokes
            // Readium's HtmlInjector — which registers the WebView tap listener that drives
            // immersive-mode toggle AND injects ReadiumCSS-before/default/after — when the
            // publication conforms to the EPUB profile. Without it, our synthesised chapters
            // load into a raw WebView with no tap dispatch and no CSS.
            metadata = Metadata(
                conformsTo = setOf(Publication.Profile.EPUB),
                localizedTitle = LocalizedString(bookTitle ?: "Annotations"),
            ),
            readingOrder = readingOrder,
            tableOfContents = readingOrder,
        )

        // Register PerResourcePositionsService so [Publication.positionsByReadingOrder] returns
        // one Locator per elided chapter (elided-view chapter-map follow-up). Without this,
        // Readium's default services builder leaves no PositionsService bound — the extension
        // returns emptyList(), [EpubReaderViewModel.spinePositionCounts] emits (spineHrefs, [])
        // and [EpubReaderViewModel.railSegments] short-circuits to emptyList so the chapter map
        // is silently blank in the elided reader. One position per resource is exactly the rail
        // needs: the elided TOC is flat, so [buildRailSegments] produces one segment per elided
        // chapter with equal weight.
        val publication = Publication(
            manifest = manifest,
            container = InMemoryContainer(entries),
            servicesBuilder = Publication.ServicesBuilder(
                positions = { ctx ->
                    PerResourcePositionsService(
                        readingOrder = ctx.manifest.readingOrder,
                        fallbackMediaType = MediaType.XHTML,
                    )
                },
            ),
        )
        return HighlightsPublicationHandle(
            publication, chapterUrls, entries, dataUriByHref, publisherFontFaceCss,
        )
    }

    /**
     * Render one chapter's synthesised HTML — the full XHTML document that Readium serves as the
     * chapter resource. Exposed `internal` so [EpubReaderViewModel] can regenerate a single
     * chapter's bytes after a per-annotation edit and write them back through
     * [HighlightsPublicationHandle.setChapterBytes] without rebuilding the whole Publication.
     */
    internal fun renderChapterHtml(
        chapter: ChapterElision,
        bookBodyFontFamily: String? = null,
        dataUriByHref: Map<String, String> = emptyMap(),
        publisherFontFaceCss: String = "",
    ): String {
        val body = buildString {
            for (annotation in chapter.highlights) {
                when (annotation.type) {
                    AnnotationEntity.TYPE_IMAGE -> appendImageAnnotation(this, annotation, dataUriByHref)
                    else -> {
                        // Interleave text and figures at their captured [EmbeddedFigure.charOffset]
                        // (fix 2026-07-09) so a graph sitting between two paragraphs of the
                        // highlighted range renders IN PLACE — not dumped at the end. When any
                        // enclosed figure was captured without an offset (legacy row, or a
                        // JS-stash-only figure that never went through the Kotlin walker), the
                        // whole highlight falls back to "text first, then figures" — the v1
                        // behaviour matches what shipped before offsets existed. See
                        // appendInterleavedHighlight's KDoc.
                        appendInterleavedHighlight(this, annotation, bookBodyFontFamily, dataUriByHref)
                    }
                }
            }
        }
        val title = chapter.title.xmlEscape()
        // Apply the book's body font to `<body>`, every heading, and `<aside>` so the whole
        // synthesised document inherits the origin's face instead of ReadiumCSS's serif default.
        // `!important` is load-bearing: ReadiumCSS-default.css sets its own `font-family` on
        // headings (via `--RS__*Font*` variables) with equal-or-higher specificity, so a plain
        // inline `<body>` style loses on `<h1>`. Per-excerpt `<p>` inline styles from
        // [appendOriginFontFamilyStyle] still override this — inline `!important` beats
        // stylesheet `!important` at equal specificity. Issue #484.
        // Skip the sentinel — see [FALLBACK_ORIGIN_FONT_FAMILY]. Emitting `font-family: serif`
        // on `<body>, h1, ...` would force the elided view's chapter titles and excerpts into
        // browser-default serif for books whose annotation set is dominated by sentinel-stamped
        // rows.
        //
        // No `!important` (elided-view-serif-font-regression follow-up): with `!important` the
        // captured origin face was pinned regardless of the reader's Font pref, so switching
        // Original → Serif / Sans / Merriweather only affected `<h1>` (a competing ReadiumCSS
        // heading rule tied on specificity + importance and won the cascade on some webviews).
        // Without `!important` our rule serves as the "Original" default, and ReadiumCSS's
        // font-pref rule (which uses `!important` in Serif/Sans/publisher-typography modes)
        // now cleanly wins over ours for the whole document — body AND heading — so the toggle
        // finally does what the user expects.
        val realBodyFont = bookBodyFontFamily?.takeIf { it != FALLBACK_ORIGIN_FONT_FAMILY }
        val safeBodyFont = sanitizeCssFontFamily(realBodyFont)
        val bodyFontStyleBlock = if (safeBodyFont != null) {
            val escaped = safeBodyFont.xmlEscape()
            "body, h1, h2, h3, h4, h5, h6, aside, figcaption, .riffle-fig { font-family: $escaped; }"
        } else ""
        // Emit the publisher `@font-face` rules BEFORE our body-font declaration so the WebView
        // sees the font source (with inlined base64 bytes) before the first `font-family: X;`
        // that references its name. Empty when [publisherFontFaceCss] is blank — no `<style>`
        // noise for tests or books without embedded fonts.
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title>$READIUM_DEFAULT_CSS_LINK<style>$publisherFontFaceCss
            |$ACCENT_BAR_TAP_CSS
            |$FIGURE_CENTERING_CSS
            |$bodyFontStyleBlock</style></head>
            |<body>
            |  <h1>$title</h1>
            |${body.trimEnd('\n')}
            |</body></html>
        """.trimMargin()
    }
}

// Class name on the transparent absolute-positioned span that owns tap dispatch for a highlight.
// Injected inside each synthesised `<p>` next to the visible text; its CSS (see
// [ACCENT_BAR_TAP_CSS]) sizes it to overlap the paragraph's border-left + padding-left gutter so a
// tap on the visible colored bar lands here and NOT on the text. The `<span>` intentionally has no
// text content; its `onclick` navigates to a [buildAnnotationTapUrl] which is intercepted by both
// reader modes' URL handlers. Kept `internal const` so JVM regression tests can pin the class name
// against the rendered HTML.
internal const val ACCENT_BAR_TAP_CLASS = "riffle-hl-tap"

// Sizes the tap element to cover the paragraph's 4px border-left + 12px padding-left plus a small
// extra margin outward — inline with what the reader actually paints. `pointer-events: auto`
// defends against a ReadiumCSS reset stripping default cursor semantics. `background: transparent`
// stays invisible on every theme.
internal const val ACCENT_BAR_TAP_CSS =
    ".$ACCENT_BAR_TAP_CLASS{position:absolute;left:-4px;top:0;bottom:0;width:20px;" +
        "background:transparent;cursor:pointer;pointer-events:auto;}"

// Centers annotated figures (`<figure class="riffle-fig">`) horizontally in the elided reader.
// `<figure>`'s browser default is `margin: 1em 40px`, which visually reads as left-aligned when
// the container is wider than the image. Setting `margin: 1em auto` centers the figure block, and
// `text-align: center` centers the inline `<img>`/`<svg>` inside it plus any `<figcaption>`. The
// `display: block` on the inner image defends against a ReadiumCSS or publisher rule that would
// otherwise force the img to inline flow with residual whitespace. Matches how graphs render in
// the source book (which centers them via publisher CSS we don't preserve in the synthesised view).
internal const val FIGURE_CENTERING_CSS =
    ".riffle-fig{margin:1em auto !important;text-align:center;position:relative;}" +
        ".riffle-fig>img,.riffle-fig>svg{display:block;margin:0 auto;max-width:100%;height:auto;}"

/**
 * Renders a TYPE_HIGHLIGHT with its embedded figures interleaved at each figure's
 * [EmbeddedFigure.charOffset] — text-before, `<figure>`, text-after (fix 2026-07-09).
 *
 * Falls back to "text first, then figures" (the v1 rendering pinned by
 * `TYPE_HIGHLIGHT with embeddedFigures renders text then caption placeholders in order`) when the
 * highlight has NO embedded figures OR when ANY figure lacks a `charOffset` (legacy rows written
 * before offsets existed, plus JS-stash-only figures that didn't go through the Kotlin walker).
 * The fallback keeps the shipped elided-reader semantics intact for annotations that predate this
 * change.
 */
private fun appendInterleavedHighlight(
    sb: StringBuilder,
    highlight: com.riffle.core.database.AnnotationEntity,
    bookBodyFontFamily: String?,
    dataUriByHref: Map<String, String> = emptyMap(),
) {
    val figures = highlight.decodedEmbeddedFigures()?.sortedBy { it.order }.orEmpty()
    val normalizedSnippetOuter = com.riffle.app.feature.reader.normalizeCaptionText(highlight.textSnippet)
    if (figures.isEmpty() || figures.any { it.charOffset == null }) {
        // Caption-highlight shape (2026-07-14): the annotation is a HIGHLIGHT that covers only
        // the figure's caption text, with the figure as its sole embeddedFigure. Natural
        // reading order is FIGURE first, then caption text below — matches how the source book
        // typesets a numbered figure. When the annotation's `charOffset` survives sync
        // (interleaved branch below), that ordering happens automatically at charOffset=0; when
        // it doesn't (older peer / sync round-trip dropped the field), we detect the caption-
        // highlight shape here and reorder manually. Text-selection-across-figure highlights
        // (>1 figure, or figure caption differs from the highlight's textSnippet) keep the v1
        // "text first, figures after" fallback so a pre-caption-highlight annotation still
        // renders the same.
        // Require an explicit caption-shape signal so a legacy text-highlight enclosing a
        // decorative uncaptioned figure (single embeddedFigure with caption="") doesn't get
        // its image hoisted above the paragraph text. Two accepted signals:
        //   (a) figure.caption normalizes to the highlight's textSnippet — the shape written
        //       before caption="" became the default (kept for back-compat with existing rows).
        //   (b) figure.caption is blank AND textSnippet starts with the canonical caption
        //       prefix (Figure/Fig./Table/Chart + digit) — the shape written by the new
        //       onFigureLongPress / CaptionHighlightUpgrader code paths.
        val singleFigure = figures.singleOrNull()
        val isCaptionHighlight = singleFigure != null && (
            (singleFigure.caption.isNotBlank() &&
                com.riffle.app.feature.reader.normalizeCaptionText(singleFigure.caption) == normalizedSnippetOuter) ||
                (singleFigure.caption.isBlank() && CAPTION_HIGHLIGHT_PREFIX_REGEX.containsMatchIn(normalizedSnippetOuter))
            )
        if (isCaptionHighlight) {
            appendFigureBlock(sb, singleFigure!!.copy(caption = ""), highlight.id, highlight.color, dataUriByHref)
            appendTextHighlight(sb, highlight, bookBodyFontFamily)
            return
        }
        appendTextHighlight(sb, highlight, bookBodyFontFamily)
        figures.forEach { fig ->
            val effective = if (
                fig.caption.isNotBlank() &&
                com.riffle.app.feature.reader.normalizeCaptionText(fig.caption) == normalizedSnippetOuter
            ) fig.copy(caption = "") else fig
            appendFigureBlock(sb, effective, highlight.id, highlight.color, dataUriByHref)
        }
        return
    }
    val chunks = com.riffle.app.feature.reader.splitSnippetForFiguresAt(
        snippet = highlight.textSnippet,
        offsets = figures.map { it.charOffset },
    )
    val normalizedSnippet = com.riffle.app.feature.reader.normalizeCaptionText(highlight.textSnippet)
    // Emit alternating: chunk[0], figure[0], chunk[1], figure[1], ..., chunk[last].
    chunks.forEachIndexed { index, chunk ->
        if (chunk.isNotEmpty()) {
            appendHighlightTextChunk(sb, highlight, chunk, bookBodyFontFamily)
        }
        figures.getOrNull(index)?.let { fig ->
            // Caption-highlight dedup: when the figure's own caption is identical to the highlight's
            // textSnippet (the caption-annotation shape from 2026-07-14), the outer text-chunk
            // above already emits the caption text — so pass the figure through with a blanked
            // caption to avoid rendering it twice. For a text-selection-encloses-figure highlight
            // the figure's caption is a strict subset of textSnippet, not equal to it, so this
            // guard is caption-highlight-specific.
            val effectiveFigure = if (
                fig.caption.isNotBlank() &&
                com.riffle.app.feature.reader.normalizeCaptionText(fig.caption) == normalizedSnippet
            ) fig.copy(caption = "") else fig
            appendFigureBlock(sb, effectiveFigure, highlight.id, highlight.color, dataUriByHref)
        }
    }
    val note = highlight.note
    if (note != null) {
        val accent = highlightBackgroundCss(highlight.color)
        val idEscaped = highlight.id.xmlEscape()
        sb.append("  <aside class=\"riffle-note\" data-ann-id=\"")
        sb.append(idEscaped)
        sb.append("\" style=\"border-left: 2px solid ")
        sb.append(accent)
        sb.append(" !important; padding-left: 12px; font-style: italic; opacity: 0.75;\">")
        sb.append(note.xmlEscape())
        sb.append("</aside>\n")
    }
}

/**
 * Emits ONE `<p>` chunk of a split highlight — same accent bar + tap span as [appendTextHighlight],
 * but only wrapping [chunk] (a portion of the highlight's snippet, not the whole thing). Callers
 * emit multiple chunks around interleaved figures; the accent-bar-tap span carries the SAME
 * annotation id on every chunk so a tap anywhere along the highlight opens the same popup.
 */
private fun appendHighlightTextChunk(
    sb: StringBuilder,
    highlight: com.riffle.core.database.AnnotationEntity,
    chunk: String,
    bookBodyFontFamily: String?,
) {
    val accent = highlightBackgroundCss(highlight.color)
    val idEscaped = highlight.id.xmlEscape()
    val tapUrl = buildAnnotationTapUrl(highlight.id).xmlEscape()
    sb.append("  <p style=\"")
    sb.append(PARAGRAPH_GAP_STYLE)
    sb.append("; position: relative; border-left: 4px solid ")
    sb.append(accent)
    sb.append(" !important; padding-left: 12px;")
    appendOriginFontFamilyStyle(sb, highlight.originFontFamily, bookBodyFontFamily)
    sb.append("\"><span class=\"")
    sb.append(ACCENT_BAR_TAP_CLASS)
    sb.append("\" data-ann-id=\"")
    sb.append(idEscaped)
    sb.append("\" onclick=\"var e=event,x=e.clientX,y=e.clientY;location.href='")
    sb.append(tapUrl)
    sb.append("?l='+x+'&amp;t='+y+'&amp;r='+(x+1)+'&amp;b='+(y+1);return false;\"></span><span class=\"riffle-hl\" data-ann-id=\"")
    sb.append(idEscaped)
    sb.append("\">")
    sb.append(chunk.xmlEscape())
    sb.append("</span></p>\n")
}

/**
 * Renders a TYPE_HIGHLIGHT annotation's own `<p>`/`<aside>` block — the pre-Task-9 rendering,
 * extracted unchanged out of [HighlightsPublicationFactory.renderChapterHtml] so figure-block
 * emission (TYPE_IMAGE, embedded figures) can be dispatched independently. Now also serves as the
 * `charOffset == null` fallback path from [appendInterleavedHighlight].
 */
private fun appendTextHighlight(sb: StringBuilder, highlight: AnnotationEntity, bookBodyFontFamily: String?) {
    // The highlight is presented as a left accent bar in the palette colour, matching
    // Riffle's [Book Search] results card style — the text itself renders in the
    // theme's normal body colour so dense highlights don't fatigue the eye. `!important`
    // so ReadiumCSS's theme rules (e.g. Dark's `:not(a){border-color: currentColor}`)
    // can't strip the colour. Tap dispatch runs off the injected [ACCENT_BAR_TAP_CLASS]
    // span below, which navigates to a [buildAnnotationTapUrl]; both continuous
    // (ChapterWebView) and paginated/vertical (EpubReaderScreen) intercept that URL and
    // open the highlight-actions popup. Tapping the text itself does nothing — the
    // reason this HTML is authored here and not decorated on top of it via Readium.
    val accent = highlightBackgroundCss(highlight.color)
    val idEscaped = highlight.id.xmlEscape()
    val tapUrl = buildAnnotationTapUrl(highlight.id).xmlEscape()
    sb.append("  <p style=\"")
    sb.append(PARAGRAPH_GAP_STYLE)
    sb.append("; position: relative; border-left: 4px solid ")
    sb.append(accent)
    sb.append(" !important; padding-left: 12px;")
    appendOriginFontFamilyStyle(sb, highlight.originFontFamily, bookBodyFontFamily)
    sb.append("\"><span class=\"")
    sb.append(ACCENT_BAR_TAP_CLASS)
    sb.append("\" data-ann-id=\"")
    sb.append(idEscaped)
    sb.append("\" onclick=\"var e=event,x=e.clientX,y=e.clientY;location.href='")
    sb.append(tapUrl)
    sb.append("?l='+x+'&amp;t='+y+'&amp;r='+(x+1)+'&amp;b='+(y+1);return false;\"></span><span class=\"riffle-hl\" data-ann-id=\"")
    sb.append(idEscaped)
    sb.append("\">")
    sb.append(highlight.textSnippet.xmlEscape())
    sb.append("</span></p>\n")
    val note = highlight.note
    if (note != null) {
        sb.append("  <aside class=\"riffle-note\" data-ann-id=\"")
        sb.append(idEscaped)
        sb.append("\" style=\"border-left: 2px solid ")
        sb.append(accent)
        sb.append(" !important; padding-left: 12px; font-style: italic; opacity: 0.75;\">")
        sb.append(note.xmlEscape())
        sb.append("</aside>\n")
    }
}

/**
 * Appends `; font-family: <sanitized>;` to a `<p style="…">` builder when either the annotation
 * has a captured [AnnotationEntity.originFontFamily] (preferred) or the caller supplied a
 * publication-wide [bookBodyFontFamily] fallback. No-op when both are null/blank — the excerpt
 * then inherits ReadiumCSS's default face, matching the pre-issue-484 behaviour.
 *
 * The value is defensively sanitized (see [sanitizeCssFontFamily]) before being XML-escaped
 * into the `style` attribute — `getComputedStyle().fontFamily` is normally well-formed, but a
 * DB row (including a lazy backfill) is not a trust boundary and CSS injection via
 * `};color:red;` would otherwise be trivially possible.
 */
private fun appendOriginFontFamilyStyle(
    sb: StringBuilder,
    originFontFamily: String?,
    bookBodyFontFamily: String?,
) {
    // Filter the shared [FALLBACK_ORIGIN_FONT_FAMILY] sentinel so a sentinel-stamped annotation
    // doesn't force a bare `font-family: serif !important` inline on its `<p>` — let the fallback
    // chain (bookBodyFontFamily, then ReadiumCSS default) take over.
    val ownFont = originFontFamily
        ?.takeIf { it.isNotBlank() && it != FALLBACK_ORIGIN_FONT_FAMILY }
    val fallback = bookBodyFontFamily
        ?.takeIf { it.isNotBlank() && it != FALLBACK_ORIGIN_FONT_FAMILY }
    val raw = ownFont ?: fallback
    val safe = sanitizeCssFontFamily(raw) ?: return
    // No `!important` (elided-view-serif-font-regression follow-up): with it, the captured
    // origin font pinned per-excerpt regardless of the reader's Font pref — switching to
    // Serif/Sans/Merriweather left the body stuck. Non-important lets ReadiumCSS's
    // font-pref rule (which uses `!important`) win when the user picks a specific face,
    // while our inline still wins over ReadiumCSS's Publisher-mode default (which uses no
    // `!important`), so the elided excerpt still renders in the captured origin face by
    // default. Same reasoning as the body/heading style block in [renderChapterHtml].
    sb.append(" font-family: ")
    sb.append(safe.xmlEscape())
    sb.append(";")
}

/**
 * Returns [value] unchanged if it only contains characters legal in a CSS `font-family` list
 * (letters, digits, spaces, single/double quotes, dashes, underscores, commas, dots) — else
 * null. This is a paranoia check: `getComputedStyle().fontFamily` from a modern browser is
 * always a serialized CSS identifier list, but the DB is not a trust boundary and a malformed
 * value must not be able to escape the `font-family` declaration.
 */
internal fun sanitizeCssFontFamily(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val trimmed = value.trim()
    // Reject any character outside a safe allowlist.
    if (trimmed.any { c ->
            !(c.isLetterOrDigit() || c == ' ' || c == '-' || c == '_' || c == ',' || c == '.' ||
                c == '\'' || c == '"')
        }) return null
    return trimmed
}

/**
 * Renders a TYPE_IMAGE annotation as a full-size `<figure>` (Task 9, ADR 0041) — full reader
 * content width, no explicit max-width shrinking, matching the "graphs and diagrams... show up in
 * the annotations view" spec goal. Inline SVG source ([AnnotationEntity.imageSvg]) is embedded
 * verbatim as a first-class XHTML element rather than wrapped in an `<img>`; raster figures
 * ([AnnotationEntity.imageHref]) point at the synthetic path the same href was staged under by
 * [HighlightsPublicationFactory.build]. [AnnotationEntity.textSnippet] is used as the caption and
 * skipped entirely when blank — a TYPE_IMAGE annotation with no caption gets no `<figcaption>`.
 */
private fun appendImageAnnotation(
    sb: StringBuilder,
    annotation: AnnotationEntity,
    dataUriByHref: Map<String, String> = emptyMap(),
) {
    // Fallback (2026-07-14): when the annotation itself has no captured `imageBytes` (JS canvas
    // rasterization failed at long-press — often a cross-origin taint on `readium_package://`
    // URLs), fall through to the bytes the factory fetched from the source publication and
    // encoded as a data URI. Keeps the elided view showing the actual image rather than the
    // "[figure image not captured]" placeholder.
    val effectiveBytes = annotation.imageBytes
        ?: annotation.imageHref?.let { dataUriByHref[it] }
    appendFigureFigure(
        sb = sb,
        annotationId = annotation.id,
        colorToken = annotation.color,
        svg = annotation.imageSvg,
        bytes = effectiveBytes,
        caption = annotation.textSnippet,
    )
}

/**
 * Renders one [EmbeddedFigure] enclosed by a TYPE_HIGHLIGHT annotation's range, appended after the
 * highlight's own text block (v1 interleaving approximation — see
 * [HighlightsPublicationFactory.renderChapterHtml]'s inline KDoc). Mirrors
 * [appendImageAnnotation]'s SVG-vs-raster branching and caption handling.
 */
private fun appendFigureBlock(
    sb: StringBuilder,
    figure: EmbeddedFigure,
    ownerAnnotationId: String,
    ownerColorToken: String,
    dataUriByHref: Map<String, String> = emptyMap(),
) {
    // See [appendImageAnnotation]'s KDoc for the fallback rationale — same pattern applied to
    // each embedded figure inside a TYPE_HIGHLIGHT.
    val effectiveBytes = figure.imageBytes ?: figure.href?.let { dataUriByHref[it] }
    appendFigureFigure(
        sb = sb,
        annotationId = ownerAnnotationId,
        colorToken = ownerColorToken,
        svg = figure.svg,
        bytes = effectiveBytes,
        caption = figure.caption,
    )
}

/**
 * Shared `<figure class="riffle-fig">` emitter for [appendImageAnnotation] (standalone TYPE_IMAGE)
 * and [appendFigureBlock] (embedded figure inside a TYPE_HIGHLIGHT). Both need:
 *  - The same coloured left accent bar the text `<p>` gets (fix: "annotated graph is missing the
 *    vertical colored line") — inline `border-left` on the `<figure>`, `position:relative` so the
 *    tap span can absolute-position over it.
 *  - The [ACCENT_BAR_TAP_CLASS] span for tap-to-open dispatch, using [buildAnnotationTapUrl] with
 *    the annotation id. For an embedded figure inside a highlight, the id points at the OWNING
 *    highlight so a tap opens the highlight's editor (not a phantom separate annotation).
 *  - Centering behaviour from [FIGURE_CENTERING_CSS] on `.riffle-fig`; nothing more to do here.
 *
 * When neither [svg] nor [bytes] is available, emits the placeholder line — the caller's caption
 * still identifies which figure this was.
 */
private fun appendFigureFigure(
    sb: StringBuilder,
    annotationId: String,
    colorToken: String,
    svg: String?,
    bytes: String?,
    caption: String,
) {
    val accent = highlightBackgroundCss(colorToken)
    val idEscaped = annotationId.xmlEscape()
    val tapUrl = buildAnnotationTapUrl(annotationId).xmlEscape()
    // `data-ann-id` on the <figure> itself so the live DOM-patch pipeline (buildRecolorJs /
    // buildRemoveJs in HighlightsDomPatch) can find and recolour or delete the figure block
    // when the owning highlight is edited — without it, `document.querySelectorAll('[data-ann-id]')`
    // only matches the inner tap span, and `.closest('p')` returns null so recolour no-ops.
    sb.append("  <figure class=\"riffle-fig\" data-ann-id=\"").append(idEscaped)
    sb.append("\" style=\"border-left: 4px solid ")
    sb.append(accent)
    sb.append(" !important; padding-left: 12px;\">\n")
    // Tap-dispatch strip over the accent bar, matching the text-paragraph tap seam. `pointer-events`
    // are `auto` per [ACCENT_BAR_TAP_CSS] so a tap on the coloured bar lands here rather than on
    // the image (which is a plain content tap and does nothing in the elided view).
    sb.append("    <span class=\"").append(ACCENT_BAR_TAP_CLASS)
        .append("\" data-ann-id=\"").append(idEscaped)
        .append("\" onclick=\"var e=event,x=e.clientX,y=e.clientY;location.href='")
        .append(tapUrl)
        .append("?l='+x+'&amp;t='+y+'&amp;r='+(x+1)+'&amp;b='+(y+1);return false;\"></span>\n")
    when {
        bytes != null -> sb.append("    <img src=\"").append(bytes.xmlAttrEscape())
            .append("\" data-ann-id=\"").append(idEscaped).append("\"/>")
        svg != null -> sb.append("    ").append(sanitizeSvgForElidedView(svg))
        // Legacy figures with only imageHref: emitting a synthetic-path <img> crashed
        // WebViewServer with an NPE on Url.relativize. Fall back to a placeholder line — the
        // caption below still tells the reader which figure this was.
        else -> sb.append("    <p class=\"riffle-fig-placeholder\">[figure image not captured]</p>")
    }
    if (caption.isNotBlank()) {
        sb.append("\n    <figcaption>").append(caption.xmlEscape()).append("</figcaption>")
    }
    sb.append("\n  </figure>\n")
}

/**
 * Where a raster figure's fetched bytes are staged inside the factory's in-memory [Container]
 * (Task 9). Sanitizes the href into a flat path segment — `/` can't appear in a synthetic
 * single-segment resource path — so `"images/g.png"` becomes `"synthetic/figures/images_g.png"`.
 * Shared by both [HighlightsPublicationFactory.build] (writing the bytes) and
 * [appendImageAnnotation]/[appendFigureBlock] (referencing them from `<img src="...">`), so the two
 * can never drift out of sync.
 */
private fun syntheticPath(href: String): String = "synthetic/figures/" + href.replace('/', '_')

/**
 * Mirror of [com.riffle.app.feature.reader.FigureCaptionWalker]'s JS `CAPTION_PREFIX_RX` — kept
 * as an isolated pattern here so [appendInterleavedHighlight]'s caption-highlight discriminator
 * doesn't misfire on text-highlights that happen to enclose a decorative uncaptioned figure.
 */
private val CAPTION_HIGHLIGHT_PREFIX_REGEX =
    Regex("^\\s*(Figure|Fig\\.?|Table|Chart)\\s+\\d", RegexOption.IGNORE_CASE)

/**
 * Best-effort MIME type from a figure href, used when inlining fetched bytes as a `data:` URI
 * inside the elided chapter HTML. Defaults to `image/jpeg` — the WebView renders that fallback
 * fine even when the actual bytes are a PNG/GIF, so a wrong guess degrades visually rather than
 * failing hard.
 */
private fun mimeForHref(href: String): String {
    val trimmed = href.substringBefore('?').substringBefore('#').lowercase()
    return when {
        trimmed.endsWith(".png") -> "image/png"
        trimmed.endsWith(".gif") -> "image/gif"
        trimmed.endsWith(".webp") -> "image/webp"
        trimmed.endsWith(".svg") -> "image/svg+xml"
        else -> "image/jpeg"
    }
}

/**
 * Strips external references (`<image href|xlink:href="…">` and `<use href|xlink:href="…">`) from
 * an inline SVG before embedding it in the elided Highlights view. Without this, Chromium
 * resolves those hrefs against the elided chapter's URL (`https://readium_package/highlights/chN.xhtml`)
 * and requests them from `WebViewServer` — whose `packageBaseHref.relativize(url)` throws
 * `IllegalStateException: Required value was null` when the resulting relative URL can't be
 * re-parsed, tearing down the reader process (same NPE class as the pre-725ee000e synthetic-`<img>`
 * crash). Data URIs are left intact — Chromium doesn't route them through WebViewServer.
 *
 * The sanitizer is a regex — jsoup on the elided-render hot path is overkill, and SVG's namespace
 * quirks make a proper XML parse fragile. If either substitution ever needs to grow into a real
 * parser, promote at that time.
 */
internal fun sanitizeSvgForElidedView(svg: String): String {
    // Drop <image ...> and <use ...> elements that point at a non-data URL. Preserve the whole
    // element tree otherwise so the SVG still renders (paths, rects, etc.).
    val externalImageOrUse = Regex(
        """<(image|use)\b[^>]*?(?:xlink:)?href\s*=\s*["'](?!data:)[^"']*["'][^>]*/?>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    return externalImageOrUse.replace(svg, "")
}

private val embeddedFiguresJson = Json { ignoreUnknownKeys = true }
private val embeddedFiguresSerializer = ListSerializer(EmbeddedFigure.serializer())

/**
 * Parses [AnnotationEntity.embeddedFigures]'s JSON column into domain [EmbeddedFigure]s. Null/blank
 * columns (TYPE_IMAGE, TYPE_BOOKMARK, or a TYPE_HIGHLIGHT with no enclosed figures) map to null.
 * Mirrors `AnnotationStoreImpl`'s private `toEmbeddedFigures()` codec (same JSON shape, same
 * serializer) — duplicated here rather than shared because that helper is `private` to
 * `core:data` and this factory lives in `:app` with no dependency edge to reach it.
 */
private fun AnnotationEntity.decodedEmbeddedFigures(): List<EmbeddedFigure>? =
    embeddedFigures?.takeIf { it.isNotBlank() }?.let {
        embeddedFiguresJson.decodeFromString(embeddedFiguresSerializer, it)
    }

/**
 * Guaranteed-visible background paint for a synthesised `<p class="riffle-hl">` (Fix A,
 * ADR 0041 follow-up). Long or punctuated snippets can fail Readium's text-matched
 * [org.readium.r2.shared.publication.Locator.Text] decoration (see
 * [HighlightsPublicationFactory]'s KDoc for the rendering path this backs up), leaving the
 * paragraph unpainted. Inline CSS is format-independent and never depends on Readium's decoration
 * tap-matching, so it paints every highlight regardless of snippet shape — the decoration overlay
 * (kept for tap-to-edit dispatch) stacks on top of the *same* colour, so the two cooperate visually
 * instead of conflicting. Reuses [HighlightColor] — the single-source palette shared by the
 * highlight decoration renderers (see [com.riffle.app.feature.reader.ContinuousHighlightRenderer],
 * [com.riffle.app.feature.reader.ReadiumHighlightRenderer]) — rather than a hardcoded hex table, so
 * a palette change here can't drift from what the rest of the reader paints.
 */
private fun highlightBackgroundCss(colorToken: String): String =
    HighlightColor.fromToken(colorToken).argb.toCssRgba()

/**
 * Explicit `<link>` to Readium's own `ReadiumCSS-default.css`, worked around this way to fix a
 * formatting bug in the elided reader (ADR 0041): every synthesised chapter loses font-size/
 * heading/spacing styling in FullBook mode's terms because Readium's `ReadiumCss.injectHtml`
 * (readium-navigator's `HtmlInjector`/`ReadiumCss.kt`, `injectStyles()`) only auto-appends
 * `ReadiumCSS-default.css` when the resource has **no** publisher-supplied styling — and its
 * `hasStyles()` heuristic treats *any* ` style="..."` attribute, `<link>`, or `<style>` tag as
 * "publisher styles present". Our synthesised chapter's `<span style="background-color: ...">`
 * (the Fix A guaranteed-visible highlight paint — see [highlightBackgroundCss]'s KDoc, pinned by
 * `HighlightsPublicationFactoryTest.highlightParagraphCarriesInlineBackgroundColorFromItsColorToken`,
 * which cannot be removed) trips that heuristic, so Readium silently skips injecting
 * `ReadiumCSS-default.css` — the stylesheet defining `--RS__baseFontSize`, the `h1`-`h6` type
 * scale, and paragraph/flow spacing. Without it, `--USER__fontSize` (font-size preference) still
 * applies to `:root`, but there's no default type-scale beneath it, so the reader renders at
 * roughly the browser's unstyled UA size instead of the size FullBook mode shows for the same
 * preference.
 *
 * Rather than removing the highlight's inline paint (which would regress Fix A), we manually
 * supply the same stylesheet link Readium would have added on our behalf. `readium_assets` is a
 * fixed hostname Readium's `WebViewServer` uses to serve its bundled CSS/JS assets
 * (`WebViewServer.ASSETS_HOSTNAME`/`assetsBaseHref` in readium-navigator 3.3.0) — it isn't a
 * per-instance or per-publication value, so hardcoding this URL doesn't reach into Readium
 * internals or depend on our Container/manifest; it only depends on the WebView being served by
 * Readium's own navigator, which is true in every reader mode (paginated/vertical/continuous all
 * route through the same navigator server).
 */
private const val READIUM_DEFAULT_CSS_LINK =
    "<link rel=\"stylesheet\" type=\"text/css\" " +
        "href=\"https://readium_assets/readium/readium-css/ReadiumCSS-default.css\"/>"

/**
 * Explicit vertical margin on every synthesised highlight `<p>`, fixing "Immersive Mode doesn't
 * toggle in the elided reader" (ADR 0041 follow-up). ReadiumCSS-default.css's own paragraph rule
 * (`p{margin-top:var(--RS__paraSpacing);margin-bottom:var(--RS__paraSpacing)}`, with
 * `--RS__paraSpacing:0` by default) sets **zero** paragraph margin — normal FullBook-mode EPUBs
 * still read fine because only isolated phrases within a much longer paragraph are highlighted, so
 * most of the reading surface is plain (non-decorated) text with ample tap targets around it.
 *
 * The elided reader has no such plain text: every `<p>` IS a highlight from margin to margin, and
 * Readium's decoration hit-test (`readium-reflowable.js`'s tap handler) walks each active
 * decoration's `getClientRects()` line boxes — which are sized to the full rendered text line,
 * consuming essentially all of the line's line-height. With zero paragraph margin, two adjacent
 * highlight `<p>`s sit flush against each other, leaving no non-decorated gap for a tap to land in
 * and reach `Android.onTap` (wired to [com.riffle.app.feature.reader.ImmersiveModeState.toggle] —
 * see `EpubReaderScreen`'s `tapListener`/`InputListener.onTap`). Tapping a highlight itself
 * correctly opens the highlight-actions sheet (by design — see ADR 0041 and the "Annotations View"
 * glossary entry in CONTEXT.md) — the bug is that immersive becomes *unreachable* because there's
 * no other place to tap.
 *
 * `1em` top/bottom is comfortably larger than typical inter-line leading, so it reads as a real
 * paragraph break (consistent with how FullBook mode's own un-highlighted inter-paragraph spacing
 * looks) while guaranteeing a tappable, non-decorated strip between every highlight.
 */
private const val PARAGRAPH_GAP_STYLE = "margin: 1em 0;"

private fun String.xmlEscape(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

/** Escape a value for an XML attribute — same as [xmlEscape] minus the `<`/`>` (attribute values
 *  don't parse element markup) so a base64 data URI containing `+`/`/` survives verbatim. */
private fun String.xmlAttrEscape(): String =
    replace("&", "&amp;")
        .replace("\"", "&quot;")

/**
 * A [Container] backed entirely by an in-memory map of already-synthesised bytes. There is no
 * first-party in-memory [Container] in readium-shared 3.3.0 (only [org.readium.r2.shared.util.data.EmptyContainer]
 * and [org.readium.r2.shared.util.data.CompositeContainer], which compose real containers rather
 * than provide storage), so this is a minimal from-scratch implementation.
 */
private class InMemoryContainer(
    // Mutable map so [HighlightsPublicationHandle.setChapterBytes] can rewrite one chapter's
    // synthesised HTML after a per-annotation DOM patch — Readium re-fetches the resource from
    // this container on the NEXT navigation into that chapter, so the fresh bytes surface without
    // touching the Publication object.
    private val data: MutableMap<Url, ByteArray>,
) : Container<Resource> {

    // Snapshot at Publication-build time — the spine's shape is fixed for the lifetime of this
    // container. Byte-content mutations don't add/remove keys, they only rewrite the value.
    override val entries: Set<Url> = data.keys.toSet()

    override fun get(url: Url): Resource? {
        // Fresh lambda per read so subsequent [read()] calls surface the LATEST bytes rather than
        // whatever snapshot was captured at first-get time. Without this, a rewrite via
        // [setChapterBytes] wouldn't be visible until the Resource itself was re-vended.
        if (url !in data) return null
        return BytesResource(url as? AbsoluteUrl) { data[url] ?: ByteArray(0) }
    }

    override fun close() = Unit
}

private class BytesResource(
    override val sourceUrl: AbsoluteUrl?,
    private val bytesProvider: () -> ByteArray,
) : Resource {

    override suspend fun properties(): Try<Resource.Properties, ReadError> =
        Try.success(Resource.Properties())

    override suspend fun length(): Try<Long, ReadError> =
        Try.success(bytesProvider().size.toLong())

    override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> {
        val bytes = bytesProvider()
        val result = if (range == null) {
            bytes
        } else {
            val start = range.first.coerceIn(0, bytes.size.toLong()).toInt()
            val endExclusive = (range.last + 1).coerceIn(0, bytes.size.toLong()).toInt()
            if (endExclusive <= start) ByteArray(0) else bytes.copyOfRange(start, endExclusive)
        }
        return Try.success(result)
    }

    override fun close() = Unit
}
