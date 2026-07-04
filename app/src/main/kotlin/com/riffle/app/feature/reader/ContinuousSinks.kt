package com.riffle.app.feature.reader

import androidx.compose.ui.unit.IntRect
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

/**
 * Navigation events the continuous stack surfaces upward — chrome toggle taps and
 * raw-position updates that feed the reader's position pipeline.
 */
internal interface ContinuousNavigationSink {
    fun onTap()
    fun onLocator(locator: Locator)
}

/**
 * Hyperlink events surfaced from tapped chapter content. `onFollowInternalLink` gets
 * the resolved reading-order [Link] plus the origin [Locator] so the host can build a
 * return-to-position card; `onFootnote` receives the resolved footnote body.
 */
internal interface ContinuousLinkSink {
    fun onFollowInternalLink(link: Link, origin: Locator)
    fun onExternalLink(url: String)
    fun onFootnote(content: FootnoteContent)

    /**
     * A same-document non-footnote anchor was tapped and the outer viewport is about to scroll to
     * it. Passes the pre-jump [origin] so the host can offer a "Back" card, mirroring the paged
     * mode's [FootnoteAnchorBridge] cross-reference path.
     */
    fun captureReturnAnchor(origin: Locator)
}

/**
 * Annotation + readaloud events surfaced from chapter selections or taps on existing marks.
 * Rects are in device pixels relative to the reader viewport.
 */
internal interface ContinuousAnnotationSink {
    fun onAnnotationTap(id: String, rect: IntRect)
    fun onAnnotationNoteTap(id: String, rect: IntRect)
    fun onHighlight(locator: Locator, rect: IntRect)
    fun onPlayFromHere(fragmentRef: String)
}
