package com.riffle.app.feature.reader.highlights

import com.riffle.feature.reader.highlights.ChapterElision
import com.riffle.feature.reader.highlights.EMPHASIS_ONLY_BAR_COLOR
import com.riffle.feature.reader.highlights.decodedEmbeddedFigures
import com.riffle.feature.reader.highlights.renderChapterHtml
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.PerResourcePositionsService
import org.readium.r2.shared.publication.services.search.StringSearchService
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource

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
    /** Href → data-URI map for figure bytes fetched at [HighlightsPublicationFactory.buildHandle]
     *  time. Exposed so the reader's live-patch path can pass the same map into [renderChapterHtml]
     *  when regenerating a chapter for a per-annotation edit. */
    val figureBytesByHref: Map<String, String>,
    /** Concatenated publisher `@font-face` rules (with base64-inlined font bytes) extracted from
     *  the source EPUB's stylesheets. */
    val publisherFontFaceCss: String,
) {
    /** Overwrite the bytes for [chapterHref] with [freshHtml]'s UTF-8 encoding. No-op if the
     *  chapter isn't in the current spine. */
    fun setChapterBytes(chapterHref: String, freshHtml: String) {
        val url = chapterUrls[chapterHref] ?: return
        byteStore[url] = freshHtml.toByteArray(Charsets.UTF_8)
    }
}

/**
 * Synthesises an in-memory Readium [Publication] out of a set of [ChapterElision]s so the elided
 * "highlights only" reader (ADR 0048) can be driven by the exact same [EpubNavigatorFragment] /
 * [EpubReaderViewModel] machinery as a full book.
 *
 * XHTML content is produced by [renderChapterHtml] in `feature:reader` commonMain — byte-identical
 * on both platforms. This Android-only class handles the Readium-Kotlin Publication assembly
 * (in-memory container, Publication, services).
 */
class HighlightsPublicationFactory constructor() {

    fun build(
        sourceId: String,
        itemId: String,
        bookTitle: String?,
        chapters: List<ChapterElision>,
        urlFactory: (String) -> Url? = { Url(it) },
        resourceFetcher: ResourceFetcher = ResourceFetcher { null },
        bookBodyFontFamily: String? = null,
        emphasisBarCss: String = EMPHASIS_ONLY_BAR_COLOR,
    ): Publication =
        buildHandle(sourceId, itemId, bookTitle, chapters, urlFactory, resourceFetcher, bookBodyFontFamily, emphasisBarCss)
            .publication

    fun buildHandle(
        sourceId: String,
        itemId: String,
        bookTitle: String?,
        chapters: List<ChapterElision>,
        urlFactory: (String) -> Url? = { Url(it) },
        resourceFetcher: ResourceFetcher = ResourceFetcher { null },
        bookBodyFontFamily: String? = null,
        emphasisBarCss: String = EMPHASIS_ONLY_BAR_COLOR,
    ): HighlightsPublicationHandle {
        val nonEmptyChapters = chapters.filter { it.highlights.isNotEmpty() }

        val entries = mutableMapOf<Url, ByteArray>()
        val readingOrder = mutableListOf<Link>()
        val chapterUrls = mutableMapOf<String, Url>()

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
            dataUriByHref[href] = "data:${mimeForHref(href)};base64," +
                java.util.Base64.getEncoder().encodeToString(bytes)
            val url = urlFactory(syntheticPath(href)) ?: continue
            entries[url] = bytes
        }

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
                chapter, bookBodyFontFamily, dataUriByHref, publisherFontFaceCss, emphasisBarCss,
            ).toByteArray(Charsets.UTF_8)
            chapterUrls[chapter.href] = url
            readingOrder += Link(
                href = url,
                mediaType = MediaType.XHTML,
                title = chapter.title,
            )
        }

        val manifest = Manifest(
            metadata = Metadata(
                conformsTo = setOf(Publication.Profile.EPUB),
                localizedTitle = LocalizedString(bookTitle ?: "Annotations"),
            ),
            readingOrder = readingOrder,
            tableOfContents = readingOrder,
        )

        @OptIn(ExperimentalReadiumApi::class)
        val publication = run {
            val searchFactory = StringSearchService.createDefaultFactory()
            Publication(
                manifest = manifest,
                container = InMemoryContainer(entries),
                servicesBuilder = Publication.ServicesBuilder(
                    positions = { ctx ->
                        PerResourcePositionsService(
                            readingOrder = ctx.manifest.readingOrder,
                            fallbackMediaType = MediaType.XHTML,
                        )
                    },
                    search = searchFactory,
                ),
            )
        }
        return HighlightsPublicationHandle(
            publication, chapterUrls, entries, dataUriByHref, publisherFontFaceCss,
        )
    }

    /**
     * Render one chapter's synthesised HTML. Delegates to the shared [renderChapterHtml] in
     * `feature:reader` commonMain so the same XHTML is produced on both platforms.
     */
    internal fun renderChapterHtml(
        chapter: ChapterElision,
        bookBodyFontFamily: String? = null,
        dataUriByHref: Map<String, String> = emptyMap(),
        publisherFontFaceCss: String = "",
        emphasisBarCss: String = EMPHASIS_ONLY_BAR_COLOR,
    ): String = com.riffle.feature.reader.highlights.renderChapterHtml(
        chapter, bookBodyFontFamily, dataUriByHref, publisherFontFaceCss, emphasisBarCss,
    )
}

// ─── Figure path + MIME helpers (Android-only — used only by buildHandle) ────

private fun syntheticPath(href: String): String = "synthetic/figures/" + href.replace('/', '_')

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

// ─── In-memory Readium Container ─────────────────────────────────────────────

private class InMemoryContainer(
    private val data: MutableMap<Url, ByteArray>,
) : Container<Resource> {

    override val entries: Set<Url> = data.keys.toSet()

    override fun get(url: Url): Resource? {
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
