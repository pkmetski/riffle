package com.riffle.app.feature.reader.highlights

import javax.inject.Inject

/**
 * Resolves a figure's source `href` (as stored on [com.riffle.core.database.AnnotationEntity.imageHref]
 * / [com.riffle.core.models.EmbeddedFigure.href]) to the raw image bytes backing it, so
 * [HighlightsPublicationFactory] can serve raster figures inside the synthesised elided-reader
 * Publication (ADR 0041, Task 9).
 *
 * [href] is relative to the source book's own EPUB container — this seam exists so the factory
 * itself never needs to know how to resolve it (real container lookup on-device vs. a fixed map in
 * JVM tests). Returns null when the resource cannot be found; callers must treat that as
 * "figure image missing" (render the figcaption without an `<img>`), not as an error.
 */
fun interface ResourceFetcher {
    fun fetch(href: String): ByteArray?
}

/**
 * Always-null fetcher. Bound in production today (Task 9 follow-up): wiring a real fetcher backed
 * by the source book's Readium [org.readium.r2.shared.util.data.Container] requires
 * [HighlightsPublicationFactory]'s caller ([com.riffle.app.feature.reader.EpubReaderViewModel]) to
 * hold the source `Publication` at the point it loads Highlights mode, which it does not do today
 * (Highlights mode is diverted before the normal `lifecycle.open()` container-loading path — see
 * [ReaderSource]'s KDoc). Until that wiring lands, raster/SVG figures render as
 * figcaption-only (missing `<img>`/inline SVG), which is the documented "missing image bytes fall
 * back to figcaption-only" behaviour.
 */
class NoopResourceFetcher @Inject constructor() : ResourceFetcher {
    override fun fetch(href: String): ByteArray? = null
}
