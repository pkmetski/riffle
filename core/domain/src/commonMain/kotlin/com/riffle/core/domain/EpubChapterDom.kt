package com.riffle.core.domain

/**
 * A figure-bearing element (`<img>`, `<svg>`, `<picture>`, `<figure>`) found in a chapter body,
 * already positioned in the readable-character stream.
 *
 * This is the flattened, DOM-library-free view the highlight layer reasons over. Deciding *which*
 * figures a highlight encloses — the straddle rules, de-duplication, caption preference, ordering —
 * is shared logic and lives in `commonMain`; only the walk that produces these entries is
 * platform-bound (jsoup on JVM, ksoup on Kotlin/Native — `org.jsoup` has no K/N target).
 *
 * Char positions use the same readable-character model as [EpubTextChars.countReadableChars]:
 * blank-only text nodes contribute nothing, and a void element like `<img>` contributes zero
 * characters, so its span is empty and sits where the text before it ended.
 */
data class RawChapterFigure(
    /** Lowercased tag name of the matched element. */
    val tag: String,
    /** Readable characters emitted before this element. */
    val startChar: Long,
    /** Readable characters emitted after this element's subtree; equals [startChar] for void tags. */
    val endChar: Long,
    /**
     * Identity of the element this entry ultimately designates — a `<figure>` designates its inner
     * `<img>`/`<svg>`/`<picture>`, every other tag designates itself. Two entries sharing a
     * [targetId] are the same figure reached two ways and must collapse to one.
     *
     * Only unique and meaningful within a single [EpubChapterDomOps.figures] call.
     */
    val targetId: Int,
    /** Lowercased tag name of the designated target. */
    val targetTag: String,
    /** Nearest enclosing `<figure>`'s `<figcaption>` text; else the target's `alt`, else its `aria-label`. */
    val caption: String,
    /** `src` of the target image — the target itself, or a `<picture>`'s inner `<img>`. Null for SVG. */
    val imageSrc: String?,
    /** Serialised markup when the target is an `<svg>`. Null otherwise. */
    val svgOuterHtml: String?,
)

/**
 * The chapter-DOM primitives the highlight and annotation layers need, behind a platform seam.
 *
 * Every implementation must walk identically: the same chapter HTML has to yield the same readable
 * text, the same figure positions and the same range CFI on either platform, or a highlight created
 * on one device re-anchors to the wrong span on the other.
 */
interface EpubChapterDomOps {
    /**
     * Concatenation of the body's non-blank text nodes in document order — the "flat" view of the
     * chapter that every highlight char offset indexes into.
     *
     * Its length is by construction equal to [EpubTextChars.countReadableChars] of the same body.
     */
    fun readableBodyText(html: String): String

    /** Every figure-bearing element in the body, in document order. */
    fun figures(html: String): List<RawChapterFigure>

    /**
     * Build an EPUB range CFI (ADR 0028) spanning `[startChar, endChar]` of the body's readable
     * text, at spine step [spineStep] (i.e. `/6/<spineStep>`). Null when either offset falls
     * outside the document.
     */
    fun buildCfiRange(spineStep: Int, html: String, startChar: Long, endChar: Long): String?
}

/** The platform's chapter-DOM implementation: jsoup on JVM/Android, ksoup on Kotlin/Native. */
expect fun epubChapterDomOps(): EpubChapterDomOps

/** Tags that carry a figure for annotation purposes. Both DOM walks must agree on this set. */
val FIGURE_TAGS: Set<String> = setOf("img", "svg", "picture", "figure")

/**
 * Factor two absolute CFI doc paths into the range form both endpoints share:
 * `epubcfi(/6/<spineStep>!<commonParent>,<startRemainder>,<endRemainder>)`.
 *
 * e.g. `epubcfi(/6/4!/4/2,/1:0,/1:5)` — both ends inside the same text node of the first paragraph.
 * Pure string arithmetic, so it is shared by every platform's [EpubChapterDomOps.buildCfiRange].
 */
fun assembleCfiRange(spineStep: Int, startPath: String, endPath: String): String {
    val startTokens = startPath.trimStart('/').split('/')
    val endTokens = endPath.trimStart('/').split('/')
    var common = 0
    while (common < startTokens.size && common < endTokens.size && startTokens[common] == endTokens[common]) {
        common++
    }
    val parent = "/" + startTokens.take(common).joinToString("/")
    val startRemainder = "/" + startTokens.drop(common).joinToString("/")
    val endRemainder = "/" + endTokens.drop(common).joinToString("/")
    return "epubcfi(/6/$spineStep!$parent,$startRemainder,$endRemainder)"
}

/** The platform's [EpubCfiOps]: `JvmEpubCfiOps` (jsoup) or `IosEpubCfiOps` (ksoup). */
expect fun epubCfiOps(): EpubCfiOps
