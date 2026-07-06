package com.riffle.app.feature.reader.highlights

import com.riffle.app.feature.reader.toCssRgba
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.HighlightColor
import javax.inject.Inject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource

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
        serverId: String,
        itemId: String,
        bookTitle: String?,
        chapters: List<ChapterElision>,
        urlFactory: (String) -> Url? = { Url(it) },
    ): Publication {
        val nonEmptyChapters = chapters.filter { it.highlights.isNotEmpty() }

        val entries = mutableMapOf<Url, ByteArray>()
        val readingOrder = mutableListOf<Link>()

        nonEmptyChapters.forEachIndexed { index, chapter ->
            val href = "highlights/ch$index.xhtml"
            val url = requireNotNull(urlFactory(href)) { "Failed to build synthetic Url for $href" }
            entries[url] = renderChapterHtml(chapter).toByteArray(Charsets.UTF_8)
            readingOrder += Link(
                href = url,
                mediaType = MediaType.XHTML,
                title = chapter.title,
            )
        }

        val manifest = Manifest(
            metadata = Metadata(localizedTitle = LocalizedString(bookTitle ?: "Annotations")),
            readingOrder = readingOrder,
            tableOfContents = readingOrder,
        )

        return Publication(
            manifest = manifest,
            container = InMemoryContainer(entries),
        )
    }

    private fun renderChapterHtml(chapter: ChapterElision): String {
        val body = buildString {
            for (highlight in chapter.highlights) {
                append("  <p class=\"riffle-hl\" data-ann-id=\"")
                append(highlight.id.xmlEscape())
                append("\" style=\"background-color: ")
                append(highlightBackgroundCss(highlight.color))
                append(";\">")
                append(highlight.textSnippet.xmlEscape())
                append("</p>\n")
                val note = highlight.note
                if (note != null) {
                    append("  <aside class=\"riffle-note\" data-ann-id=\"")
                    append(highlight.id.xmlEscape())
                    append("\" style=\"background-color: ")
                    append(NOTE_BACKGROUND_CSS)
                    append(";\">")
                    append(note.xmlEscape())
                    append("</aside>\n")
                }
            }
        }
        val title = chapter.title.xmlEscape()
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head>
            |<body>
            |  <h1>$title</h1>
            |${body.trimEnd('\n')}
            |</body></html>
        """.trimMargin()
    }
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

/** Paler, neutral background for a highlight's note `<aside>` — distinguishes it from the highlight
 * paragraph above it without introducing a second colour token. */
private const val NOTE_BACKGROUND_CSS = "#f5f5f5"

private fun String.xmlEscape(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

/**
 * A [Container] backed entirely by an in-memory map of already-synthesised bytes. There is no
 * first-party in-memory [Container] in readium-shared 3.3.0 (only [org.readium.r2.shared.util.data.EmptyContainer]
 * and [org.readium.r2.shared.util.data.CompositeContainer], which compose real containers rather
 * than provide storage), so this is a minimal from-scratch implementation.
 */
private class InMemoryContainer(
    private val data: Map<Url, ByteArray>,
) : Container<Resource> {

    override val entries: Set<Url> = data.keys

    override fun get(url: Url): Resource? {
        val bytes = data[url] ?: return null
        return BytesResource(url as? AbsoluteUrl, bytes)
    }

    override fun close() = Unit
}

private class BytesResource(
    override val sourceUrl: AbsoluteUrl?,
    private val bytes: ByteArray,
) : Resource {

    override suspend fun properties(): Try<Resource.Properties, ReadError> =
        Try.success(Resource.Properties())

    override suspend fun length(): Try<Long, ReadError> =
        Try.success(bytes.size.toLong())

    override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> {
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
